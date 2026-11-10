package com.vyzorix.connector.deploy

import com.vyzorix.connector.cloud.CloudJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The frame a device sends after an apply.
 * `type` is `device_ready` — the server's handler routes on it and records
 * the version — and a refusal adds the machine-readable `reason` and the `detail`
 * that names the app or artifact. A successful apply sends the same frame with
 * `success = true` and no reason.
 */
@Serializable
data class DeployReportFrame(
    @SerialName("type") val type: String = "device_ready",
    @SerialName("version") val version: Int,
    @SerialName("success") val success: Boolean,
    @SerialName("reason") val reason: String = "",
    @SerialName("detail") val detail: String = "",
) {
    fun toJson(): String = CloudJson.outbound.encodeToString(serializer(), this)
}

/** Builds the report frame for an [ApplyResult]. */
object DeployReporter {
    fun frame(result: ApplyResult): DeployReportFrame =
        DeployReportFrame(
            version = result.version,
            success = result.success,
            reason = result.reason.orEmpty(),
            detail = result.detail.orEmpty(),
        )
}
