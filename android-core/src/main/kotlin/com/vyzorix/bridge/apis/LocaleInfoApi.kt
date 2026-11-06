package com.vyzorix.bridge.apis

import android.os.LocaleList
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import java.util.Locale
import org.json.JSONObject

/**
 * Locale and language configuration. Used to decide what language to render
 * an app's UI in, which is the first thing a proxied app gets wrong. No runtime
 * permission.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PUBLIC,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_NORMAL,
        ApiTags.VISIBILITY_PUBLIC,
    ],
)
object LocaleInfoApi : ApiHandler {

    const val NAME = "locale_info"

    override fun handle(call: ApiCall): ApiOutcome {
        val locale = Locale.getDefault()

        return ApiOutcome.ok(
            JSONObject()
                .put("language", locale.language)
                .put("country", locale.country)
                .put("tag", locale.toLanguageTag())
                .put("displayName", locale.displayName)
                .put(
                    "locales",
                    LocaleList
                        .getDefault()
                        .toLanguageTags(),
                ),
        )
    }
}
