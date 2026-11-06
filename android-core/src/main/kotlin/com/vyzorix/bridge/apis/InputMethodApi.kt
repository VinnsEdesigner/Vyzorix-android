package com.vyzorix.bridge.apis

import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The enabled input methods. A device whose IME is in a bad state cannot be typed
 * into, which looks like a broken terminal. Read-only. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object InputMethodApi : ApiHandler {

    const val NAME = "input_method"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no input method service",
                )

        val enabled = manager.enabledInputMethodList
        val array = JSONArray()
        for (method in enabled) {
            array.put(
                JSONObject()
                    .put("id", method.id)
                    .put("label", method.loadLabel(call.context.packageManager).toString())
                    .put("packageName", method.packageName),
            )
        }

        return ApiOutcome.ok(JSONObject().put("enabled", array).put("count", array.length()))
    }
}
