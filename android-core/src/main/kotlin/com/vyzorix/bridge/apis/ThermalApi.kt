package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import java.io.File
import org.json.JSONObject

/**
 * Thermal zone temperatures, read from the kernel's sysfs. The zone names are
 * OEM-specific, so they are reported as they are rather than mapped to a
 * vocabulary this code cannot know. A device that exposes none reports an empty
 * list, which is a fact rather than a failure. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object ThermalApi : ApiHandler {

    const val NAME = "thermal"

    /** Where the kernel exposes thermal zones. Not present on every device. */
    private const val ZONE_ROOT = "/sys/class/thermal"

    /** A zone's temperature file. The value is millidegrees Celsius. */
    private const val TEMP_FILE = "temp"

    override fun handle(call: ApiCall): ApiOutcome {
        val root = File(ZONE_ROOT)
        val zones = root.listFiles { file -> file.name.startsWith("thermal_zone") }.orEmpty()

        val array = org.json.JSONArray()
        for (zone in zones.sortedBy { it.name }) {
            val celsius = readMilliDegrees(File(zone, TEMP_FILE))
            if (celsius == null) continue

            array.put(
                JSONObject()
                    .put("zone", zone.name)
                    .put("type", readText(File(zone, "type")).orEmpty())
                    .put("tempC", celsius),
            )
        }

        return ApiOutcome.ok(JSONObject().put("zones", array).put("count", array.length()))
    }

    /** The zone's temperature in degrees, or null when it cannot be read. */
    private fun readMilliDegrees(file: File): Double? {
        val raw = readText(file)?.trim()?.toLongOrNull() ?: return null

        return raw / MILLI_PER_DEGREE
    }

    private fun readText(file: File): String? =
        runCatching { file.takeIf { it.canRead() }?.readText() }.getOrNull()

    private const val MILLI_PER_DEGREE = 1000.0
}
