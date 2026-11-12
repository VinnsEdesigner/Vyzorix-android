package com.vyzorix.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The the Vyzorix CLI download fallback: a build that does not bundle the archive fetches it
 * from the release, verified against the build's checksum.
 *
 * The interesting cases are the refusals — a fetch with no checksum, a fetch whose
 * bytes do not match it — because a downloaded archive has no APK signature over it,
 * and an unverified one is arbitrary code.
 */
@RunWith(RobolectricTestRunner::class)
class VyzorixCliInstallerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The archive the fake release serves: one executable `bin/supervisor`. */
    private val archive: ByteArray = tarGz(mapOf("bin/supervisor" to "#!/bin/sh\n"))

    private fun installer(
        download: ((String) -> InputStream)? = null,
        baseUrl: String = "https://example.invalid/releases/latest/download",
        abi: String = "arm64-v8a",
        sha256: String = sha256(archive),
    ) = VyzorixCliInstaller(
        context = context,
        // No bundled asset in this test: the emulator's APK does not carry one for the
        // test ABI, so `hasBundledAsset()` is false and the download path is taken.
        download = download,
        baseUrl = baseUrl,
        abi = abi,
        expectedSha256 = sha256,
    )

    @Test
    fun `a fetched archive is extracted, verified and marked installed`() {
        val served = mutableListOf<String>()
        val installer =
            installer(
                download = { url ->
                    served += url
                    ByteArrayInputStream(archive)
                },
            )

        assertTrue("the install must succeed", installer.ensureInstalled())

        // The URL names the ABI's tarball under the base URL.
        assertEquals(
            "https://example.invalid/releases/latest/download/vyzorix-cli-arm64-v8a.tar.gz",
            served.single(),
        )

        // The supervisor is on disk and executable, and the marker records the checksum.
        assertTrue(ProotLauncher.hasSupervisor(context))
        val marker =
            java.io
                .File(context.filesDir, VyzorixCliInstaller.MARKER)
                .readText()
                .trim()
        assertTrue("the marker must record the checksum", marker.endsWith(sha256(archive)))

        // A second call is a no-op: the marker matches.
        assertTrue(installer.ensureInstalled())
        assertEquals("a no-op must not re-fetch", 1, served.size)
    }

    @Test
    fun `a fetch with no checksum is refused`() {
        var fetched = false
        val installer =
            installer(
                download = {
                    fetched = true
                    ByteArrayInputStream(archive)
                },
                sha256 = "",
            )

        assertFalse("an unverified download must be refused", installer.ensureInstalled())
        assertFalse("it must not even fetch", fetched)
    }

    @Test
    fun `a fetch whose bytes do not match the checksum is refused`() {
        val tampered = tarGz(mapOf("bin/supervisor" to "#!/bin/sh\n# attacker\n"))
        val installer =
            installer(
                download = { ByteArrayInputStream(tampered) },
                sha256 = sha256(archive),
            )

        assertFalse("a mismatched archive must be refused", installer.ensureInstalled())
    }

    @Test
    fun `a transport failure is reported, not thrown`() {
        val installer =
            installer(
                download = { throw IOException("network dropped") },
            )

        // `ensureInstalled` catches and returns false: a device without the supervisor
        // runs bridge-only, it does not crash.
        assertFalse(installer.ensureInstalled())
    }

    // --- fixtures -------------------------------------------------------------

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** A minimal ustar tar.gz. Mirrors the connector's TarFixtures, with a mode. */
    private fun tarGz(entries: Map<String, String>): ByteArray {
        val block = 512
        val out = ByteArrayOutputStream()

        GZIPOutputStream(out).use { gz ->
            for ((name, content) in entries) {
                val data = content.toByteArray(Charsets.UTF_8)
                val header = ByteArray(block)
                put(header, 0, 100, name)
                // 0755: executable, which is what `hasSupervisor` checks.
                put(header, 100, 8, "0000755")
                put(header, 108, 8, "0000000")
                put(header, 116, 8, "0000000")
                put(header, 124, 12, data.size.toString(8).padStart(11, '0'))
                put(header, 136, 12, "00000000000")
                header[156] = '0'.code.toByte()
                put(header, 257, 6, "ustar")
                put(header, 263, 2, "00")

                var sum = 0
                for (b in header) sum += b.toInt() and 0xFF
                put(header, 148, 8, sum.toString(8).padStart(6, '0') + "\u0000 ")

                gz.write(header)
                gz.write(data)
                gz.write(ByteArray((block - (data.size % block)) % block))
            }
            gz.write(ByteArray(block * 2))
        }

        return out.toByteArray()
    }

    private fun put(
        target: ByteArray,
        offset: Int,
        length: Int,
        value: String,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, target, offset, minOf(bytes.size, length))
    }
}
