package com.vyzorix.connector.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

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
        val request =
            Request
                .Builder()
                .url("$baseUrl/v1/apk/${check.apkFilename}")
                .get()
                .build()

        destination.parentFile?.mkdirs()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("apk download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("apk download returned no body")
            body.byteStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val actual = sha256(destination)
        if (!actual.equals(check.apkSha256, ignoreCase = true)) {
            destination.delete()

            throw IOException("apk checksum mismatch: expected ${check.apkSha256}, got $actual")
        }

        return destination
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
    }
}
