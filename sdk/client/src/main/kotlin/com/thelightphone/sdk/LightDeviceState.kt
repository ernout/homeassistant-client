package com.thelightphone.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
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

    /** Best last-known position across providers, or null. */
    fun lastKnown(): Fix? {
        if (!hasPermission()) return null
        val manager = androidContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return runCatching {
            manager.allProviders
                .mapNotNull { provider ->
                    @Suppress("MissingPermission")
                    manager.getLastKnownLocation(provider)
                }
                .maxByOrNull { it.time }
                ?.toFix()
        }.getOrNull()
    }

    /**
     * Requests a single fresh fix, falling back to [lastKnown] when no provider
     * answers within [timeoutMillis]. Safe to call from a coroutine on any
     * dispatcher; the callback is delivered on the main looper.
     */
    suspend fun current(timeoutMillis: Long = 15_000): Fix? {
        if (!hasPermission()) return null
        val manager = androidContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return lastKnown()

        // A cold GPS start indoors may never produce a fix, so always bound the
        // wait and fall back to the last known position.
        return withTimeoutOrNull(timeoutMillis) { awaitFix(manager, provider) } ?: lastKnown()
    }

    private suspend fun awaitFix(manager: LocationManager, provider: String): Fix? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(location.toFix())
                }

                @Deprecated("Required by the pre-API-30 interface")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderDisabled(provider: String) {
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(null)
                }
            }

            val requested = runCatching {
                @Suppress("MissingPermission")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                true
            }.getOrElse {
                Log.w("LightLocation", "requestSingleUpdate failed: ${it.message}")
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
}
