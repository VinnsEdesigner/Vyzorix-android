package com.vyzorix.connector.command

import com.vyzorix.connector.cloud.CloudJson
import com.vyzorix.connector.cloud.CommandResultFrame
import com.vyzorix.connector.cloud.FrameCompression
import com.vyzorix.connector.cloud.RawJson
import com.vyzorix.connector.cloud.Signatures
import com.vyzorix.connector.cloud.TYPE_DEVICE_CONFIG
import com.vyzorix.connector.cloud.frameTypeOf
import com.vyzorix.connector.config.PinnedServerKeys
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Verifies and routes a command frame from the server.
 *
 * The order is the point: **verify the signature first, then act**. A command
 * frame is an instruction to run something on the device, so an unverified frame
 * is not a command — it is an attacker's command, and executing it would hand
 * whoever sent it the device's privilege.
 *
 * Two things are deliberately explicit:
 *
 *  - An **unknown command type reports failed**, never silence. The architecture
 *    requires it (§5): a device that drops a command it does not implement leaves
 *    the operator watching it sit `delivered` forever, with no signal that nothing
 *    ran. Reporting failed is what makes the gap visible.
 *  - A **bad signature is refused without executing**, and the result is not
 *    reported as a failure of the command — it never was a command. The frame is
 *    dropped.
 */
class CommandDispatcher(
    private val deviceId: String,
    private val pinnedServerKeys: PinnedServerKeys,
    private val executors: Map<String, CommandExecutor>,
) {
    /**
     * Convenience for a single key, so a test or a single-key build need not build a
     * keyring. The verification path is the same either way.
     */
    constructor(
        deviceId: String,
        serverPublicKey: PublicKey,
        executors: Map<String, CommandExecutor>,
    ) : this(
        deviceId,
        PinnedServerKeys.parse(
            java.util.Base64.getEncoder().encodeToString(serverPublicKey.encoded).let { b64 ->
                "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
            },
        ),
        executors,
    )

    /** What handling a frame produced, for the caller to send back over the socket. */
    sealed class Handled {
        /** A command ran (or was reported failed); [result] goes back to the server. */
        class Result(
            val result: CommandResultFrame,
        ) : Handled()

        /** A config frame; the caller applies [document]. */
        class Config(
            val version: Int,
            val document: String,
        ) : Handled()

        /** The frame was refused or is not a command; nothing to send. */
        class Dropped(
            val reason: String,
        ) : Handled()
    }

    /**
     * Handles one inbound text frame.
     *
     * [raw] is the frame's exact bytes: the signature covers `args` verbatim, so it
     * is read from the raw text rather than a re-encoded form.
     */
    fun handle(rawFrame: String): Handled {
        val raw = FrameCompression.decode(rawFrame) ?: return Handled.Dropped("frame did not parse")

        val type = frameTypeOf(raw) ?: return Handled.Dropped("frame has no type")

        if (type == TYPE_DEVICE_CONFIG) {
            return handleConfig(raw)
        }

        return handleCommand(raw)
    }

    @Suppress("SwallowedException")
    private fun handleConfig(raw: String): Handled {
        val frame = decode(raw) ?: return Handled.Dropped("config frame did not parse")

        if (!verifySignature(frame, raw)) {
            return Handled.Dropped("config frame signature invalid")
        }

        val argsRaw =
            RawJson.field(raw, "args") ?: return Handled.Dropped("config frame has no args")

        val args =
            try {
                CloudJson.json.parseToJsonElement(argsRaw).jsonObject
            } catch (e: Exception) {
                return Handled.Dropped("config args did not parse: ${e.message}")
            }

        val document =
            (args["document"] as? JsonPrimitive)?.content
                ?: return Handled.Dropped("config has no document")
        val version = (args["version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

        return Handled.Config(version, document)
    }

    private fun handleCommand(raw: String): Handled {
        val frame = decode(raw) ?: return Handled.Dropped("command frame did not parse")

        if (!verifySignature(frame, raw)) {
            return Handled.Dropped("command frame signature invalid")
        }

        if (frame.dispatchId.isEmpty()) {
            return Handled.Dropped("command frame has no dispatchId")
        }

        val argsRaw = RawJson.field(raw, "args") ?: "{}"

        val executor =
            executors[frame.command]
                ?: return Handled.Result(
                    // An unknown type is reported failed, not dropped: the operator must
                    // learn the command did not run. See the class comment.
                    CommandResultFrame(
                        dispatchId = frame.dispatchId,
                        success = false,
                        output = "unsupported command type: ${frame.command}",
                    ),
                )

        val outcome =
            try {
                executor.execute(argsRaw)
            } catch (t: Throwable) {
                CommandOutcome.failed("${t.javaClass.simpleName}: ${t.message}")
            }

        return Handled.Result(
            CommandResultFrame(
                dispatchId = frame.dispatchId,
                success = outcome.success,
                output = outcome.output,
            ),
        )
    }

    /**
     * Verifies the frame's signature over `CommandCanonical`.
     *
     * The canonical string is built from the frame's own fields plus the raw `args`
     * text, exactly as the server built it. A device that re-encoded `args` would
     * compute a different string and refuse a genuine command, so the raw substring
     * is load-bearing.
     */
    @Suppress("SwallowedException")
    private fun verifySignature(
        frame: ParsedFrame,
        raw: String,
    ): Boolean {
        if (frame.signature.isEmpty()) return false

        val argsRaw = RawJson.field(raw, "args") ?: ""

        val canonical =
            Signatures.command(
                dispatchId = frame.dispatchId,
                deviceId = deviceId,
                command = frame.command,
                timestampMs = frame.timestamp,
                nonce = frame.nonce,
                args = argsRaw,
            )

        // Try every pinned key: during a rotation the server may sign with either.
        // A command that verifies under one of them is authentic; one that verifies
        // under none is not a command and is dropped.

        return pinnedServerKeys.anyVerifies { key ->
            try {
                val sig = Base64.getDecoder().decode(frame.signature)
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(key)
                verifier.update(canonical.toByteArray(Charsets.UTF_8))
                verifier.verify(sig)
            } catch (t: Throwable) {
                // Any failure — bad base64, wrong key, unsupported curve — means the
                // signature does not verify under this key. That is the entire answer,
                // so nothing is carried out of the catch.
                false
            }
        }
    }

    @Suppress("SwallowedException")
    private fun decode(raw: String): ParsedFrame? {
        val obj =
            try {
                CloudJson.json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                // Unparseable: the caller drops the frame. The message is not carried
                // further because a malformed frame is not a diagnosable event.
                return null
            }

        fun str(key: String): String = (obj[key] as? JsonPrimitive)?.content ?: ""

        val timestamp = (obj["timestamp"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0

        return ParsedFrame(
            dispatchId = str("dispatchId"),
            command = str("command"),
            nonce = str("nonce"),
            signature = str("signature"),
            timestamp = timestamp,
        )
    }

    /**
     * The frame's fields as the signature covers them. The device id is not among
     * them: it is the dispatcher's own, supplied once at construction, and the
     * server includes it in the canonical string — so a frame signed for another
     * device fails here.
     */
    private class ParsedFrame(
        val dispatchId: String,
        val command: String,
        val nonce: String,
        val signature: String,
        val timestamp: Long,
    )
}
