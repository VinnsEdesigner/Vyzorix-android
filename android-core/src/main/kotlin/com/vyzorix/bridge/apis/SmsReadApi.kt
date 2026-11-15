package com.vyzorix.bridge.apis

import android.content.ContentResolver
import android.provider.Telephony
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * The device's SMS inbox, read from the telephony provider.
 *
 * ## Why this exists beside `sms_received`
 *
 * `sms_received` reports a *count* of messages that arrive while the service is
 * running, and it deliberately never returns bodies. That is the right shape for
 * "is this device receiving pages", but it cannot answer "what texts are on this
 * device" — the question a user actually asks. This API answers that: it reads
 * the stored inbox through the telephony content provider, so a message that
 * arrived before the agent started, or while it was stopped, is still there.
 *
 * ## Why the whole inbox is not returned
 *
 * A text is the private content of whoever sent it, and an inbox can hold thousands.
 * The result is therefore bounded by [MAX_LIMIT] (defaulting to [DEFAULT_LIMIT]),
 * newest first, and each row carries only what a user needs to triage —
 * sender, timestamp, read state, body. `count` reports how many rows this call
 * returned and `totalAvailable` how many the provider holds, so a caller can tell a
 * bounded page from an empty inbox. A `limit` argument raises or lowers the page; it
 * can never lift the cap.
 *
 * ## Permissions
 *
 * Reading the provider needs `READ_SMS`, which is `dangerous` and separate from the
 * `SEND_SMS`/`RECEIVE_SMS` pair — an app that can send and receive cannot
 * necessarily read what was stored. The router gates this API on the grant, so a
 * device without it reports `readable: false` rather than an empty inbox: "no
 * messages" and "cannot look" are different answers and must not be conflated.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object SmsReadApi : ApiHandler {

    const val NAME = "sms_read"

    /** Rows returned when the caller does not ask for a different page. */
    private const val DEFAULT_LIMIT = 50

    /**
     * The hard cap on rows returned.
     *
     * A caller may lower the page but never raise it past this: an unbounded inbox
     * read would blow past the result frame's size bound, and the result is meant to
     * be read by a person, not slurped whole.
     */
    private const val MAX_LIMIT = 200

    @Suppress("SwallowedException")
    override fun handle(call: ApiCall): ApiOutcome {
        val limit = limitOf(call.args["limit"])
        val includeBody = call.args["body"]?.toBooleanStrictOrNull() ?: true

        val rows =
            try {
                queryInbox(call, limit, includeBody)
            } catch (e: SecurityException) {
                // READ_SMS is not granted. Not an error the caller can fix by
                // retrying: report the state so a caller can tell it apart from an
                // empty inbox.
                return ApiOutcome.ok(
                    JSONObject()
                        .put("readable", false)
                        .put("reason", "READ_SMS not granted")
                        .put("messages", JSONArray())
                        .put("count", 0)
                        .put("totalAvailable", 0),
                )
            } catch (e: Exception) {
                // A provider that is absent (a device with no telephony) or a malformed
                // cursor. Report the reason rather than an empty list.
                return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "could not read the SMS inbox: ${e.message}",
                )
            }

        return ApiOutcome.ok(
            JSONObject()
                .put("readable", true)
                .put("messages", rows.messages)
                .put("count", rows.messages.length())
                .put("totalAvailable", rows.totalAvailable)
                .put("limit", limit),
        )
    }

    /** One page of the inbox and the provider's total row count. */
    private class Inbox(
        val messages: JSONArray,
        val totalAvailable: Int,
    )

    /**
     * Reads up to [limit] newest inbox rows.
     *
     * The projection is explicit rather than `null`: a `null` projection returns
     * every column the provider holds, which is both wider than this API promises
     * and slower. Ordering is newest-first so a bounded page is the recent messages,
     * which is what a user wants.
     */
    private fun queryInbox(
        call: ApiCall,
        limit: Int,
        includeBody: Boolean,
    ): Inbox {
        val resolver: ContentResolver = call.context.contentResolver

        val columns =
            buildList {
                add(Telephony.Sms._ID)
                add(Telephony.Sms.ADDRESS)
                add(Telephony.Sms.DATE)
                add(Telephony.Sms.READ)
                add(Telephony.Sms.TYPE)
                if (includeBody) add(Telephony.Sms.BODY)
            }.toTypedArray()

        val messages = JSONArray()

        // A null cursor (no provider, e.g. a device with no telephony) is an empty
        // inbox, not a failure.
        resolver
            .query(
                Telephony.Sms.Inbox.CONTENT_URI,
                columns,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                val addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                val readIdx = cursor.getColumnIndex(Telephony.Sms.READ)
                val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)
                val bodyIdx = if (includeBody) cursor.getColumnIndex(Telephony.Sms.BODY) else -1

                // The provider's row count for this query is the honest "how many are
                // there" without a second scan; captured before the bounded walk.
                val total = cursor.count

                while (cursor.moveToNext() && messages.length() < limit) {
                    messages.put(
                        rowOf(cursor, idIdx, addressIdx, dateIdx, readIdx, typeIdx, bodyIdx),
                    )
                }

                return Inbox(messages, total)
            }

        return Inbox(messages, 0)
    }

    /** One inbox row as JSON, guarding each column index. */
    private fun rowOf(
        cursor: android.database.Cursor,
        idIdx: Int,
        addressIdx: Int,
        dateIdx: Int,
        readIdx: Int,
        typeIdx: Int,
        bodyIdx: Int,
    ): JSONObject {
        val row =
            JSONObject()
                .put("id", if (idIdx >= 0) cursor.getLong(idIdx) else 0L)
                .put("address", if (addressIdx >= 0) cursor.getString(addressIdx).orEmpty() else "")
                .put("dateMillis", if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L)
                .put("read", if (readIdx >= 0) cursor.getInt(readIdx) == 1 else false)
                .put("type", if (typeIdx >= 0) cursor.getInt(typeIdx) else 0)

        if (bodyIdx >= 0) {
            row.put("body", cursor.getString(bodyIdx).orEmpty())
        }

        return row
    }

    /** The requested page size, clamped to (0, MAX_LIMIT]. */
    private fun limitOf(raw: String?): Int {
        val requested = raw?.toIntOrNull() ?: DEFAULT_LIMIT

        return requested.coerceIn(1, MAX_LIMIT)
    }
}
