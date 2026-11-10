package com.vyzorix.connector.deploy

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/**
 * Fetches an artifact to disk and verifies its SHA-256.
 *
 * A reference is `{url, sha256}` and the signature covers the digest, so the
 * transport is untrusted: the bytes may arrive from anywhere and are trusted only
 * because they hash to what was signed.
 *
 * A partial download resumes with a Range request, and the digest is then computed
 * over the **whole** file — not just the new tail. A resumed transfer whose prefix
 * was tampered with must still fail.
 */
class ArtifactFetcher(
    private val http: OkHttpClient,
) {
    /**
     * Fetches [url] into [destination], verifying [sha256].
     *
     * @throws IOException on a network failure or a digest mismatch. On a mismatch
     *   the file is removed, so a bad download never becomes the base of the next
     *   attempt.
     */
    fun fetch(
        url: String,
        sha256: String,
        destination: File,
    ) {
        destination.parentFile?.mkdirs()

        download(url, destination)
        verify(destination, sha256)
    }

    private fun download(
        url: String,
        destination: File,
    ) {
        val existing = if (destination.isFile) destination.length() else 0L
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("artifact fetch failed: HTTP ${response.code} for $url")
            }
            val body =
                response.body
                    ?: throw IOException("artifact fetch returned no body for $url")

            // Resume only when the server honoured the Range; otherwise start over.
            val offset = if (existing > 0 && response.code == HTTP_PARTIAL) existing else 0L
            if (offset == 0L && destination.exists()) {
                destination.delete()
            }
            writeBody(body, destination, offset)
        }
    }

    private fun writeBody(
        body: ResponseBody,
        destination: File,
        offset: Long,
    ) {
        FileOutputStream(destination, offset > 0).use { out ->
            body.byteStream().use { input -> copy(input, out) }
        }
    }

    private fun copy(
        input: InputStream,
        out: OutputStream,
    ) {
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            out.write(buffer, 0, read)
        }
    }

    private fun verify(
        destination: File,
        sha256: String,
    ) {
        val actual = sha256Of(destination)
        if (!actual.equals(sha256, ignoreCase = true)) {
            destination.delete()

            throw DigestMismatch("artifact checksum mismatch: expected $sha256, got $actual")
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER)

        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val HTTP_PARTIAL = 206
    }
}
