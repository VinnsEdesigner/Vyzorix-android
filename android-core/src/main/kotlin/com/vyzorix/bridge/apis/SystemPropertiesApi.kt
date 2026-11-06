package com.vyzorix.bridge.apis

import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * The JVM's own system properties. `android.os.SystemProperties` is hidden and
 * reading the build's properties that way is not available to an app, so this
 * reports what `System.getProperties()` actually exposes and says so. No runtime
 * permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object SystemPropertiesApi : ApiHandler {

    const val NAME = "system_properties"

    override fun handle(call: ApiCall): ApiOutcome {
        val wanted =
            listOf(
                "java.version",
                "java.vendor",
                "java.vm.name",
                "os.arch",
                "os.name",
                "os.version",
                "file.separator",
                "path.separator",
                "line.separator",
                "user.dir",
                "user.home",
                "user.language",
                "user.country",
            )

        val properties = JSONObject()
        for (key in wanted) {
            properties.put(key, System.getProperty(key).orEmpty())
        }

        return ApiOutcome.ok(JSONObject().put("properties", properties).put("count", wanted.size))
    }
}
