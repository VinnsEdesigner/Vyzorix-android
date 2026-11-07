package com.vyzorix.connector.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The OTA exchange, mirroring the server's `VersionManifest` / `UpdaterCheckResult`
 * in `apps/api/internal/api/handlers/updater/update_check.go`.
 *
 * The field names are the server's exactly. `check-update` returns the version
 * fields flattened at the top level (not nested), so a client does not have to know
 * whether it asked the version or the check endpoint.
 */
@Serializable
data class UpdateCheck(
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("version") val version: String = "",
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("apk_filename") val apkFilename: String = "",
    @SerialName("apk_sha256") val apkSha256: String = "",
    @SerialName("release_notes") val releaseNotes: String = "",
    @SerialName("apk_size_bytes") val apkSizeBytes: Long = 0,
)
