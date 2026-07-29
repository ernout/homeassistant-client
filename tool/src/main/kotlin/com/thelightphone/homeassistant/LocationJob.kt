package com.thelightphone.homeassistant

import android.util.Log
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlin.time.Duration.Companion.minutes

const val LOCATION_JOB_KEY = "report-location"

/**
 * Reports battery and location to every server that opted in, so Home Assistant
 * keeps updating while the tool is closed. LightOS has no long-running
 * services, so this is a periodic job — the interval is the update latency.
 */
@LightJob(LOCATION_JOB_KEY)
val reportLocationJob: LightJobHandler = { lightContext, _ ->
    val store = ServerStore(lightContext.dataStore)
    val servers = store.servers().filter { it.webhookId != null && (it.sendLocation || it.sendBattery) }

    if (servers.isEmpty()) {
        LightJobResult.Success()
    } else {
        val battery = lightContext.battery.levelPercent()
        val fix = if (servers.any { it.sendLocation }) {
            lightContext.location.current()
        } else {
            null
        }

        var anyFailed = false
        servers.forEach { server ->
            val client = HaClient(server)
            try {
                val level = battery.takeIf { server.sendBattery }
                if (level != null) {
                    client.updateBatterySensor(level)
                        .onFailure { anyFailed = true }
                }
                if (server.sendLocation && fix != null) {
                    client.updateLocation(
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        accuracyMeters = fix.accuracyMeters,
                        battery = level,
                    ).onFailure {
                        anyFailed = true
                        Log.w("HomeTool", "location job: ${it.message}")
                    }
                }
            } finally {
                client.close()
            }
        }

        if (anyFailed) LightJobResult.Retry else LightJobResult.Success()
    }
}

/** WorkManager's minimum periodic interval is 15 minutes. */
fun scheduleLocationReporting(lightContext: SealedLightContext) {
    LightWork.enqueuePeriodic(
        lightContext = lightContext,
        jobKey = LOCATION_JOB_KEY,
        repeatInterval = 15.minutes,
    )
}

fun cancelLocationReporting(lightContext: SealedLightContext) {
    LightWork.cancel(lightContext, LOCATION_JOB_KEY)
}
