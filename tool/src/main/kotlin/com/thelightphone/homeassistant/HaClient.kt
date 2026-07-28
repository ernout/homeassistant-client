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

    suspend fun fetchStates(): Result<Map<String, HaState>> = runCatching {
        json.decodeFromString<List<HaState>>(get("/api/states"))
            .associateBy { it.entity_id }
    }

    suspend fun callService(call: HaActions.ServiceCall, entityId: String): Result<Unit> =
        runCatching {
            val body = buildJsonObject { put("entity_id", entityId) }.toString()
            post("/api/services/${call.domain}/${call.service}", body)
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
    suspend fun fetchDashboard(): Result<List<DashView>> = runCatching {
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
        LovelaceParser.parse(config ?: error("No dashboard config received."))
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendJson(
        obj: JsonObject,
    ) = send(Frame.Text(obj.toString()))

    fun close() {
        // No evictAll here: closing pooled TLS sockets performs network I/O and
        // crashes with NetworkOnMainThreadException when called from main.
        runCatching { http.dispatcher.executorService.shutdown() }
    }
}
