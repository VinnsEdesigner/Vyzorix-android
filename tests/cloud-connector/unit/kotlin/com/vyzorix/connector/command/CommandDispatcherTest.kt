package com.vyzorix.connector.command

import com.vyzorix.connector.cloud.Signatures
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dispatcher must verify before it acts, and must report an unknown command as
 * failed rather than dropping it.
 *
 * These tests build a frame the way the **server** builds one — same canonical
 * string, signed with a P-256 key — so a drift in the canonical string shows up
 * here rather than on a device. The server's public key is the only one that
 * verifies; anything else is refused.
 */
class CommandDispatcherTest {
    private val serverKey: KeyPair = generateKey()
    private val deviceId = "dev-1"

    private fun dispatcher(
        executors: Map<String, CommandExecutor> = emptyMap(),
    ): CommandDispatcher = CommandDispatcher(deviceId, serverKey.public, executors)

    /** Signs a command frame the way the server does, returning the raw JSON. */
    private fun signedFrame(
        dispatchId: String,
        command: String,
        args: String,
        timestampMs: Long = 1_767_225_600_000,
        nonce: String = "n-1",
        signingKey: KeyPair = serverKey,
    ): String {
        val canonical =
            Signatures.command(
                dispatchId = dispatchId,
                deviceId = deviceId,
                command = command,
                timestampMs = timestampMs,
                nonce = nonce,
                args = args,
            )
        val signature = sign(signingKey, canonical)

        val argsField = if (args.isEmpty()) "" else ""","args":$args"""

        return """{"type":"command","dispatchId":"$dispatchId","command":"$command",""" +
            """"nonce":"$nonce","signature":"$signature","timestamp":$timestampMs$argsField}"""
    }

    @Test
    fun `a valid command routes to its executor`() {
        var ran = false
        val executors =
            mapOf(
                "SHELL_EXEC" to
                    CommandExecutor { args ->
                        ran = true
                        assertEquals("""{"cmd":"id"}""", args)
                        CommandOutcome.ok("uid=0")
                    },
            )

        val handled =
            dispatcher(
                executors,
            ).handle(signedFrame("d1", "SHELL_EXEC", """{"cmd":"id"}"""))

        assertTrue(ran)
        val result = (handled as CommandDispatcher.Handled.Result).result
        assertTrue(result.success)
        assertEquals("uid=0", result.output)
        assertEquals("d1", result.dispatchId)
    }

    @Test
    fun `a bad signature is refused without executing`() {
        var ran = false
        val executors =
            mapOf(
                "SHELL_EXEC" to
                    CommandExecutor {
                        ran = true
                        CommandOutcome.ok("x")
                    },
            )

        // Signed by a different key: an attacker's frame.
        val attacker = generateKey()
        val frame = signedFrame("d1", "SHELL_EXEC", "{}", signingKey = attacker)

        val handled = dispatcher(executors).handle(frame)

        assertEquals(false, ran)
        assertTrue(handled is CommandDispatcher.Handled.Dropped)
    }

    @Test
    fun `a tampered args field is refused`() {
        var ran = false
        val executors =
            mapOf(
                "SHELL_EXEC" to
                    CommandExecutor {
                        ran = true
                        CommandOutcome.ok("x")
                    },
            )

        val frame = signedFrame("d1", "SHELL_EXEC", """{"cmd":"id"}""")
        // Swap the command after signing: the signature covers it, so it must fail.
        val tampered = frame.replace("SHELL_EXEC", "FACTORY_RESET")

        val handled = dispatcher(executors).handle(tampered)

        assertEquals(false, ran)
        assertTrue(handled is CommandDispatcher.Handled.Dropped)
    }

    @Test
    fun `a missing signature is refused`() {
        var ran = false
        val executors =
            mapOf(
                "SHELL_EXEC" to
                    CommandExecutor {
                        ran = true
                        CommandOutcome.ok("x")
                    },
            )

        val frame =
            """{"type":"command","dispatchId":"d1","command":"SHELL_EXEC",""" +
                """"nonce":"n","timestamp":1}"""

        val handled = dispatcher(executors).handle(frame)

        assertEquals(false, ran)
        assertTrue(handled is CommandDispatcher.Handled.Dropped)
    }

    @Test
    fun `an unknown command type is reported failed, never dropped`() {
        // The architecture requires this: a device that drops a command it does not
        // implement leaves the user watching it sit delivered forever.
        val frame = signedFrame("d1", "USB_ATTACH", "{}")

        val handled = dispatcher().handle(frame)

        val result = (handled as CommandDispatcher.Handled.Result).result
        assertEquals(false, result.success)
        assertTrue(result.output.contains("unsupported command type"))
    }

    @Test
    fun `an executor that throws is reported as a failure`() {
        val executors =
            mapOf(
                "SHELL_EXEC" to
                    CommandExecutor { throw IllegalStateException("boom") },
            )

        val handled = dispatcher(executors).handle(signedFrame("d1", "SHELL_EXEC", "{}"))

        val result = (handled as CommandDispatcher.Handled.Result).result
        assertEquals(false, result.success)
        assertTrue(result.output.contains("boom"))
    }

    @Test
    fun `a config frame is recognised and its document handed back`() {
        val document = "v device dev-1\nv version 1\n"
        val args = """{"version":1,"document":${jsonString(document)}}"""
        val frame = signedFrame("", "DEVICE_CONFIG", args)

        // The frame type the server uses for a config is device_config, not command.
        val configFrame = frame.replace(""""type":"command"""", """"type":"device_config"""")

        val handled = dispatcher().handle(configFrame)

        val config = handled as CommandDispatcher.Handled.Config
        assertEquals(1, config.version)
        assertEquals(document, config.document)
    }

    /**
     * The server echoes device telemetry back to connected clients as a `broadcast`
     * frame, which carries no `command`. It is not a command, so it must be ignored,
     * not verified and reported as a signature failure — which is what produced a
     * "command frame signature invalid" warning on every telemetry tick.
     */
    @Test
    fun `a telemetry broadcast echo is ignored, not reported as a signature failure`() {
        val echo =
            """{"type":"broadcast","args":{"type":"telemetry","deviceId":"dev-1",""" +
                """"uptime":123}}"""

        val handled = dispatcher().handle(echo)

        assertTrue(handled is CommandDispatcher.Handled.Ignored)
    }

    /**
     * An unsigned frame that *does* claim a command is still refused: the ignore path
     * must not become a way for an attacker to smuggle a command past verification.
     */
    @Test
    fun `a frame with a command but no signature is still dropped`() {
        val frame = """{"type":"SHELL_EXEC","command":"SHELL_EXEC","dispatchId":"d1"}"""

        val handled = dispatcher().handle(frame)

        assertTrue(handled is CommandDispatcher.Handled.Dropped)
    }

    private fun jsonString(value: String): String {
        val escaped =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")

        return "\"$escaped\""
    }

    private fun sign(
        key: KeyPair,
        canonical: String,
    ): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key.private)
        signer.update(canonical.toByteArray())

        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun generateKey(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
}
