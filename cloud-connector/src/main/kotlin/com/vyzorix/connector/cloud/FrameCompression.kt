package com.vyzorix.connector.cloud

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes the server's compressed-frame envelope.
 *
 * A frame larger than the server's threshold (1 KiB) arrives gzipped:
 *
 *   {"type":"device_config","data":"<gzip bytes>","compressed":true}
 *
 * The `data` field is a Go `[]byte`, which JSON-encodes as **standard base64**, and
 * the bytes are a gzip stream. A smaller frame arrives as the plain frame.
 *
 * The device must decompress before it can read a frame's `args`/`signature`; without
 * this, every config push and every large command was silently dropped as "not a
 * frame" — the deploy path could never work.
 */
internal object FrameCompression {
    /** The field marking a compressed envelope. */
    private const val COMPRESSED = "compressed"

    /** The field carrying the gzip bytes. */
    private const val DATA = "data"

    /** A conservative cap, matching the server's decompression-bomb guard. */
    private const val MAX_DECOMPRESSED = 16 shl 20

    private const val COPY_BUFFER = 64 * 1024

    /**
     * The plain frame text, decompressing [rawFrame] when it is a compressed envelope.
     *
     * Returns null when the input cannot be parsed or decompressed; the caller drops
     * the frame. A plain frame is returned unchanged.
     */
    fun decode(rawFrame: String): String? {
        val obj = parseObject(rawFrame) ?: return null

        if ((obj[COMPRESSED] as? JsonPrimitive)?.content != "true") return rawFrame

        val data = (obj[DATA] as? JsonPrimitive)?.content ?: return null

        return gunzipOrNull(Base64.decode(data, Base64.DEFAULT))
    }

    // Not JSON: the caller treats null as "not a frame it recognises".
    @Suppress("SwallowedException")
    private fun parseObject(raw: String): JsonObject? =
        try {
            CloudJson.json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            // Not JSON: the caller treats null as "not a frame it recognises".
            null
        }

    // A malformed gzip stream: the caller drops the frame.
    @Suppress("SwallowedException")
    private fun gunzipOrNull(bytes: ByteArray): String? =
        try {
            String(gunzip(bytes), Charsets.UTF_8)
        } catch (e: Exception) {
            // A malformed gzip stream: the caller drops the frame.
            null
        }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size * BUFFER_GROWTH)
        GZIPInputStream(bytes.inputStream()).use { gz ->
            val buffer = ByteArray(COPY_BUFFER)
            var total = 0
            while (true) {
                val read = gz.read(buffer)
                if (read < 0) break
                total += read
                check(total <= MAX_DECOMPRESSED) { "decompressed frame too large" }
                out.write(buffer, 0, read)
            }
        }

        return out.toByteArray()
    }

    /** The initial output buffer is sized as a multiple of the compressed input. */
    private const val BUFFER_GROWTH = 4
}
