package com.vyzorix.connector.deploy

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Builds minimal ustar tar.gz archives for the apply tests. */
object TarFixtures {
    private const val BLOCK = 512

    fun tarGz(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for ((name, content) in entries) {
                val data = content.toByteArray(Charsets.UTF_8)
                gz.write(header(name, data.size.toLong()))
                gz.write(data)
                gz.write(ByteArray((BLOCK - (data.size % BLOCK)) % BLOCK))
            }
            gz.write(ByteArray(BLOCK * 2))
        }

        return out.toByteArray()
    }

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun header(
        name: String,
        size: Long,
    ): ByteArray {
        val h = ByteArray(BLOCK)
        put(h, 0, 100, name)
        put(h, 100, 8, "0000755")
        put(h, 108, 8, "0000000")
        put(h, 116, 8, "0000000")
        put(h, 124, 12, size.toString(8).padStart(11, '0'))
        put(h, 136, 12, "00000000000")
        h[156] = '0'.code.toByte()
        put(h, 257, 6, "ustar")
        put(h, 263, 2, "00")

        var sum = 0
        for (b in h) sum += b.toInt() and 0xFF
        put(h, 148, 8, sum.toString(8).padStart(6, '0') + "\u0000 ")

        return h
    }

    private fun put(
        h: ByteArray,
        offset: Int,
        length: Int,
        value: String,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, h, offset, minOf(bytes.size, length))
    }
}
