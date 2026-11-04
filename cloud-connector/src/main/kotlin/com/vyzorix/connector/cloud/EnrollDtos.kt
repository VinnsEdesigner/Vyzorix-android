package com.vyzorix.connector.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The enroll exchange, mirroring the server's `enrollRequest` / `enrollResponse`
 * in `apps/api/internal/api/handlers/inbox/enroll_handler.go`.
 *
 * The request carries no secret: the public key is the device's identity, and the
 * private half never leaves the device. The response carries a pairing code to
 * display and how long it is good for — nothing to intercept.
 */
@Serializable
data class EnrollRequest(
    @SerialName("deviceUuid") val deviceUuid: String,
    @SerialName("publicKey") val publicKey: String,
    @SerialName("model") val model: String = "",
    @SerialName("manufacturer") val manufacturer: String = "",
    @SerialName("osVersion") val osVersion: String = "",
    @SerialName("appVersion") val appVersion: String = "",
    @SerialName("deviceClass") val deviceClass: String = "",
    @SerialName("tierHint") val tierHint: Int = 0,
)

@Serializable
data class EnrollResponse(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("pairingCode") val pairingCode: String,
    @SerialName("pairingDisplay") val pairingDisplay: String,
    @SerialName("expiresAt") val expiresAt: Long,
    /**
     * Remaining time in seconds. The server sends this alongside the absolute
     * deadline so a device with a wrong clock still expires its code correctly.
     */
    @SerialName("expiresInSeconds") val expiresInSeconds: Long,
)

/**
 * The config re-fetch response: `GET /v1/device/:imei/config`. The device asks by
 * version and applies the document if it differs from what it holds.
 */
@Serializable
data class ConfigResponse(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("version") val version: Int,
    @SerialName("issuedAt") val issuedAt: Long,
    @SerialName("document") val document: String,
)
