package com.thelightphone.sdk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.util.Log

/**
 * Circular geofences via AOSP's proximity alerts.
 *
 * NOTE: like [LightBattery] and [LightLocation], a preview of a primitive the
 * SDK does not offer yet. This is the one zone mechanism that does not need
 * Google Play Services, and the platform makes it cheap: it polls slowly when
 * the phone is far from a boundary and speeds up as it approaches, so an
 * arrival is noticed without any polling of our own.
 *
 * A crossing wakes the tool by enqueuing the [LightJob] whose key was given at
 * registration, with `fence` and `entering` in its input data.
 */
class LightGeofence internal constructor(private val androidContext: Context) {

    fun add(
        id: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        jobKey: String,
    ): Boolean {
        val manager = androidContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return runCatching {
            @Suppress("MissingPermission")
            manager.addProximityAlert(
                latitude,
                longitude,
                radiusMeters,
                NEVER_EXPIRE,
                pendingIntent(id, jobKey),
            )
            true
        }.getOrElse {
            Log.w("LightGeofence", "addProximityAlert failed: ${it.message}")
            false
        }
    }

    fun remove(id: String, jobKey: String) {
        val manager = androidContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return
        runCatching { manager.removeProximityAlert(pendingIntent(id, jobKey)) }
    }

    private fun pendingIntent(id: String, jobKey: String): PendingIntent {
        val intent = Intent(androidContext, LightGeofenceReceiver::class.java)
            .setAction(LightGeofenceReceiver.ACTION)
            .putExtra(LightGeofenceReceiver.EXTRA_FENCE_ID, id)
            .putExtra(LightGeofenceReceiver.EXTRA_JOB_KEY, jobKey)
            // Distinct data per fence, so PendingIntents don't collide.
            .setData(android.net.Uri.parse("lightgeofence://$id"))
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system writes KEY_PROXIMITY_ENTERING into the intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(androidContext, id.hashCode(), intent, flags)
    }

    private companion object {
        const val NEVER_EXPIRE = -1L
    }
}

/** Turns a proximity alert into a [LightJob] run. Declared in the SDK manifest. */
class LightGeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val jobKey = intent.getStringExtra(EXTRA_JOB_KEY) ?: return
        val fenceId = intent.getStringExtra(EXTRA_FENCE_ID).orEmpty()
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        Log.d("LightGeofence", "fence '$fenceId' ${if (entering) "entered" else "exited"}")

        LightWork.enqueue(
            lightContext = SealedLightContext(context.applicationContext),
            jobKey = jobKey,
            inputData = mapOf(
                "fence" to fenceId,
                "entering" to entering.toString(),
            ),
        )
    }

    companion object {
        const val ACTION = "com.thelightphone.sdk.PROXIMITY_ALERT"
        const val EXTRA_FENCE_ID = "fence_id"
        const val EXTRA_JOB_KEY = "job_key"
    }
}
