package com.thelightphone.homeassistant

import android.util.Log
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

const val LOCATION_JOB_KEY = "report-location"

/**
 * Reports battery and location to every server that opted in, so Home Assistant
 * keeps updating while the tool is closed.
 *
 * LightOS has no long-running services and Android throttles background
 * location hard, so this is a one-shot job that reschedules itself. The delay
 * adapts: short while the phone is actually moving, backing off to an hour once
 * it has been sitting still, and slower again on a low battery. The
 * accelerometer decides whether a GPS fix is worth acquiring at all — a phone
 * on a desk never turns the receiver on, except once, to report where it was
 * parked.
 */
@LightJob(LOCATION_JOB_KEY)
val reportLocationJob: LightJobHandler = { lightContext, input ->
    val store = ServerStore(lightContext.dataStore)
    val servers = store.servers()
        .filter { it.webhookId != null && (it.sendLocation || it.sendBattery) }

    if (servers.isEmpty()) {
        LightJobResult.Success()
    } else {
        val tracking = ReportingState.load(store)
        val battery = lightContext.battery.levelPercent()
        val charging = lightContext.battery.isCharging() ?: false
        val wantsLocation = servers.any { it.sendLocation }

        // A zone crossing is exactly the moment automations care about, so it
        // overrides both the motion check and the distance filter.
        val crossedZone = input["fence"] != null

        // Null (no accelerometer) counts as moving, so a missing sensor never
        // silently disables reporting.
        val moving = when {
            !wantsLocation -> false
            crossedZone -> true
            else -> lightContext.motion.isMoving() ?: true
        }

        // Still and already reported from here? Then leave the receiver alone.
        val needsFix = wantsLocation &&
            (moving || !tracking.reportedWhileStill || tracking.isStale())

        if (wantsLocation && !lightContext.location.hasBackgroundPermission()) {
            // Worth saying out loud: without it Android rejects the location
            // app op on every run that happens off-screen, so HA silently keeps
            // whatever position the tool last reported while it was open.
            Log.w("HomeTool", "location job: no background location permission")
        }

        val fix = if (needsFix) lightContext.location.current() else null
        val usableFix = fix?.takeIf {
            it.accuracyMeters <= MAX_ACCURACY_METERS && it.ageMillis <= MAX_FIX_AGE_MILLIS
        }
        if (fix != null && usableFix == null) {
            Log.d(
                "HomeTool",
                "location job: discarded fix, ${fix.accuracyMeters}m accurate, " +
                    "${fix.ageMillis / 1000}s old",
            )
        }

        val movedFar = usableFix != null && tracking.movedFrom(usableFix) >= MIN_DISTANCE_METERS
        val sendLocation = usableFix != null &&
            (crossedZone || movedFar || !tracking.reportedWhileStill || tracking.isStale())

        var anyFailed = false
        servers.forEach { server ->
            val client = HaClient(server)
            try {
                if (server.sendBattery && battery != null) {
                    client.updateBatterySensor(battery).onFailure { anyFailed = true }
                }
                if (server.sendLocation && sendLocation && usableFix != null) {
                    client.updateLocation(
                        latitude = usableFix.latitude,
                        longitude = usableFix.longitude,
                        accuracyMeters = usableFix.accuracyMeters,
                        battery = battery.takeIf { server.sendBattery },
                    ).onFailure {
                        anyFailed = true
                        Log.w("HomeTool", "location job: ${it.message}")
                    }
                }
            } finally {
                client.close()
            }
        }

        val next = nextDelay(
            moving = moving,
            previous = tracking.intervalMinutes,
            batteryPercent = battery,
            charging = charging,
        )
        ReportingState(
            latitude = usableFix?.latitude ?: tracking.latitude,
            longitude = usableFix?.longitude ?: tracking.longitude,
            intervalMinutes = next.inWholeMinutes.toInt(),
            reportedWhileStill = if (moving) false else (sendLocation || tracking.reportedWhileStill),
            lastSentAtMillis = if (sendLocation) System.currentTimeMillis() else tracking.lastSentAtMillis,
        ).save(store)

        LightWork.enqueue(
            lightContext = lightContext,
            jobKey = LOCATION_JOB_KEY,
            initialDelay = next,
        )

        if (anyFailed) LightJobResult.Retry else LightJobResult.Success()
    }
}

/** What the previous run learned, so the next one can adapt. */
private data class ReportingState(
    val latitude: Double?,
    val longitude: Double?,
    val intervalMinutes: Int,
    val reportedWhileStill: Boolean,
    val lastSentAtMillis: Long,
) {
    fun movedFrom(fix: com.thelightphone.sdk.LightLocation.Fix): Double {
        val previousLatitude = latitude ?: return Double.MAX_VALUE
        val previousLongitude = longitude ?: return Double.MAX_VALUE
        val meanLatitude = Math.toRadians((previousLatitude + fix.latitude) / 2)
        val dx = Math.toRadians(fix.longitude - previousLongitude) * cos(meanLatitude)
        val dy = Math.toRadians(fix.latitude - previousLatitude)
        return sqrt(dx * dx + dy * dy) * EARTH_RADIUS_METERS
    }

    /** Force a heartbeat now and then so the entity never looks abandoned. */
    fun isStale(): Boolean =
        System.currentTimeMillis() - lastSentAtMillis >= HEARTBEAT_MILLIS

    suspend fun save(store: ServerStore) = store.saveReportingState(
        latitude = latitude,
        longitude = longitude,
        intervalMinutes = intervalMinutes,
        reportedWhileStill = reportedWhileStill,
        lastSentAtMillis = lastSentAtMillis,
    )

    companion object {
        suspend fun load(store: ServerStore): ReportingState {
            val raw = store.reportingState()
            return ReportingState(
                latitude = raw["lat"]?.toDoubleOrNull(),
                longitude = raw["lon"]?.toDoubleOrNull(),
                intervalMinutes = raw["interval"]?.toIntOrNull() ?: MOVING_MINUTES,
                reportedWhileStill = raw["stillReported"] == "true",
                lastSentAtMillis = raw["lastSent"]?.toLongOrNull() ?: 0L,
            )
        }
    }
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val MIN_DISTANCE_METERS = 100.0

/**
 * Home Assistant hands `gps_accuracy` straight to its zone matcher, which
 * counts a device as inside a zone when the fix could plausibly be there. A
 * wildly imprecise fix would therefore park the phone in whichever zone is
 * nearest — usually home — so cap what we are willing to send. 500m still lets
 * a network-only fix through; it is worth more than no position at all.
 */
private const val MAX_ACCURACY_METERS = 500

/**
 * Long enough to accept a fix acquired earlier in the same wake-up, short
 * enough that a days-old cached position never gets reported as current.
 */
private const val MAX_FIX_AGE_MILLIS = 10 * 60 * 1000L
private const val HEARTBEAT_MILLIS = 60 * 60 * 1000L
private const val MOVING_MINUTES = 5
private const val MAX_MINUTES = 60
private const val LOW_BATTERY_PERCENT = 20
private const val LOW_BATTERY_MINUTES = 30

/**
 * Fast while moving, backing off geometrically once still. A low battery slows
 * everything down; a charger removes the brake entirely.
 */
private fun nextDelay(
    moving: Boolean,
    previous: Int,
    batteryPercent: Int?,
    charging: Boolean,
): Duration {
    val base = if (moving) {
        MOVING_MINUTES
    } else {
        (previous * 3 / 2).coerceIn(MOVING_MINUTES, MAX_MINUTES)
    }
    val low = !charging && batteryPercent != null && batteryPercent <= LOW_BATTERY_PERCENT
    val minutes = if (low) maxOf(base, LOW_BATTERY_MINUTES) else base
    return minutes.minutes
}

/** Starts the self-rescheduling chain; safe to call on every app open. */
fun scheduleLocationReporting(lightContext: SealedLightContext) {
    LightWork.enqueue(lightContext, LOCATION_JOB_KEY)
}

/**
 * Registers proximity alerts for the instance's zones, so arriving somewhere
 * reports immediately instead of waiting out the polling interval. Limited to
 * the nearest few zones — each fence costs the platform some polling.
 */
suspend fun refreshZoneGeofences(lightContext: SealedLightContext, server: ServerConfig) {
    val client = HaClient(server)
    try {
        client.fetchZones()
            .onSuccess { zones ->
                zones.take(MAX_FENCES).forEach { zone ->
                    lightContext.geofence.add(
                        id = zone.entityId,
                        latitude = zone.latitude,
                        longitude = zone.longitude,
                        radiusMeters = zone.radiusMeters,
                        jobKey = LOCATION_JOB_KEY,
                    )
                }
                Log.d("HomeTool", "registered ${zones.take(MAX_FENCES).size} zone geofences")
            }
            .onFailure { Log.w("HomeTool", "could not fetch zones: ${it.message}") }
    } finally {
        client.close()
    }
}

private const val MAX_FENCES = 5

fun cancelLocationReporting(lightContext: SealedLightContext) {
    LightWork.cancel(lightContext, LOCATION_JOB_KEY)
}
