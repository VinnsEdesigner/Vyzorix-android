package com.vyzorix.bridge.apis

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * What sensors the device has, by type and vendor. Listing is not reading:
 * no sensor data is returned here, and no runtime permission is needed to
 * enumerate them. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object SensorListApi : ApiHandler {

    const val NAME = "sensor_list"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no sensor service",
                )

        val sensors = manager.getSensorList(Sensor.TYPE_ALL)
        val array = org.json.JSONArray()
        for (sensor in sensors) {
            array.put(
                JSONObject()
                    .put("name", sensor.name)
                    .put("vendor", sensor.vendor)
                    .put("type", sensor.type)
                    .put("version", sensor.version)
                    .put("maxRange", sensor.maximumRange.toDouble())
                    .put("resolution", sensor.resolution.toDouble())
                    .put("powerMa", sensor.power.toDouble()),
            )
        }

        return ApiOutcome.ok(JSONObject().put("sensors", array).put("count", array.length()))
    }
}
