package com.vyzorix.connector.config

import com.vyzorix.libv.VParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The structural no-secret rule. The load-bearing
 * pair is the last two tests: a config that *names* a secret must be accepted, and
 * one that *carries* a value must be refused. The old line scanner failed the first
 * half — it refused its own schema field — so both halves are asserted.
 *
 * The shapes are the server's `configscan`; these tests pin the port so a drift
 * between the two implementations shows up here.
 */
class ConfigSecretScanTest {
    private val scan = ConfigSecretScan()

    private fun offending(body: String): String? = scan.offendingKey(VParser.parse(body).root)

    @Test
    fun `a clean config with an apps section is accepted`() {
        val body =
            """
            v device d_1
            vv apps
            vvv app vfd
            vvvv env MI_PORT=8080
            vvvv secret DATABASE_URL
            vvvv artifact engine
            vvvvv sha256 9f2c
            """.trimIndent() + "\n"

        assertNull(offending(body))
    }

    @Test
    fun `a secret NAME node is accepted`() {
        // The half the shipped scanner failed: `secret NAME` names a secret without
        // carrying one, which is exactly what the field is for.
        val body = "v device d_1\nvv apps\nvvv app vfd\nvvvv secret S3_SECRET_KEY\n"

        assertNull(offending(body))
    }

    @Test
    fun `a secret node carrying a value-shaped token is refused`() {
        // A secret *name* is not a JWT. A node whose value is a credential shape is
        // refused even though the `secret` key itself is legal inside an app.
        val jwt =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val body = "v device d_1\nvv apps\nvvv app vfd\nvvvv secret $jwt\n"

        assertEquals("secret", offending(body))
    }

    @Test
    fun `an env key naming a secret is refused`() {
        val body = "v device d_1\nvv apps\nvvv app vfd\nvvvv env TOKEN=abc123\n"

        assertEquals("env.TOKEN", offending(body))
    }

    @Test
    fun `an env value that looks like a secret is refused`() {
        val body =
            "v device d_1\nvv apps\nvvv app vfd\n" +
                "vvvv env DATABASE_URL=postgres://user:pass@db.internal:5432/app\n"

        assertEquals("env.DATABASE_URL", offending(body))
    }

    @Test
    fun `an ordinary env value is accepted`() {
        val body =
            "v device d_1\nvv apps\nvvv app vfd\n" +
                "vvvv env MI_PORT=8080\nvvvv env LOG_LEVEL=debug\n"

        assertNull(offending(body))
    }

    @Test
    fun `an artifact sha256 is not mistaken for a secret`() {
        // 64 hex characters is a digest, not a credential — the reason bare hex and
        // base64 are absent from the context-free shapes.
        val body =
            "v device d_1\nvv apps\nvvv app vfd\nvvvv artifact engine\n" +
                "vvvvv sha256 9f2c1e6b3a4d5c7e8f90112233445566778899aabbccddeeff00112233445566\n"

        assertNull(offending(body))
    }

    @Test
    fun `a secret smuggled into an unexpected field is caught`() {
        val jwt =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val body = "v device d_1\nvv name $jwt\n"

        assertEquals("name", offending(body))
    }

    @Test
    fun `a secret key outside an app is refused`() {
        // A `secret` key is only the name-only field inside an app; elsewhere it is
        // an attempt to carry one.
        val body = "v device d_1\nvv secret TOKEN\n"

        assertEquals("secret", offending(body))
    }
}
