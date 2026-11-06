package com.vyzorix.bridge.apis

import android.content.Context
import android.os.BatteryManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * The battery's electrical state: current, voltage and charge counter, which
 * `battery` does not report. An operator diagnosing a device that will not stay up
 * needs these. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object BatteryHistoryApi : ApiHandler {

    const val NAME = "battery_history"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no battery service",
                )

        // A property the hardware does not have reports Integer.MIN_VALUE rather than
        // throwing, so an absent counter is reported as unknown instead of as a number.
        return ApiOutcome.ok(
            JSONObject()
                .put(
                    "currentNowMicroA",
                    known(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                ).put(
                    "currentAvgMicroA",
                    known(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
                ).put(
                    "chargeCounterUah",
                    known(manager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                ).put("capacityPercent", known(manager, BatteryManager.BATTERY_PROPERTY_CAPACITY)),
        )
    }

    /** The property's value, or null when the device does not report it. */
    private fun known(
        manager: BatteryManager,
        property: Int,
    ): Any? {
        val value = manager.getIntProperty(property)

        return if (value == Int.MIN_VALUE) null else value
    }
}
