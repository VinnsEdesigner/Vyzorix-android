package com.vyzorix.connector.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON text frames that cross the device WebSocket.
 *
 * Field names are the server's exactly (`apps/api/internal/ws/inbound.go` and
 * `internal/domain/command/command_entity.go`). kotlinx.serialization is
 * configured to ignore unknown keys, because the server adds fields over time and
 * an older device must not fail on a frame it does not fully understand.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object CloudJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
}

/**
 * A frame from the server. [type] is the discriminator; `telemetry` is handled by
 * the hub and everything else arrives at the dispatcher.
 *
 * [args] is deliberately not decoded here — the signature covers its exact bytes,
 * so the dispatcher reads the raw substring with [RawJson] and only parses it once
 * the signature is trusted.
 */
@Serializable
data class CommandFrame(
    @SerialName("type") val type: String = "",
    @SerialName("dispatchId") val dispatchId: String = "",
    @SerialName("command") val command: String = "",
    @SerialName("nonce") val nonce: String = "",
    @SerialName("signature") val signature: String = "",
    @SerialName("timestamp") val timestamp: Long = 0,
    // args is read from the raw text, not through this field; kept so an encoded
    // frame from a test carries the same shape.
    @SerialName("args") val args: JsonObject? = null,
)

/** The frame type that carries a signed config, distinct from a command. */
const val TYPE_DEVICE_CONFIG = "device_config"

/** The frame the device sends to report a command's terminal outcome. */
@Serializable
data class CommandResultFrame(
    @SerialName("type") val type: String = "command_result",
    @SerialName("dispatchId") val dispatchId: String,
    @SerialName("success") val success: Boolean,
    @SerialName("output") val output: String = "",
)

/**
 * A telemetry frame. Fields mirror the server's `TelemetryFrame`; every one but
 * `type` is optional, because a device reports what it has.
 */
@Serializable
data class TelemetryFrame(
    @SerialName("type") val type: String = "telemetry",
    @SerialName("timestamp") val timestamp: Long = 0,
    @SerialName("deviceId") val deviceId: String = "",
    @SerialName("uptime") val uptime: Long = 0,
    @SerialName("riskScore") val riskScore: Int = 0,
    @SerialName("audioMode") val audioMode: Int = 0,
    @SerialName("bufferLevel") val bufferLevel: Int = 0,
    @SerialName("thermalTemp") val thermalTemp: Double = 0.0,
    @SerialName("speakerOn") val speakerOn: Boolean = false,
)

/** Reads just the `type` of an inbound frame, without decoding the rest. */
@Suppress("SwallowedException")
fun frameTypeOf(raw: String): String? =
    try {
        (CloudJson.json.parseToJsonElement(raw) as? JsonObject)
            ?.get("type")
            ?.let { (it as? JsonPrimitive)?.content }
    } catch (e: Exception) {
        // The caller treats null as "not a frame it recognises" and drops it; the
        // exception carries nothing the caller can act on.
        null
    }
