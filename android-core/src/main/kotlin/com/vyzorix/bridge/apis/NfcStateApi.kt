package com.vyzorix.bridge.apis

import android.content.Context
import android.nfc.NfcManager
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * NFC adapter state. `NFC` is a normal permission granted at install. Read-only:
 * enabling the adapter is not permitted to a normal app on modern Android, so
 * there is no write form here to be refused.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_APP,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object NfcStateApi : ApiHandler {

    const val NAME = "nfc_state"

    override fun handle(call: ApiCall): ApiOutcome {
        val manager = call.context.getSystemService(Context.NFC_SERVICE) as? NfcManager
        val adapter = manager?.defaultAdapter

        return ApiOutcome.ok(
            JSONObject().put("supported", adapter != null).put(
                "enabled",
                adapter?.isEnabled ?: false,
            ),
        )
    }
}
