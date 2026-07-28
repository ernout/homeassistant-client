package com.thelightphone.homeassistant

import java.io.File

/**
 * Reads the battery percentage from sysfs. The Light sandbox blocks
 * BatteryManager (getSystemService) and BroadcastReceivers, but
 * /sys/class/power_supply is world-readable on Android. Returns null when no
 * readable battery node exists (then we simply skip the sensor update).
 */
object BatteryReader {

    fun levelPercent(): Int? {
        val root = File("/sys/class/power_supply")
        val candidates = listOf(File(root, "battery/capacity")) +
            (root.listFiles().orEmpty().map { File(it, "capacity") })
        return candidates.firstNotNullOfOrNull { file ->
            runCatching { file.readText().trim().toInt() }.getOrNull()
                ?.takeIf { it in 0..100 }
        }
    }
}
