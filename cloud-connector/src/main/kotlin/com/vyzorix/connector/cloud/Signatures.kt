package com.vyzorix.connector.cloud

/**
 * The canonical strings the device signs and verifies, mirrored from the server
 * byte for byte.
 *
 * These are the whole of the device/server contract that can silently break: a
 * signature is only valid if both sides build the identical string, and nothing
 * about a mismatch is visible until a device is refused. Each form is therefore
 * one function here, used by both signing and verification, and each is covered
 * by a test that reproduces a server-produced fixture.
 *
 * The server's counterparts are in
 * `apps/api/internal/infrastructure/crypto/{command_signer,device_signing}.go`.
 * The domain prefixes (`connect`, `command`, `SERVER`) keep the three forms
 * mutually non-replayable.
 */
object Signatures {
    /**
     * What the device signs for the WebSocket connect handshake.
     *
     * The server's `ConnectCanonical`. It travels as query parameters, because an
     * upgrade request cannot carry the signature headers, so the signature is
     * base64url (see [encodeSignature]) — a `+` in a query string decodes as a
     * space.
     */
    fun connect(
        deviceId: String,
        timestampMs: String,
        nonce: String,
    ): String = "connect|$deviceId|$timestampMs|$nonce"

    /**
     * What the server signs for a command or a config frame, and what the device
     * verifies. The server's `CommandCanonical`.
     *
     * [args] must be the **exact bytes** the frame carried, not a re-serialised
     * form: the signature covers those bytes, and a JSON library that normalises
     * key order or whitespace would produce a different string. [RawJson] exists
     * to hand back that exact substring.
     *
     * An empty dispatch id is normal for a config frame — it is not a dispatched
     * command — and the server signs the empty string, so this must too.
     */
    fun command(
        dispatchId: String,
        deviceId: String,
        command: String,
        timestampMs: Long,
        nonce: String,
        args: String,
    ): String {
        val argsText = args.ifEmpty { "{}" }

        return "command|$dispatchId|$deviceId|$command|$timestampMs|$nonce|$argsText"
    }

    /**
     * What the device signs for an HTTP request. The server's
     * `DeviceCanonicalString`.
     *
     * [bodyHashBase64] is standard base64 of the SHA-256 of the request body, as
     * the server computes it.
     */
    fun deviceRequest(
        method: String,
        path: String,
        deviceId: String,
        timestampMs: String,
        nonce: String,
        bodyHashBase64: String,
    ): String =
        buildString {
            append(method.uppercase())
            append('\n')
            append(path)
            append('\n')
            append(deviceId)
            append('\n')
            append(timestampMs)
            append('\n')
            append(nonce)
            append('\n')
            append(bodyHashBase64)
        }
}
