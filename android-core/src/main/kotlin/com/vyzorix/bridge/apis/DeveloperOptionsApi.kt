package com.vyzorix.bridge.apis

import android.content.ContentResolver
import android.provider.Settings
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether developer options and USB debugging are enabled. An operator diagnosing
 * a sideloaded device wants this; it is also a signal that the device is not a
 * locked-down fleet unit. Read-only. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object DeveloperOptionsApi : ApiHandler {

    const val NAME = "developer_options"

    override fun handle(call: ApiCall): ApiOutcome {
        val resolver = call.context.contentResolver

        return ApiOutcome.ok(
            JSONObject()
                .put("developerOptions", isOn(resolver, DEV_SETTINGS))
                .put("adbEnabled", isOn(resolver, ADB_ENABLED)),
        )
    }

    private fun isOn(
        resolver: ContentResolver,
        key: String,
    ): Boolean = Settings.Global.getInt(resolver, key, 0) != 0

    private const val DEV_SETTINGS = "development_settings_enabled"
    private const val ADB_ENABLED = "adb_enabled"
}
