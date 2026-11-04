package com.vyzorix.connector.cloud

import java.io.IOException
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The device's HTTP surface: enroll, and the config re-fetch.
 *
 * Everything that is not enroll is device-signed with [DeviceRequestSigner]. Enroll
 * is the one unauthenticated call, by necessity — a fresh device has no credential
 * yet — so it sends only the public key.
 *
 * Failures are surfaced as [CloudError] with the status the server returned, so a
 * caller can tell "rate limited" (retry later) from "already claimed" (do not
 * retry) from "network down" (retry with backoff).
 */
class EnrollmentClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val deviceIdProvider: () -> String,
) {
    private val json = CloudJson.json

    /**
     * Enrolls the device. [deviceUuid] is the stable, device-generated id — not the
     * IMEI, which on Android 10+ needs a system-only permission and may be null.
     */
    fun enroll(request: EnrollRequest): EnrollResponse {
        val body = json.encodeToString(request).toRequestBody(JSON)

        val httpRequest =
            Request
                .Builder()
                .url("$baseUrl/v1/device/enroll")
                .post(body)
                .build()

        http.newCall(httpRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CloudError.fromStatus(response.code, text)
            }

            return json.decodeFromString(EnrollResponse.serializer(), text)
        }
    }

    /**
     * Fetches the device's own config by version, for a device that missed the push
     * or holds a stale one. Device-signed.
     */
    fun fetchConfig(requestSigner: DeviceRequestSigner): ConfigResponse {
        val deviceId = deviceIdProvider()
        val path = "/v1/device/$deviceId/config"

        val httpRequest =
            Request
                .Builder()
                .url("$baseUrl$path")
                .get()
                .apply {
                    requestSigner.headers("GET", path, ByteArray(0)).forEach { (k, v) ->
                        header(k, v)
                    }
                }.build()

        http.newCall(httpRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CloudError.fromStatus(response.code, text)
            }

            return json.decodeFromString(ConfigResponse.serializer(), text)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** A failure talking to the server, carrying enough to decide whether to retry. */
class CloudError(
    val status: Int,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    /** True when retrying later could succeed: rate limit, or a server-side fault. */
    val isRetryable: Boolean get() = status == 429 || status >= 500

    /** True when the device is already claimed, so re-enrolling is refused by design. */
    val isAlreadyClaimed: Boolean get() = status == 409

    companion object {
        /** How much of an error body to keep. Enough to identify the cause, bounded. */
        private const val ERROR_BODY_PREVIEW = 256

        fun fromStatus(
            status: Int,
            body: String,
        ): CloudError =
            CloudError(
                status,
                "server returned $status: ${body.take(ERROR_BODY_PREVIEW)}",
            )

        fun network(cause: Throwable): CloudError =
            CloudError(0, "network error: ${cause.message}", cause)
    }
}
