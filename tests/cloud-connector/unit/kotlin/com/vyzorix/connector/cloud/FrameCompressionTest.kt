package com.vyzorix.connector.cloud

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The server compresses any frame over 1 KiB into a base64 gzip envelope. Without
 * decompressing, the device read every large frame as "not a frame" and silently
 * dropped it — the whole config/command push. This pins the envelope shape.
 */
@RunWith(RobolectricTestRunner::class)
class FrameCompressionTest {
    private fun gzipBase64(text: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }

        return Base64.encodeToString(out.toByteArray(), Base64.DEFAULT)
    }

    @Test
    fun `a compressed envelope is decompressed to the plain frame`() {
        val plain = """{"type":"device_config","command":"DEVICE_CONFIG","args":{"version":1}}"""
        val envelope = """{"type":"device_config","data":"${gzipBase64(plain)}","compressed":true,"originalSize":${plain.length}}"""

        assertEquals(plain, FrameCompression.decode(envelope))
    }

    @Test
    fun `a plain frame passes through unchanged`() {
        val plain = """{"type":"device_config","args":{"version":1},"signature":"abc"}"""

        assertEquals(plain, FrameCompression.decode(plain))
    }

    @Test
    fun `a malformed frame returns null`() {
        assertNull(FrameCompression.decode("not json"))
    }
}
