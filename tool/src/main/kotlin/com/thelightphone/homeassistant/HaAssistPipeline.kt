package com.thelightphone.homeassistant

import com.thelightphone.sdk.audio.CaptureConfig
import com.thelightphone.sdk.audio.LightAudio
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs one spoken exchange through HA's Assist pipeline: microphone → speech
 * to text → intent → spoken answer.
 *
 * Audio goes up the same WebSocket as binary frames, each prefixed with the
 * handler id the server hands out when the run starts. HA answers with events
 * as it goes, so the transcript can appear while the intent is still running.
 */
class HaAssistPipeline(
    private val server: ServerConfig,
    private val audio: LightAudio,
    private val scope: CoroutineScope,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var captureJob: Job? = null

    /** Set when the user taps stop; the audio stream ends at the next chunk. */
    @Volatile
    private var stopRequested = false

    fun requestStop() {
        stopRequested = true
    }

    /**
     * @param onTranscript what HA heard
     * @param onReply the agent's answer
     * @param onSpeech URL of the spoken answer, if the pipeline produced one
     */
    suspend fun run(
        conversationId: String?,
        pipelineId: String? = null,
        onListening: () -> Unit,
        onTranscript: (String) -> Unit,
        onReply: (AssistReply) -> Unit,
        onSpeech: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stopRequested = false
        val wsUrl = server.url.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/api/websocket"

        val client = HttpClient(OkHttp) { install(WebSockets) }
        try {
            client.webSocket(wsUrl) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = json.parseToJsonElement(frame.readText()).jsonObject
                    when (message.str("type")) {
                        "auth_required" -> send(
                            Frame.Text(
                                buildJsonObject {
                                    put("type", "auth")
                                    put("access_token", server.token)
                                }.toString(),
                            ),
                        )
                        "auth_invalid" -> {
                            onError("Invalid token.")
                            return@webSocket
                        }
                        "auth_ok" -> send(Frame.Text(runRequest(conversationId, pipelineId)))
                        "result" -> {
                            val ok = (message["success"] as? JsonPrimitive)?.contentOrNull == "true"
                            if (!ok) {
                                onError(
                                    (message["error"] as? JsonObject)?.str("message")
                                        ?: "Assist is not available.",
                                )
                                return@webSocket
                            }
                        }
                        "event" -> {
                            val event = message["event"] as? JsonObject ?: continue
                            val data = event["data"] as? JsonObject
                            when (event.str("type")) {
                                "run-start" -> {
                                    val handlerId = ((data?.get("runner_data") as? JsonObject)
                                        ?.get("stt_binary_handler_id") as? JsonPrimitive)
                                        ?.contentOrNull?.toIntOrNull() ?: continue
                                    onListening()
                                    streamMicrophone(handlerId, onError)
                                }
                                "stt-end" -> {
                                    captureJob?.cancel()
                                    ((data?.get("stt_output") as? JsonObject)?.str("text"))
                                        ?.let(onTranscript)
                                }
                                "intent-end" -> {
                                    val response = (data?.get("intent_output") as? JsonObject)
                                    onReply(
                                        AssistReply(
                                            speech = response?.speechText() ?: "…",
                                            conversationId = response?.str("conversation_id"),
                                            continueConversation = false,
                                        ),
                                    )
                                }
                                "tts-end" -> {
                                    ((data?.get("tts_output") as? JsonObject)?.str("url"))
                                        ?.let { onSpeech(server.url.trimEnd('/') + it) }
                                }
                                "error" -> onError(data?.str("message") ?: "Assist failed.")
                                "run-end" -> return@webSocket
                            }
                        }
                    }
                }
            }
        } finally {
            captureJob?.cancel()
            captureJob = null
            client.close()
        }
    }

    private fun runRequest(conversationId: String?, pipelineId: String?) = buildJsonObject {
        put("id", RUN_ID)
        put("type", "assist_pipeline/run")
        put("start_stage", "stt")
        put("end_stage", "tts")
        conversationId?.let { put("conversation_id", it) }
        pipelineId?.let { put("pipeline", it) }
        put(
            "input",
            buildJsonObject { put("sample_rate", SAMPLE_RATE) },
        )
    }.toString()

    /** Pumps microphone buffers up the socket until stopped or cancelled. */
    private fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.streamMicrophone(
        handlerId: Int,
        onError: (String) -> Unit,
    ) {
        captureJob = scope.launch {
            val capture = audio.newCapture(CaptureConfig(sampleRate = SAMPLE_RATE))
            runCatching {
                capture.asFlow().buffer().collect { samples ->
                    if (stopRequested) {
                        // An empty payload is how the pipeline is told the
                        // utterance is over.
                        send(Frame.Binary(true, byteArrayOf(handlerId.toByte())))
                        return@collect
                    }
                    send(Frame.Binary(true, byteArrayOf(handlerId.toByte()) + samples.toBytes()))
                }
            }.onFailure { failure ->
                android.util.Log.w("HomeTool", "assist capture: ${failure.message}")
                onError(failure.message ?: "Could not use the microphone.")
            }
        }
    }

    /** HA expects signed 16-bit little-endian PCM. */
    private fun ShortArray.toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        forEach { buffer.putShort(it) }
        return buffer.array()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.speechText(): String? =
        ((this["response"] as? JsonObject)?.get("speech") as? JsonObject)
            ?.get("plain")
            ?.let { it as? JsonObject }
            ?.let { (it["speech"] as? JsonPrimitive)?.contentOrNull }

    private companion object {
        const val RUN_ID = 20
        const val SAMPLE_RATE = 16_000
    }
}
