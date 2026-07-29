package com.thelightphone.homeassistant

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Keeps a WebSocket open while the dashboard is on screen and pushes entity
 * state changes as they happen, using the frontend's `subscribe_entities`
 * command (compressed, diff-based, filtered to the entities we render).
 */
class HaLiveConnection(
    private val server: ServerConfig,
    private val entityIds: List<String>,
    private val scope: CoroutineScope,
    private val onStates: (Map<String, HaState>) -> Unit,
    private val onConnected: (Boolean) -> Unit,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var job: Job? = null
    private val states = mutableMapOf<String, HaState>()

    fun start() {
        if (job?.isActive == true || entityIds.isEmpty()) return
        job = scope.launch {
            var backoffMillis = 2_000L
            while (isActive) {
                val clean = runCatching { connect() }
                    .onFailure { android.util.Log.w("HomeTool", "live connection: ${it.message}") }
                    .getOrDefault(false)
                onConnected(false)
                if (!isActive) return@launch
                backoffMillis = if (clean) 2_000L else (backoffMillis * 2).coerceAtMost(60_000L)
                delay(backoffMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        onConnected(false)
    }

    /** Returns true when the socket closed without an error. */
    private suspend fun connect(): Boolean {
        val wsUrl = server.url.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/api/websocket"

        var subscribed = false
        val client = HttpClient(OkHttp) { install(WebSockets) }
        try {
            client.webSocket(wsUrl) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val msg = json.parseToJsonElement(frame.readText()).jsonObject
                    when (msg.str("type")) {
                        "auth_required" -> send(
                            Frame.Text(
                                buildJsonObject {
                                    put("type", "auth")
                                    put("access_token", server.token)
                                }.toString(),
                            ),
                        )
                        "auth_invalid" -> {
                            android.util.Log.w("HomeTool", "live connection: invalid token")
                            return@webSocket
                        }
                        "auth_ok" -> {
                            send(
                                Frame.Text(
                                    buildJsonObject {
                                        put("id", SUBSCRIBE_ID)
                                        put("type", "subscribe_entities")
                                        put(
                                            "entity_ids",
                                            buildJsonArray {
                                                entityIds.forEach { add(JsonPrimitive(it)) }
                                            },
                                        )
                                    }.toString(),
                                ),
                            )
                            subscribed = true
                            onConnected(true)
                        }
                        "event" -> applyEvent(msg["event"] as? JsonObject ?: continue)
                    }
                }
            }
        } finally {
            client.close()
        }
        return subscribed
    }

    /**
     * Applies one compressed state event: "a" adds full states, "c" carries
     * per-entity diffs ("+" = changed values), "r" removes entities.
     */
    private fun applyEvent(event: JsonObject) {
        (event["a"] as? JsonObject)?.forEach { (entityId, element) ->
            val compact = element as? JsonObject ?: return@forEach
            states[entityId] = HaState(
                entity_id = entityId,
                state = compact.str("s").orEmpty(),
                attributes = compact["a"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }

        (event["c"] as? JsonObject)?.forEach { (entityId, element) ->
            val change = element as? JsonObject ?: return@forEach
            val plus = change["+"] as? JsonObject ?: return@forEach
            val existing = states[entityId]
            val mergedAttributes = (plus["a"] as? JsonObject)?.let { updated ->
                JsonObject(existing?.attributes.orEmpty() + updated)
            } ?: existing?.attributes ?: JsonObject(emptyMap())
            states[entityId] = HaState(
                entity_id = entityId,
                state = plus.str("s") ?: existing?.state.orEmpty(),
                attributes = mergedAttributes,
            )
        }

        (event["r"] as? JsonArray)?.forEach { element ->
            (element as? JsonPrimitive)?.contentOrNull?.let { states.remove(it) }
        }

        onStates(states.toMap())
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> =
        this ?: emptyMap()

    private companion object {
        const val SUBSCRIBE_ID = 10
    }
}
