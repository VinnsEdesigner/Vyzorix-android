package com.vyzorix.bridge.apis

import android.content.ClipboardManager
import android.content.Context
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * Whether the clipboard has content. From Android 10 a background app cannot read
 * the clipboard's *contents* — only an app with focus can — so this deliberately
 * reports the presence of a clip rather than its text, which is what is actually
 * observable and is also the less sensitive fact. No runtime permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object ClipboardStateApi : ApiHandler {

    const val NAME = "clipboard_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no clipboard service",
                )

        // Reading primaryClip from a background process returns null on Android 10+, so
        // a null here means "not observable", not "empty". The two are reported
        // separately rather than conflated.
        val clip = manager.primaryClip
        val observable = clip != null

        return ApiOutcome.ok(
            JSONObject()
                .put("hasPrimaryClip", manager.hasPrimaryClip())
                .put("observable", observable)
                .put("itemCount", clip?.itemCount ?: 0),
        )
    }
}
