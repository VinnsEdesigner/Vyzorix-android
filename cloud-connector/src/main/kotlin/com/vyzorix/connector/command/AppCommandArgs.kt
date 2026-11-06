package com.vyzorix.connector.command

import com.vyzorix.connector.cloud.CloudJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Reads the arguments the `APP_*` commands carry.
 *
 * The server sends a command's args as raw JSON (the exact substring it signed, which
 * the dispatcher passes through untouched). Two field names are accepted for the app
 * name — `app` and `name` — because the risk catalog and the dashboard have each used
 * one at different times, and guessing one would break the other silently.
 *
 * A parse failure is thrown, not defaulted: an empty name would become "start the app
 * called ''", which the supervisor would refuse with a confusing message.
 */
object AppCommandArgs {
    fun name(args: String): String {
        if (args.isBlank()) return ""

        val obj = CloudJson.json.parseToJsonElement(args).jsonObject

        return (obj["app"] as? JsonPrimitive)?.content
            ?: (obj["name"] as? JsonPrimitive)?.content
            ?: ""
    }
}
