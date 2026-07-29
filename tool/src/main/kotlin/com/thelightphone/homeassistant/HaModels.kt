package com.thelightphone.homeassistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
    val dashboard: String = "light-phone",
    val webhookId: String? = null,
    val cloudhookUrl: String? = null,
    val remoteUiUrl: String? = null,
    /** Opt-in: don't report where the phone is unless asked to. */
    val sendLocation: Boolean = false,
    val sendBattery: Boolean = true,
)

@Serializable
data class RegistrationResponse(
    val webhook_id: String,
    val cloudhook_url: String? = null,
    val remote_ui_url: String? = null,
)

/** Payload for the combined onboarding QR: {"url": ..., "token": ..., "name": ...} */
@Serializable
data class QrPayload(
    val url: String,
    val token: String,
    val name: String? = null,
)

@Serializable
data class HaState(
    val entity_id: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
) {
    val friendlyName: String
        get() = (attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: entity_id
    val unit: String?
        get() = (attributes["unit_of_measurement"] as? JsonPrimitive)?.contentOrNull
    val domain: String
        get() = entity_id.substringBefore(".")

    fun number(key: String): Double? =
        (attributes[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    fun text(key: String): String? =
        (attributes[key] as? JsonPrimitive)?.contentOrNull

    fun flag(key: String): Boolean? =
        (attributes[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    fun textList(key: String): List<String> =
        (attributes[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    /** Brightness as a percentage; HA reports it 0-255. */
    val brightnessPercent: Int?
        get() = number("brightness")?.let { (it / 255.0 * 100).toInt() }

    val supportsBrightness: Boolean
        get() = attributes["brightness"] != null ||
            (attributes["supported_color_modes"] as? JsonArray)
                ?.any { (it as? JsonPrimitive)?.contentOrNull in BRIGHTNESS_MODES } == true
}

// Not a companion object inside HaState: kotlinx.serialization resolves the
// serializer through HaState.Companion, so declaring a private one there makes
// it inaccessible to callers at runtime.
/** Arm modes an alarm panel advertises through supported_features. */
object AlarmModes {
    private const val ARM_HOME = 1
    private const val ARM_AWAY = 2
    private const val ARM_NIGHT = 4
    private const val ARM_VACATION = 32

    /** Service name to label, for the modes this panel actually supports. */
    fun available(supportedFeatures: Int): List<Pair<String, String>> = buildList {
        if (supportedFeatures and ARM_HOME != 0) add("alarm_arm_home" to "Arm home")
        if (supportedFeatures and ARM_AWAY != 0) add("alarm_arm_away" to "Arm away")
        if (supportedFeatures and ARM_NIGHT != 0) add("alarm_arm_night" to "Arm night")
        if (supportedFeatures and ARM_VACATION != 0) add("alarm_arm_vacation" to "Arm vacation")
    }

    /** The state each arm service leads to, so the active one can be marked. */
    fun stateFor(service: String): String = when (service) {
        "alarm_arm_home" -> "armed_home"
        "alarm_arm_away" -> "armed_away"
        "alarm_arm_night" -> "armed_night"
        "alarm_arm_vacation" -> "armed_vacation"
        else -> "disarmed"
    }
}

/** on/off wording per binary_sensor device class, following HA's frontend. */
private val BINARY_SENSOR_LABELS: Map<String, Pair<String, String>> = mapOf(
    "battery" to ("Low" to "Normal"),
    "battery_charging" to ("Charging" to "Not charging"),
    "carbon_monoxide" to ("Detected" to "Clear"),
    "cold" to ("Cold" to "Normal"),
    "connectivity" to ("Connected" to "Disconnected"),
    "door" to ("Open" to "Closed"),
    "garage_door" to ("Open" to "Closed"),
    "gas" to ("Detected" to "Clear"),
    "heat" to ("Hot" to "Normal"),
    "light" to ("Light" to "No light"),
    "lock" to ("Unlocked" to "Locked"),
    "moisture" to ("Wet" to "Dry"),
    "motion" to ("Detected" to "Clear"),
    "moving" to ("Moving" to "Still"),
    "occupancy" to ("Detected" to "Clear"),
    "opening" to ("Open" to "Closed"),
    "plug" to ("Plugged in" to "Unplugged"),
    "power" to ("Detected" to "No power"),
    "presence" to ("Home" to "Away"),
    "problem" to ("Problem" to "OK"),
    "running" to ("Running" to "Not running"),
    "safety" to ("Unsafe" to "Safe"),
    "smoke" to ("Detected" to "Clear"),
    "sound" to ("Detected" to "Clear"),
    "tamper" to ("Detected" to "Clear"),
    "update" to ("Update available" to "Up-to-date"),
    "vibration" to ("Detected" to "Clear"),
    "window" to ("Open" to "Closed"),
)

private val BRIGHTNESS_MODES = setOf(
    "brightness", "color_temp", "hs", "rgb", "rgbw", "rgbww", "white", "xy",
)

data class HaZone(
    val entityId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
)

/** One row on a rendered dashboard screen. */
sealed class DashRow {
    data class Header(val text: String) : DashRow()
    data class Text(val text: String) : DashRow()
    data class Entity(val entityId: String, val nameOverride: String? = null) : DashRow()

    /** A map card: opens a plotted view of the entities it tracks. */
    data class Map(val entityIds: List<String>, val title: String) : DashRow()

    /** A button card with a navigate tap action: jumps to another view. */
    data class Navigate(val path: String, val title: String) : DashRow()
}

data class DashView(
    val title: String,
    val rows: List<DashRow>,
    val skippedCards: Int,
    val path: String? = null,
)

/**
 * Tolerant parser for a Lovelace dashboard config: flattens the cards of each
 * view (including sections and stacks) into simple rows the LP3 can render.
 */
object LovelaceParser {

    fun parse(config: JsonObject): List<DashView> {
        val views = (config["views"] as? JsonArray).orEmpty()
        return views.mapIndexedNotNull { index, viewEl ->
            val view = viewEl as? JsonObject ?: return@mapIndexedNotNull null
            var skipped = 0
            val rows = mutableListOf<DashRow>()

            fun addCard(card: JsonObject) {
                when (card.str("type")?.removePrefix("custom:")) {
                    "entities", "glance" -> {
                        card.str("title")?.let { rows += DashRow.Header(it) }
                        (card["entities"] as? JsonArray).orEmpty().forEach { ref ->
                            when (ref) {
                                is JsonPrimitive -> rows += DashRow.Entity(ref.content)
                                is JsonObject -> ref.str("entity")?.let {
                                    rows += DashRow.Entity(it, ref.str("name"))
                                }
                                else -> skipped++
                            }
                        }
                    }
                    "map" -> {
                        val ids = (card["entities"] as? JsonArray).orEmpty().mapNotNull { ref ->
                            when (ref) {
                                is JsonPrimitive -> ref.content
                                is JsonObject -> ref.str("entity")
                                else -> null
                            }
                        }
                        if (ids.isEmpty()) skipped++ else {
                            rows += DashRow.Map(ids, card.str("title") ?: "Map")
                        }
                    }
                    "camera", "picture-glance" ->
                        (card.str("camera_image") ?: card.str("entity"))
                            ?.let { rows += DashRow.Entity(it, card.str("name")) }
                            ?: run { skipped++ }
                    "entity", "tile", "button", "light", "lock", "thermostat",
                    "picture-entity", "sensor", "gauge", "humidifier" -> {
                        val navigatePath = (card["tap_action"] as? JsonObject)
                            ?.takeIf { it.str("action") == "navigate" }
                            ?.str("navigation_path")
                        when {
                            navigatePath != null -> rows += DashRow.Navigate(
                                path = navigatePath,
                                title = card.str("name") ?: card.str("title") ?: navigatePath,
                            )
                            else -> card.str("entity")
                                ?.let { rows += DashRow.Entity(it, card.str("name")) }
                                ?: run { skipped++ }
                        }
                    }
                    "markdown" -> card.str("content")?.let { rows += DashRow.Text(it) }
                    "heading" -> card.str("heading")?.let { rows += DashRow.Header(it) }
                    "vertical-stack", "horizontal-stack", "grid" ->
                        (card["cards"] as? JsonArray).orEmpty()
                            .forEach { (it as? JsonObject)?.let(::addCard) }
                    else -> skipped++
                }
            }

            (view["cards"] as? JsonArray).orEmpty()
                .forEach { (it as? JsonObject)?.let(::addCard) }
            (view["sections"] as? JsonArray).orEmpty().forEach { sectionEl ->
                val section = sectionEl as? JsonObject ?: return@forEach
                section.str("title")?.let { rows += DashRow.Header(it) }
                (section["cards"] as? JsonArray).orEmpty()
                    .forEach { (it as? JsonObject)?.let(::addCard) }
            }

            DashView(
                title = view.str("title") ?: view.str("path") ?: "View ${index + 1}",
                rows = rows,
                skippedCards = skipped,
                path = view.str("path"),
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
}

/** Maps an entity's domain + current state to the service call a tap should perform. */
object HaActions {

    data class ServiceCall(val domain: String, val service: String)

    fun actionFor(domain: String, state: String?): ServiceCall? = when (domain) {
        "light", "switch", "fan", "input_boolean", "siren", "humidifier" ->
            ServiceCall("homeassistant", "toggle")
        "lock" ->
            if (state == "locked") ServiceCall("lock", "unlock") else ServiceCall("lock", "lock")
        "cover" ->
            if (state == "open" || state == "opening") ServiceCall("cover", "close_cover")
            else ServiceCall("cover", "open_cover")
        "scene" -> ServiceCall("scene", "turn_on")
        "script" -> ServiceCall("script", "turn_on")
        "button", "input_button" -> ServiceCall(domain, "press")
        "automation" -> ServiceCall("automation", "trigger")
        // Anything other than disarmed — including the arming countdown —
        // should disarm, so a tap can cancel an accidental arm.
        "alarm_control_panel" ->
            if (state == "disarmed" || state == null) {
                ServiceCall("alarm_control_panel", "alarm_arm_away")
            } else {
                ServiceCall("alarm_control_panel", "alarm_disarm")
            }
        else -> null
    }

    fun stateLabel(state: HaState?): String {
        state ?: return "…"
        // A binary_sensor's on/off means something different per device class,
        // the same way HA words it in the frontend.
        if (state.domain == "binary_sensor") {
            val on = state.state == "on"
            BINARY_SENSOR_LABELS[state.text("device_class")]?.let { (onLabel, offLabel) ->
                return if (on) onLabel else offLabel
            }
        }
        return when (state.state) {
            "disarmed" -> "Off"
            "armed_home" -> "Home"
            "armed_away" -> "Away"
            "armed_night" -> "Night"
            "armed_vacation" -> "Vacation"
            "arming" -> "Arming…"
            "pending" -> "Pending"
            "triggered" -> "Triggered"
            "on" -> "On"
            "off" -> "Off"
            "locked" -> "Locked"
            "unlocked" -> "Unlocked"
            "locking" -> "Locking…"
            "unlocking" -> "Unlocking…"
            "jammed" -> "Jammed"
            "open" -> "Open"
            "closed" -> "Closed"
            "unavailable" -> "N/A"
            "unknown" -> "?"
            else -> state.unit?.let { "${state.state} $it" } ?: state.state
        }
    }

    /**
     * True when an action opens something up and so deserves a second tap:
     * unlocking a door, disarming an alarm. Closing or arming never asks.
     */
    fun needsConfirmation(domain: String, state: String?): Boolean = when (domain) {
        "lock" -> state == "locked"
        else -> false
    }

    /** Domains that render as a "run" action instead of a state. */
    fun isRunAction(domain: String): Boolean =
        domain in setOf("scene", "script", "button", "input_button", "automation")

    /**
     * True when an entity has more than on/off to offer — a brightness, a
     * position, a temperature — and so deserves its own screen.
     */
    fun hasDetailScreen(state: HaState?): Boolean {
        state ?: return false
        return when (state.domain) {
            "climate", "cover", "fan", "media_player", "input_number", "number",
            "alarm_control_panel" -> true
            "light" -> state.supportsBrightness
            else -> false
        }
    }
}
