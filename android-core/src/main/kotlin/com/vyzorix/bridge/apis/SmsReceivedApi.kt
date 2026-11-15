package com.vyzorix.bridge.apis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.VyzorixApi
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * What has arrived by SMS since this service started, and whether receiving is
 * possible at all.
 *
 * **What this reports.** It counts messages and records the most recent sender, as
 * the **raw originating address**. The sender is reported verbatim because the
 * user workflows that need it — whitelisting a paging number, telling a service
 * sender from a person — cannot work on a hash. The count and the sender are the
 * facts a flasher or a paging workflow acts on.
 *
 * It does **not** return message bodies. A text is the private content of whoever
 * sent it, and nothing in the design needs it: the sender answers "who is this",
 * which is the question an operator is actually asking. The body is not offered, so
 * it cannot leak.
 *
 * **Why it is a class, not an object.** A received SMS is an event, so the count
 * only exists if something is listening. [start] registers a receiver and [stop]
 * unregisters it; the service owns that lifecycle, the way it owns `UsbBroker`.
 * An `object` would need a process-wide receiver it could never take down.
 *
 * `RECEIVE_SMS` is dangerous and is checked by the router before [handle] runs. The
 * receiver is registered only when the permission is held, so a device without it
 * reports `listening: false` rather than silently counting nothing — the difference
 * between "no messages" and "not watching" matters to whoever is waiting.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
class SmsReceivedApi : ApiHandler {

    /** Messages seen since [start]. Atomic because the receiver is on the main thread. */
    private val count = AtomicLong(0)

    @Volatile private var lastSender: String? = null
    private val lastAtMillis = AtomicLong(0)

    @Volatile private var listening = false
    private var receiver: BroadcastReceiver? = null

    /**
     * Begins observing incoming SMS on [context].
     *
     * Idempotent: a second call is a no-op, so the service can call it on every
     * start without tracking whether it already did.
     */
    fun start(context: Context) {
        if (listening) return

        val target = context.applicationContext
        val next =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (intent.action != SMS_RECEIVED) return
                    record(intent)
                }
            }

        try {
            target.registerReceiver(next, IntentFilter(SMS_RECEIVED))
        } catch (e: SecurityException) {
            // RECEIVE_SMS not granted. Not an error: the API reports the state, and
            // the caller learns receiving is off rather than that the call failed.
            // Logged so the reason is visible.
            Log.d(TAG, "SMS receiver not registered: ${e.message}")
            return
        }

        receiver = next
        listening = true
    }

    /** Stops observing. Safe to call when not started. */
    fun stop(context: Context) {
        val current = receiver ?: return

        runCatching { context.applicationContext.unregisterReceiver(current) }
        receiver = null
        listening = false
    }

    override fun handle(call: ApiCall): ApiOutcome {
        val result =
            JSONObject()
                .put("listening", listening)
                .put("count", count.get())
                .put("hasMessage", lastAtMillis.get() > 0)

        if (lastAtMillis.get() > 0) {
            result
                .put("lastSender", lastSender ?: "")
                .put("lastAgeMillis", System.currentTimeMillis() - lastAtMillis.get())
        }

        return ApiOutcome.ok(result)
    }

    /**
     * Counts one received message and remembers who sent it.
     *
     * A multipart message arrives as several PDUs in one broadcast, so the parts are
     * counted as one message — otherwise a single long text would read as three, and
     * the count would stop meaning "messages".
     */
    private fun record(intent: Intent) {
        @Suppress("DEPRECATION")
        val pdus = intent.extras?.get(PDU_KEY) as? Array<*> ?: return
        if (pdus.isEmpty()) return

        // The originating address is the same across a message's parts; the first
        // non-null one is the sender.
        var sender: String? = null
        for (pdu in pdus) {
            val bytes = pdu as? ByteArray ?: continue
            val message = messageFrom(bytes, intent)
            if (sender == null) sender = message?.originatingAddress
        }

        count.incrementAndGet()
        lastAtMillis.set(System.currentTimeMillis())
        sender?.takeIf { it.isNotEmpty() }?.let { lastSender = it }
    }

    /**
     * Decodes one PDU, guarding the format argument by API level.
     *
     * The two-argument `createFromPdu` (which takes the format) arrived in API 23,
     * so the one-argument form is the pre-23 path. `minSdk` is 26, so the guard is
     * dead code in practice — it exists because the deprecated overload is still
     * present in the compile SDK and calling it unconditionally is what the
     * platform's own lint flags.
     */
    private fun messageFrom(
        bytes: ByteArray,
        intent: Intent,
    ): SmsMessage? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            SmsMessage.createFromPdu(bytes, intent.getStringExtra(FORMAT_KEY))
        } else {
            @Suppress("DEPRECATION")
            SmsMessage.createFromPdu(bytes)
        }

    companion object {
        const val NAME = "sms_received"

        /** The platform's broadcast for an incoming SMS. */
        const val SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED"

        private const val PDU_KEY = "pdus"
        private const val FORMAT_KEY = "format"

        private const val TAG = "SmsReceivedApi"
    }
}
