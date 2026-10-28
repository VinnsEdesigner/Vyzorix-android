package com.vyzorix.bridge.apis

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.BridgeErrorKind
import org.json.JSONObject

/**
 * Battery state. No runtime permission.
 */
object BatteryApi : ApiHandler {

    const val NAME = "battery"

    /** EXTRA_TEMPERATURE is in tenths of a degree Celsius. */
    private const val TENTHS_PER_DEGREE = 10.0

    override fun handle(call: ApiCall): ApiOutcome {
        val context = call.context
        val manager =
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return ApiOutcome.fail(BridgeErrorKind.INTERNAL, "no battery service")

        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val statusCode = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging =
            statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusCode == BatteryManager.BATTERY_STATUS_FULL

        // EXTRA_TEMPERATURE is tenths of a degree Celsius.
        val tenths = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val temperatureC = tenths / TENTHS_PER_DEGREE

        return ApiOutcome.ok(
            JSONObject()
                .put("level", level)
                .put("charging", charging)
                .put("temperatureC", temperatureC),
        )
    }
}
