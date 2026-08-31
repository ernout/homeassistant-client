package com.thelightphone.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Battery level access for tools.
 *
 * NOTE: this is a preview of a primitive the SDK does not offer yet. Tool code
 * cannot reach BatteryManager itself (getSystemService is blocked by the build
 * plugin), so it lives here in the client library, in the shape we would expect
 * an official `LightBattery` API to take. Replace with the official primitive
 * once Light ships one.
 */
class LightBattery internal constructor(private val androidContext: Context) {

    /** Current charge as a percentage (0-100), or null if unavailable. */
    fun levelPercent(): Int? {
        val manager = androidContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it in 0..100 }
    }

    /** True while the phone is plugged in / charging. */
    fun isCharging(): Boolean? {
        val manager = androidContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        return manager.isCharging
    }
}

/**
 * Cheap "is the phone moving?" check via the accelerometer.
 *
 * Sampling motion for a couple of seconds costs a fraction of what a GPS fix
 * costs, so a job can use this to decide whether acquiring a position is worth
 * it at all. Needs no permission.
 */
class LightMotion internal constructor(private val androidContext: Context) {

    /**
     * Samples the accelerometer for [durationMillis] and reports whether the
     * readings vary more than [thresholdMs2] — a phone on a desk sits near
     * zero, a phone being carried does not. Returns null if there is no
     * accelerometer or no samples arrived.
     */
    suspend fun isMoving(
        durationMillis: Long = 2_500,
        thresholdMs2: Double = 0.35,
    ): Boolean? {
        val manager = androidContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null

        val magnitudes = mutableListOf<Double>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                magnitudes += kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        return try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            delay(durationMillis)
            if (magnitudes.size < 3) return null
            // Standard deviation of total acceleration: gravity cancels out, so
            // only actual movement shows up.
            val mean = magnitudes.average()
            val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size
            kotlin.math.sqrt(variance) > thresholdMs2
        } catch (error: Exception) {
            Log.w("LightMotion", "accelerometer sampling failed: ${error.message}")
            null
        } finally {
            runCatching { manager.unregisterListener(listener) }
        }
    }
}

/**
 * Location access for tools.
 *
 * Same caveat as [LightBattery]: a preview of a primitive the SDK does not
 * offer yet. ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION are already on the
 * permission allowlist, but there is no API to actually read a position.
 *
 * Note that a foreground-only grant buys nothing here: Android rejects the
 * app op outright once the tool leaves the screen, so a periodic job needs
 * ACCESS_BACKGROUND_LOCATION as well. [hasBackgroundPermission] says whether
 * that is the case, so callers can explain the difference instead of silently
 * reporting nothing.
 */
class LightLocation internal constructor(private val androidContext: Context) {

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Int,
        val ageMillis: Long,
    )

    fun hasPermission(): Boolean =
        androidContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            androidContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** True when location also works with no screen up — i.e. from a job. */
    fun hasBackgroundPermission(): Boolean =
        hasPermission() &&
            androidContext.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best last-known position across providers, or null.
     *
     * "Best" is the most accurate of the recent ones, not simply the newest.
     * The network provider answers constantly and coarsely, so picking by
     * timestamp alone hands back an estimate kilometres wide while a GPS fix
     * good to ten metres, minutes older, sits right next to it.
     */
    fun lastKnown(): Fix? {
        if (!hasPermission()) return null
        val manager = androidContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return runCatching {
            val known = manager.allProviders
                .mapNotNull { provider ->
                    @Suppress("MissingPermission")
                    manager.getLastKnownLocation(provider)
                }
                .map { it.toFix() }
            known
                .filter { it.ageMillis <= RECENT_ENOUGH_MILLIS }
                .minByOrNull { it.accuracyMeters }
                ?: known.maxByOrNull { -it.ageMillis }
        }.getOrNull()
    }

    /**
     * Asks every enabled provider for a fresh fix at once and returns the most
     * accurate answer, falling back to [lastKnown] when none of them delivers.
     *
     * Asking one provider at a time does not work in practice: GNSS on a Light
     * Phone III reports a mean time-to-first-fix around 50 seconds, so a short
     * wait on the GPS provider alone times out on nearly every run while the
     * network and fused providers — which would have answered in a second —
     * are never consulted. Hence the fan-out, an early exit as soon as a fix is
     * accurate enough to be worth having, and a [timeoutMillis] generous enough
     * to outlast a cold start.
     */
    suspend fun current(
        timeoutMillis: Long = 90_000,
        goodEnoughMeters: Int = 100,
    ): Fix? {
        if (!hasPermission()) return null
        val manager = androidContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = PROVIDERS.filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return lastKnown()

        var best: Fix? = null
        coroutineScope {
            // Unlimited, so a worker finishing after the timeout never blocks
            // on a send nobody is going to receive.
            val fixes = Channel<Fix?>(Channel.UNLIMITED)
            val workers = providers.map { provider ->
                launch { fixes.send(awaitFix(manager, provider, timeoutMillis)) }
            }
            withTimeoutOrNull(timeoutMillis) {
                repeat(providers.size) {
                    val fix = fixes.receive() ?: return@repeat
                    if (best == null || fix.accuracyMeters < best!!.accuracyMeters) best = fix
                    if (fix.accuracyMeters <= goodEnoughMeters) return@withTimeoutOrNull
                }
            }
            workers.forEach { it.cancel() }
        }
        return best ?: lastKnown()
    }

    /**
     * One fix from one provider, or null once [durationMillis] has passed.
     *
     * Deliberately not `getCurrentLocation`, which would read better: AOSP
     * caps that call at 30 seconds no matter what the request asks for, and
     * this phone's mean time-to-first-fix is around 50, so GNSS is switched
     * off again before it has ever locked on. Subscribing to updates and
     * unsubscribing on the first one honours the duration we ask for.
     */
    private suspend fun awaitFix(
        manager: LocationManager,
        provider: String,
        durationMillis: Long,
    ): Fix? =
        suspendCancellableCoroutine { continuation ->
            val startedAt = SystemClock.elapsedRealtime()
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    val fix = location.toFix()
                    Log.d(
                        "LightLocation",
                        "$provider: ${fix.accuracyMeters}m after " +
                            "${SystemClock.elapsedRealtime() - startedAt}ms",
                    )
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(fix)
                }

                override fun onProviderDisabled(provider: String) {
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            val request = LocationRequest.Builder(0)
                .setDurationMillis(durationMillis)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMaxUpdates(1)
                .build()
            val requested = runCatching {
                @Suppress("MissingPermission")
                manager.requestLocationUpdates(
                    provider,
                    request,
                    androidContext.mainExecutor,
                    listener,
                )
                true
            }.getOrElse {
                Log.w("LightLocation", "requestLocationUpdates($provider) failed: ${it.message}")
                false
            }

            if (!requested) {
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
        }

    private fun Location.toFix() = Fix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.toInt(),
        ageMillis = System.currentTimeMillis() - time,
    )

    private companion object {
        /**
         * Every provider worth asking; they are all asked at once. `fused` and
         * `network` usually answer within a second but coarsely, `gps` is the
         * slow and precise one. `passive` is deliberately absent: it only ever
         * repeats what another app asked for, and on a Light Phone nothing else
         * asks.
         */
        /** How far back a fix still counts when picking the best known one. */
        const val RECENT_ENOUGH_MILLIS = 60 * 60 * 1000L

        val PROVIDERS = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
    }
}
