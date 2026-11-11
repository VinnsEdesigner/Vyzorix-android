package com.vyzorix.connector.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The device's OTA client: ask the server whether a newer build exists, download it,
 * and verify it against the manifest's SHA-256.
 *
 * It is deliberately two steps — check, then download — because the check is cheap
 * and can run on every connect, while the download is tens of megabytes and must not
 * be paid when there is nothing to fetch.
 *
 * The checksum is what makes the download trustworthy. The APK is fetched over
 * whatever the OS gives us (the same HTTP client the rest of the connector uses),
 * and the manifest hash is the only thing that binds the bytes to the release — so a
 * missing or mismatched hash is a refusal, not a warning. This mirrors the rootfs
 * download, which refuses an unverified tarball for the same reason.
 */
class UpdateClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
    /**
     * The device's own id, for the download-progress report.
     *
     * A provider rather than a value because the id is not known until enrollment
     * finishes, which is after this client is built — the same shape the enrollment
     * client uses. An empty id means the report is skipped; progress reporting is
     * best-effort and must never fail a download.
     */
    private val deviceId: () -> String = { "" },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Asks whether a build newer than [currentVersionCode] is available.
     *
     * A failure is reported as null rather than thrown: an update check that cannot
     * reach the server is not an error the device should act on, and the next connect
     * retries.
     */
    @Suppress("SwallowedException") // A failed check is "no answer"; the next connect retries.
    fun check(currentVersionCode: Int): UpdateCheck? {
        val request =
            Request
                .Builder()
                .url("$baseUrl/v1/check-update?version_code=$currentVersionCode")
                .get()
                .build()

        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return null
                }

                json.decodeFromString(UpdateCheck.serializer(), body)
            }
        } catch (e: Exception) {
            // A failed check is not an error the device acts on: the next connect
            // retries, and the caller only needs "no answer". Swallowing is the
            // contract, so it is logged and named by the function-level suppress.
            android.util.Log.d(TAG, "update check failed: ${e.message}")

            null
        }
    }

    /**
     * Downloads [check]'s APK to [destination] and verifies it.
     *
     * Returns the verified file, or throws. The verification is the point: an APK
     * that does not match the manifest is refused rather than installed, because the
     * install path is the one place a bad download becomes arbitrary code.
     */
    fun download(
        check: UpdateCheck,
        destination: File,
        onProgress: (percent: Int) -> Unit = {},
    ): File {
        // One guard, one throw: the manifest must name a file and carry the hash the
        // download is verified against. Detekt's ThrowsCount prefers a single refusal
        // here rather than one per field.
        if (check.apkFilename.isEmpty() || check.apkSha256.isEmpty()) {
            val apkName = check.apkFilename.ifEmpty { "(none)" }
            val hash = check.apkSha256.ifEmpty { "(none)" }
            throw IOException("manifest is not installable: apk=$apkName sha256=$hash")
        }

        // The server serves the APK at /v1/apk/<name>; the name is a basename, so it
        // cannot escape the directory it is served from (the server also enforces
        // that with filepath.Base).
        destination.parentFile?.mkdirs()

        // Resume: a partial file from a previous attempt (the network dropped
        // mid-download) is continued with a Range request rather than truncated. The
        // manifest's checksum is the guarantee, so a partial file is only ever
        // *appended to* — the whole thing is verified at the end regardless of how
        // many attempts it took.
        val partial = partialBytes(check, destination)

        val request =
            Request
                .Builder()
                .url("$baseUrl/v1/apk/${check.apkFilename}")
                .apply { if (partial > 0) header("Range", "bytes=$partial-") }
                .get()
                .build()

        http.newCall(request).execute().use { response ->
            // 200 = the server ignored the Range (no resume, start over); 206 = the
            // range was honoured (append). Anything else is a failure.
            val resuming = partial > 0 && response.code == HTTP_PARTIAL
            requireUsableResponse(response.code, response.isSuccessful, partial, resuming)

            val body = response.body ?: throw IOException("apk download returned no body")

            // A 200 to a Range request means the server will not resume, so the file
            // must be rewritten from the start; a 206 appends.
            val startAt = if (resuming) partial else 0L
            if (!resuming && destination.exists()) destination.delete()

            // `total` is the whole file's size, not this response's, so progress is
            // measured against the finished download across attempts.
            val remaining = body.contentLength().takeIf { it > 0 } ?: (check.apkSizeBytes - startAt)
            val total = startAt + remaining

            body.byteStream().use { input ->
                // Append mode is the whole point — it is what stops the
                // restart-from-zero. `File.outputStream(append=)` does not exist, so the
                // stream is opened directly.
                java.io.FileOutputStream(destination, resuming).use { output ->
                    copyReportingProgress(
                        input,
                        output,
                        total,
                        startAt,
                        check.apkFilename,
                        onProgress,
                    )
                }
            }
        }

        val actual = sha256(destination)
        if (!actual.equals(check.apkSha256, ignoreCase = true)) {
            destination.delete()

            throw IOException("apk checksum mismatch: expected ${check.apkSha256}, got $actual")
        }

        return destination
    }

    /**
     * Throws unless [code] is a response the download can use.
     *
     * Usable means: 2xx, and — when a Range was sent — either 206 (honoured, append) or
     * 200 (ignored, full body). A 2xx that is neither cannot be resumed from and is not
     * the whole file, so it is a failure like any other. Kept out of [download] so that
     * method has one HTTP throw rather than two (detekt's ThrowsCount).
     */
    private fun requireUsableResponse(
        code: Int,
        successful: Boolean,
        partial: Long,
        resuming: Boolean,
    ) {
        if (!successful) {
            throw IOException("apk download failed: HTTP $code")
        }
        if (partial > 0 && !resuming && code != HTTP_OK) {
            throw IOException("apk download failed: HTTP $code")
        }
    }

    /**
     * How many bytes of a partial download to resume from, or 0.
     *
     * A staged file is only resumed when it is *smaller* than the manifest's size and
     * the manifest names a size. A file at or past the expected size is either complete
     * (and `matches` already returned it, so we would not be here) or corrupt, and is
     * restarted rather than appended to.
     */
    private fun partialBytes(
        check: UpdateCheck,
        destination: File,
    ): Long {
        if (!destination.isFile) return 0

        val length = destination.length()
        if (length <= 0) return 0
        if (check.apkSizeBytes > 0 && length >= check.apkSizeBytes) return 0

        return length
    }

    /**
     * Copies [input] to [output], reporting progress to the server as it goes.
     *
     * The report is throttled: a 64 MB download in 8 KB reads would be thousands of
     * requests, so it fires only when the whole-percent changes. A failed report is
     * swallowed — progress is for an operator's dashboard, and losing one must never
     * fail the download that is actually delivering the update.
     */
    private fun copyReportingProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        /** Bytes already on disk from a resumed download; progress starts here. */
        startAt: Long,
        filename: String,
        onProgress: (percent: Int) -> Unit,
    ) {
        val id = deviceId()
        val buffer = ByteArray(COPY_BUFFER)
        var loaded = startAt
        var lastReportedPercent = -1

        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            loaded += read

            if (total <= 0) continue

            val percent = ((loaded * 100) / total).toInt()
            if (percent != lastReportedPercent) {
                lastReportedPercent = percent
                // The local callback drives the notification's progress bar, so it runs
                // even when there is no device id to report against.
                onProgress(percent)
                if (id.isNotEmpty()) {
                    reportProgress(id, filename, loaded, total, percent)
                }
            }
        }
    }

    /** Reports progress, best-effort. Never throws. */
    private fun reportProgress(
        deviceId: String,
        filename: String,
        loaded: Long,
        total: Long,
        percent: Int,
    ) {
        val payload =
            buildString {
                append("{\"deviceId\":\"").append(deviceId).append("\",")
                append("\"filename\":\"").append(filename).append("\",")
                append("\"bytesLoaded\":").append(loaded).append(",")
                append("\"totalBytes\":").append(total).append(",")
                append("\"progress\":").append(percent).append("}")
            }

        try {
            val request =
                Request
                    .Builder()
                    .url("$baseUrl/v1/download-progress")
                    .post(payload.toByteArray().toRequestBody(JSON_MEDIA))
                    .build()

            http.newCall(request).execute().close()
        } catch (e: Exception) {
            android.util.Log.d(TAG, "progress report failed (ignored): ${e.message}")
        }
    }

    /**
     * True when [file] already matches [check]'s manifest hash.
     *
     * Used to skip a re-download: a completed download from an earlier connect is
     * still good, and re-fetching tens of megabytes on every reconnect is exactly the
     * data leak this guards. A missing manifest hash means "cannot confirm", so the
     * caller downloads — never "assume it matches".
     */
    fun matches(
        check: UpdateCheck,
        file: File,
    ): Boolean {
        if (check.apkSha256.isEmpty() || !file.isFile) return false

        return sha256(file).equals(check.apkSha256, ignoreCase = true)
    }

    /** The lowercase hex SHA-256 of [file]. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "UpdateClient"
        const val COPY_BUFFER = 64 * 1024

        /** HTTP 200: a full response, so a Range request was not honoured. */
        const val HTTP_OK = 200

        /** HTTP 206: a partial response, so a Range request was honoured. */
        const val HTTP_PARTIAL = 206
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
