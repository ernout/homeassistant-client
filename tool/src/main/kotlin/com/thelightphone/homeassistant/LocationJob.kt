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
 *
 * Crossing into one of the instance's zones skips the receiver entirely: the
 * fence already says where the phone is, and Home Assistant takes a zone name
 * in place of coordinates. That is what makes coming home register at all,
 * since indoors is precisely where GNSS does not lock — as long as a fix from
 * the walk up to the door is still around to confirm which zone it was.
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

        // Arriving somewhere answers the question a fix would have answered, so
        // report the zone by name and leave the receiver off. This is the only
        // path that works indoors, where GNSS does not lock and the arrival
        // that matters — coming home — usually ends.
        //
        // Never on trust, though. AOSP evaluates a new proximity alert against
        // whatever position it has, so registering fences while the only fix is
        // a kilometres-wide network estimate claims arrival in every zone at
        // once — and since WorkManager collapses those into one run, whichever
        // fired last would win. So a claim counts only when a position we can
        // actually rely on agrees with it. Arriving home means having been
        // outside just before, which is exactly when a recent fix exists.
        val claimedZone = input["fence"]
            ?.takeIf { input["entering"] == "true" }
            ?.let { store.fencedZones()[it] }
        val enteredZone = claimedZone?.takeIf { zone ->
            val here = lightContext.location.lastKnown()
                ?.takeIf { it.accuracyMeters <= MAX_ACCURACY_METERS }
            if (here == null) {
                Log.d("HomeTool", "location job: '${zone.name}' unconfirmed, no fix to check it")
                return@takeIf false
            }
            val off = metersBetween(here.latitude, here.longitude, zone.latitude, zone.longitude)
            val plausible = off <= zone.radiusMeters + here.accuracyMeters
            if (!plausible) {
                Log.d("HomeTool", "location job: ignored '${zone.name}', ${off.toInt()}m away")
            }
            plausible
        }?.name

        // Null (no accelerometer) counts as moving, so a missing sensor never
        // silently disables reporting.
        val moving = when {
            !wantsLocation -> false
            crossedZone -> true
            else -> lightContext.motion.isMoving() ?: true
        }

        // Still and already reported from here? Then leave the receiver alone.
        val needsFix = wantsLocation && enteredZone == null &&
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
        val sendLocation = enteredZone != null ||
            (
                usableFix != null &&
                    (crossedZone || movedFar || !tracking.reportedWhileStill || tracking.isStale())
                )

        // Fences follow the phone. The zones nearest home are not the ones
        // nearest the office, and re-picking them only when the tool is opened
        // means arriving somewhere new is never the thing that gets noticed.
        // Doing it here means the drive over is what earns the new set.
        if (usableFix != null) {
            val anchor = store.fenceAnchor()
            val movedTowns = anchor == null || metersBetween(
                anchor.first,
                anchor.second,
                usableFix.latitude,
                usableFix.longitude,
            ) >= FENCE_REFRESH_METERS
            if (movedTowns) {
                servers.firstOrNull { it.sendLocation }?.let {
                    refreshZoneGeofences(lightContext, it, usableFix)
                }
            }
        }

        var anyFailed = false
        servers.forEach { server ->
            val client = HaClient(server)
            try {
                if (server.sendBattery && battery != null) {
                    client.updateBatterySensor(battery).onFailure { anyFailed = true }
                }
                if (server.sendLocation && sendLocation) {
                    client.updateLocation(
                        latitude = usableFix?.latitude,
                        longitude = usableFix?.longitude,
                        accuracyMeters = usableFix?.accuracyMeters,
                        battery = battery.takeIf { server.sendBattery },
                        locationName = enteredZone,
                    ).onFailure {
                        anyFailed = true
                        Log.w("HomeTool", "location job: ${it.message}")
                    }
                }
            } finally {
                client.close()
            }
        }

        if (sendLocation) {
            val what = listOfNotNull(
                enteredZone?.let { "'$it'" },
                usableFix?.let { "${it.accuracyMeters}m fix" },
            ).joinToString(" plus ")
            Log.d("HomeTool", "location job: reported $what")
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
        return metersBetween(previousLatitude, previousLongitude, fix.latitude, fix.longitude)
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

/** Equirectangular approximation; plenty over the distances a phone covers. */
private fun metersBetween(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double,
): Double {
    val meanLatitude = Math.toRadians((fromLatitude + toLatitude) / 2)
    val dx = Math.toRadians(toLongitude - fromLongitude) * cos(meanLatitude)
    val dy = Math.toRadians(toLatitude - fromLatitude)
    return sqrt(dx * dx + dy * dy) * EARTH_RADIUS_METERS
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
 * Generous on purpose. Nothing else on a Light Phone asks for a position, so
 * the cache only ever holds what this job last managed to acquire — and once
 * the phone is indoors, that fix simply ages until it is thrown away. Half an
 * hour of staleness is a far better answer than none; the distance filter stops
 * the same position being sent over and over, and a days-old one is still
 * refused.
 */
private const val MAX_FIX_AGE_MILLIS = 30 * 60 * 1000L
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
 * reports immediately instead of waiting out the polling interval. Limited to a
 * few zones — each fence costs the platform some polling — but the home zone is
 * never one of the ones dropped: it is the arrival everything else is compared
 * against, and `/api/states` returns zones in no particular order, so taking the
 * first few can silently leave home unfenced.
 */
suspend fun refreshZoneGeofences(
    lightContext: SealedLightContext,
    server: ServerConfig,
    from: com.thelightphone.sdk.LightLocation.Fix? = null,
) {
    val client = HaClient(server)
    val store = ServerStore(lightContext.dataStore)
    try {
        client.fetchZonesRetrying()
            .onSuccess { zones ->
                val here = from ?: lightContext.location.lastKnown()
                val chosen = zones
                    .sortedWith(
                        compareByDescending<HaZone> { it.entityId == HOME_ZONE }
                            .thenBy { zone ->
                                here?.let {
                                    metersBetween(
                                        it.latitude,
                                        it.longitude,
                                        zone.latitude,
                                        zone.longitude,
                                    )
                                } ?: 0.0
                            },
                    )
                    .take(MAX_FENCES)

                // Drop every zone we are not keeping, not just the ones we
                // remember fencing: proximity alerts cannot be enumerated, so
                // a set registered before this bookkeeping existed would other-
                // wise poll forever with nothing left to recognise it.
                val keeping = chosen.map { it.entityId }.toSet()
                zones.map { it.entityId }
                    .filterNot { it in keeping }
                    .forEach { lightContext.geofence.remove(it, LOCATION_JOB_KEY) }

                chosen.forEach { zone ->
                    lightContext.geofence.add(
                        id = zone.entityId,
                        latitude = zone.latitude,
                        longitude = zone.longitude,
                        radiusMeters = zone.radiusMeters,
                        jobKey = LOCATION_JOB_KEY,
                    )
                }
                store.saveFencedZones(
                    chosen.associate {
                        it.entityId to FencedZone(
                            name = it.reportedName(),
                            latitude = it.latitude,
                            longitude = it.longitude,
                            radiusMeters = it.radiusMeters,
                        )
                    },
                    anchor = here?.let { it.latitude to it.longitude },
                )
                Log.d("HomeTool", "fenced zones: ${chosen.joinToString { it.entityId }}")
            }
            .onFailure { Log.w("HomeTool", "could not fetch zones: ${it.message}") }
    } finally {
        client.close()
    }
}

/**
 * The instance's zones, with a couple of retries.
 *
 * The first request after the tool starts tends to die in the TLS handshake —
 * the network is evidently not ready by the time the home screen appears —
 * while everything after it succeeds. Refreshing fences is the one call with no
 * second chance later in the session, and failing it silently leaves the phone
 * fenced for wherever it used to be.
 */
private suspend fun HaClient.fetchZonesRetrying(): Result<List<HaZone>> {
    var last: Result<List<HaZone>> = Result.failure(IllegalStateException("Not attempted."))
    repeat(ZONE_FETCH_ATTEMPTS) { attempt ->
        last = fetchZones()
        if (last.isSuccess) return last
        kotlinx.coroutines.delay(ZONE_FETCH_BACKOFF_MILLIS * (attempt + 1))
    }
    return last
}

private const val ZONE_FETCH_ATTEMPTS = 3
private const val ZONE_FETCH_BACKOFF_MILLIS = 1_500L

/**
 * What Home Assistant should show as the tracker's state on arrival. Its own
 * zone matcher answers with the literal string `home` for the home zone and
 * with the friendly name for every other one, so match that exactly —
 * automations and `person` entities compare against `home`, not "Home".
 */
private fun HaZone.reportedName(): String =
    if (entityId == HOME_ZONE) "home" else name

private const val HOME_ZONE = "zone.home"

/**
 * Not a platform ceiling — AOSP hangs every alert off one provider request, so
 * the marginal cost of another zone is close to nothing, and what actually
 * drives polling is being near a boundary. Bounded anyway, because the set is
 * re-registered as the phone travels and there is no point carrying zones from
 * the other side of the country.
 */
private const val MAX_FENCES = 20

/** How far the phone has to travel before the fence set is worth re-picking. */
private const val FENCE_REFRESH_METERS = 2_000.0

fun cancelLocationReporting(lightContext: SealedLightContext) {
    LightWork.cancel(lightContext, LOCATION_JOB_KEY)
}
