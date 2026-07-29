package com.thelightphone.homeassistant

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Minimal Home Assistant client: plain OkHttp for REST (states/services),
 * ktor for the WebSocket dashboard-config fetch.
 */
class HaClient(private val server: ServerConfig) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient()
    private val baseUrl = server.url.trimEnd('/')

    /** GET /api/ — cheapest authenticated call; verifies URL + token. */
    suspend fun validate(): Result<Unit> = runCatching {
        get("/api/")
        Unit
    }

    suspend fun fetchStates(): Result<Pair<Map<String, HaState>, String>> = runCatching {
        val raw = get("/api/states")
        json.decodeFromString<List<HaState>>(raw).associateBy { it.entity_id } to raw
    }

    /** Parses a cached /api/states body; used to paint the UI before the network call. */
    fun parseStates(raw: String): Map<String, HaState>? = runCatching {
        json.decodeFromString<List<HaState>>(raw).associateBy { it.entity_id }
    }.getOrNull()

    /** Parses a cached Lovelace config body. */
    fun parseDashboard(raw: String): List<DashView>? = runCatching {
        LovelaceParser.parse(json.parseToJsonElement(raw).jsonObject)
    }.getOrNull()

    suspend fun callService(
        call: HaActions.ServiceCall,
        entityId: String,
        data: Map<String, Any> = emptyMap(),
    ): Result<Unit> = runCatching {
        val body = buildJsonObject {
            put("entity_id", entityId)
            data.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    is Double ->
                        if (value == value.toInt().toDouble()) put(key, value.toInt())
                        else put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }.toString()
        post("/api/services/${call.domain}/${call.service}", body)
        Unit
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        http.newCall(request(path).get().build()).execute().use { it.checkAndRead() }
    }

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val jsonBody = body.toRequestBody("application/json".toMediaType())
        http.newCall(request(path).post(jsonBody).build()).execute().use { it.checkAndRead() }
    }

    private fun request(path: String): Request.Builder = Request.Builder()
        .url(baseUrl + path)
        .header("Authorization", "Bearer ${server.token}")

    private fun okhttp3.Response.checkAndRead(): String {
        val text = body?.string().orEmpty()
        when {
            code == 401 -> error("Invalid token (401).")
            !isSuccessful -> error("HTTP $code ${text.take(120)}")
        }
        return text
    }

    /** The instance's home coordinates from /api/config, used as the map origin. */
    suspend fun fetchHomeCoordinates(): Result<Pair<Double, Double>> = runCatching {
        val config = json.parseToJsonElement(get("/api/config")).jsonObject
        val latitude = (config["latitude"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val longitude = (config["longitude"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        if (latitude == null || longitude == null) error("Instance has no home coordinates.")
        latitude to longitude
    }

    /**
     * Sends a sentence to HA's conversation agent. Passing back the
     * conversation id keeps a follow-up question in the same context.
     */
    suspend fun converse(
        text: String,
        conversationId: String? = null,
        language: String? = null,
        agentId: String? = null,
    ): Result<AssistReply> = runCatching {
        val body = buildJsonObject {
            put("text", text)
            conversationId?.let { put("conversation_id", it) }
            language?.let { put("language", it) }
            agentId?.let { put("agent_id", it) }
        }.toString()
        val response = json.parseToJsonElement(post("/api/conversation/process", body)).jsonObject
        AssistReply(
            speech = response.speechText() ?: "…",
            conversationId = (response["conversation_id"] as? JsonPrimitive)?.contentOrNull,
            continueConversation =
                (response["continue_conversation"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    private fun JsonObject.speechText(): String? =
        ((this["response"] as? JsonObject)
            ?.get("speech") as? JsonObject)
            ?.get("plain")
            ?.let { it as? JsonObject }
            ?.get("speech")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }

    /**
     * Lists the instance's Assist pipelines. Which one answers matters: a
     * pipeline pointing at an agent that is no longer loaded (an unplugged
     * Ollama, say) fails every question until another is chosen.
     */
    suspend fun fetchPipelines(): Result<List<AssistPipeline>> = runCatching {
        val result = websocketCommand(
            buildJsonObject {
                put("id", PIPELINES_COMMAND_ID)
                put("type", "assist_pipeline/pipeline/list")
            },
        ) ?: error("Assist is not set up on this server.")

        val preferred = (result["preferred_pipeline"] as? JsonPrimitive)?.contentOrNull
        (result["pipelines"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
            val pipeline = it as? JsonObject ?: return@mapNotNull null
            val id = (pipeline["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            AssistPipeline(
                id = id,
                name = (pipeline["name"] as? JsonPrimitive)?.contentOrNull ?: id,
                conversationEngine =
                    (pipeline["conversation_engine"] as? JsonPrimitive)?.contentOrNull,
                preferred = id == preferred,
            )
        }
    }

    private fun kotlinx.serialization.json.JsonArray?.orEmpty() =
        this ?: kotlinx.serialization.json.JsonArray(emptyList())

    /** Runs one authenticated WebSocket command and returns its result object. */
    private suspend fun websocketCommand(command: JsonObject): JsonObject? {
        val wsUrl = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/api/websocket"
        var result: JsonObject? = null
        var failure: String? = null

        val ws = HttpClient(OkHttp) { install(WebSockets) }
        try {
            ws.webSocket(wsUrl) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = json.parseToJsonElement(frame.readText()).jsonObject
                    when ((message["type"] as? JsonPrimitive)?.contentOrNull) {
                        "auth_required" -> send(
                            Frame.Text(
                                buildJsonObject {
                                    put("type", "auth")
                                    put("access_token", server.token)
                                }.toString(),
                            ),
                        )
                        "auth_invalid" -> {
                            failure = "Invalid token."
                            close()
                            break
                        }
                        "auth_ok" -> send(Frame.Text(command.toString()))
                        "result" -> {
                            result = message["result"] as? JsonObject
                            if ((message["success"] as? JsonPrimitive)?.contentOrNull != "true") {
                                failure = (message["error"] as? JsonObject)
                                    ?.let { (it["message"] as? JsonPrimitive)?.contentOrNull }
                                    ?: "Command failed."
                            }
                            close()
                            break
                        }
                    }
                }
            }
        } finally {
            ws.close()
        }
        failure?.let { error(it) }
        return result
    }

    /** The instance's zones, for geofencing arrivals and departures. */
    suspend fun fetchZones(): Result<List<HaZone>> = runCatching {
        json.decodeFromString<List<HaState>>(get("/api/states"))
            .filter { it.domain == "zone" }
            .mapNotNull { zone ->
                val latitude = zone.number("latitude") ?: return@mapNotNull null
                val longitude = zone.number("longitude") ?: return@mapNotNull null
                HaZone(
                    entityId = zone.entity_id,
                    name = zone.friendlyName,
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = (zone.number("radius") ?: 100.0).toFloat(),
                )
            }
    }

    /** Downloads a file (a TTS answer, say) into the tool's own storage. */
    suspend fun download(url: String, target: java.io.File): Result<java.io.File> = runCatching {
        withContext(Dispatchers.IO) {
            val request = if (url.startsWith(baseUrl)) {
                Request.Builder().url(url).header("Authorization", "Bearer ${server.token}")
            } else {
                Request.Builder().url(url)
            }
            http.newCall(request.get().build()).execute().use { response ->
                if (!response.isSuccessful) error("Download: HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: error("Empty download.")
                target.writeBytes(bytes)
                target
            }
        }
    }

    /** Fetches a still frame for a camera entity as JPEG bytes. */
    suspend fun cameraSnapshot(entityId: String): Result<ByteArray> = runCatching {
        withContext(Dispatchers.IO) {
            http.newCall(request("/api/camera_proxy/$entityId").get().build()).execute().use {
                if (it.code == 401) error("Invalid token (401).")
                if (!it.isSuccessful) error("Camera: HTTP ${it.code}")
                it.body?.bytes() ?: error("Empty snapshot.")
            }
        }
    }

    /**
     * Registers this device with HA's mobile_app integration; creates a device
     * entry plus notify.mobile_app_* service and returns the webhook credentials.
     */
    suspend fun register(deviceId: String): Result<RegistrationResponse> = runCatching {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("app_id", "com.thelightphone.homeassistant")
            put("app_name", "Home for Light Phone")
            put("app_version", "0.1.0")
            put("device_name", "Light Phone 3")
            put("manufacturer", "Light")
            put("model", "Light Phone III")
            put("os_name", "LightOS")
            put("os_version", android.os.Build.VERSION.RELEASE ?: "unknown")
            put("supports_encryption", false)
        }.toString()
        json.decodeFromString<RegistrationResponse>(
            post("/api/mobile_app/registrations", body),
        )
    }

    /** Thrown when HA returns 410 for our webhook: registration was deleted. */
    class WebhookGoneException : Exception("Webhook registration deleted (410).")

    /**
     * Sends a mobile_app webhook message. Uses the Nabu Casa cloudhook when
     * available, otherwise the instance URL. No auth needed: the id is the secret.
     */
    suspend fun webhook(type: String, data: kotlinx.serialization.json.JsonElement): Result<Unit> =
        runCatching {
            val webhookId = server.webhookId ?: error("Not registered.")
            val url = server.cloudhookUrl ?: "$baseUrl/api/webhook/$webhookId"
            val body = buildJsonObject {
                put("type", type)
                put("data", data)
            }.toString()
            withContext(Dispatchers.IO) {
                val jsonBody = body.toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder().url(url).post(jsonBody).build()).execute().use {
                    if (it.code == 410) throw WebhookGoneException()
                    if (!it.isSuccessful) error("Webhook: HTTP ${it.code}")
                }
            }
        }

    suspend fun registerBatterySensor(level: Int): Result<Unit> = webhook(
        "register_sensor",
        buildJsonObject {
            put("device_class", "battery")
            put("icon", "mdi:battery")
            put("name", "Battery level")
            put("state", level)
            put("type", "sensor")
            put("unique_id", "battery_level")
            put("unit_of_measurement", "%")
            put("state_class", "measurement")
            put("entity_category", "diagnostic")
        },
    )

    suspend fun updateBatterySensor(level: Int): Result<Unit> = webhook(
        "update_sensor_states",
        kotlinx.serialization.json.buildJsonArray {
            add(
                buildJsonObject {
                    put("icon", "mdi:battery")
                    put("state", level)
                    put("type", "sensor")
                    put("unique_id", "battery_level")
                },
            )
        },
    )

    /** Sends a location update; ready for when LightOS exposes a GPS primitive. */
    suspend fun updateLocation(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Int,
        battery: Int?,
    ): Result<Unit> = webhook(
        "update_location",
        buildJsonObject {
            put(
                "gps",
                kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(latitude))
                    add(JsonPrimitive(longitude))
                },
            )
            put("gps_accuracy", accuracyMeters)
            battery?.let { put("battery", it) }
        },
    )

    /**
     * Fetches the Lovelace config of [ServerConfig.dashboard] over the WebSocket API
     * (the same `lovelace/config` command the HA frontend uses).
     */
    suspend fun fetchDashboard(): Result<Pair<List<DashView>, String>> = runCatching {
        val wsUrl = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/api/websocket"

        var config: JsonObject? = null
        var failure: String? = null

        val ws = HttpClient(OkHttp) { install(WebSockets) }
        try {
            ws.webSocket(wsUrl) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val msg = json.parseToJsonElement(frame.readText()).jsonObject
                    when ((msg["type"] as? JsonPrimitive)?.contentOrNull) {
                        "auth_required" -> sendJson(
                            buildJsonObject {
                                put("type", "auth")
                                put("access_token", server.token)
                            },
                        )
                        "auth_invalid" -> {
                            failure = "Invalid token."
                            close()
                            break
                        }
                        "auth_ok" -> sendJson(
                            buildJsonObject {
                                put("id", 1)
                                put("type", "lovelace/config")
                                if (server.dashboard.isNotBlank() && server.dashboard != "default") {
                                    put("url_path", server.dashboard)
                                }
                            },
                        )
                        "result" -> {
                            val success =
                                (msg["success"] as? JsonPrimitive)?.contentOrNull == "true"
                            if (success) {
                                config = msg["result"] as? JsonObject
                            } else {
                                failure = (msg["error"] as? JsonObject)
                                    ?.let { (it["message"] as? JsonPrimitive)?.contentOrNull }
                                    ?: "Dashboard not found."
                            }
                            close()
                            break
                        }
                    }
                }
            }
        } finally {
            ws.close()
        }

        failure?.let { error(it) }
        val resolved = config ?: error("No dashboard config received.")
        LovelaceParser.parse(resolved) to resolved.toString()
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendJson(
        obj: JsonObject,
    ) = send(Frame.Text(obj.toString()))

    fun close() {
        // No evictAll here: closing pooled TLS sockets performs network I/O and
        // crashes with NetworkOnMainThreadException when called from main.
        runCatching { http.dispatcher.executorService.shutdown() }
    }

    private companion object {
        const val PIPELINES_COMMAND_ID = 30
    }
}
