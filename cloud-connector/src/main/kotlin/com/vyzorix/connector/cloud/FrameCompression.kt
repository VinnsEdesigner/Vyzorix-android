package com.vyzorix.connector.cloud

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Decodes the server's compressed-frame envelope.
 *
 * A frame larger than the server's threshold (1 KiB) arrives gzipped:
 *
 *   {"type":"device_config","data":"<gzip bytes>","originalSize":1234,"compressed":true}
 *
 * The `data` field is a Go `[]byte`, which JSON-encodes as **standard base64**, and
 * the bytes are a gzip stream. A smaller frame arrives as the plain frame.
 *
 * The device must decompress before it can read a frame's `args`/`signature`; without
 * this, every config push and every large command was silently dropped as "not a
 * frame" — the deploy path could never work.
 */
internal object FrameCompression {
    /** A conservative cap, matching the server's decompression-bomb guard. */
    private const val MAX_DECOMPRESSED = 16 shl 20

    /**
     * The plain frame text, decompressing [rawFrame] when it is a compressed envelope.
     *
     * Returns null when the input cannot be parsed or decompressed; the caller drops
     * the frame. A plain frame is returned unchanged.
     */
    fun decode(rawFrame: String): String? {
        val obj =
            try {
                CloudJson.json.parseToJsonElement(rawFrame) as? kotlinx.serialization.json.JsonObject
            } catch (e: Exception) {
                return null
            } ?: return null

        // A compressed envelope is the only shape carrying `compressed: true`.
        val compressed =
            (obj["compressed"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        if (!compressed) return rawFrame

        val data =
            (obj["data"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return null

        return try {
            val gz = Base64.decode(data, Base64.DEFAULT)
            String(gunzip(gz), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size * 4)
        GZIPInputStream(bytes.inputStream()).use { gz ->
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = gz.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_DECOMPRESSED) throw IllegalStateException("decompressed frame too large")
                out.write(buffer, 0, read)
            }
        }

        return out.toByteArray()
    }
}
