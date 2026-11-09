package com.vyzorix.connector.config

/**
 * An artifact reference: `{url, sha256}`. The signature covers the digest, so the
 * artifact may be fetched over an untrusted transport. `sha256` is mandatory.
 */
data class ArtifactConfig(
    val name: String,
    /** `arm64-v8a`, `armeabi-v7a` or `x86_64`; null applies to every ABI. */
    val arch: String?,
    val url: String,
    val sha256: String,
    /** `dir` or `webroot`. */
    val kind: String,
)

/**
 * One app the config declares. [secretNames] holds the secret *names* the app
 * expects, never values — a value travels on its own channel, encrypted to the
 * device's key.
 */
data class AppConfig(
    val name: String,
    val dir: String,
    val cmd: String,
    val port: Int?,
    val autostart: Boolean,
    val expose: String,
    val purge: Boolean,
    val env: Map<String, String>,
    val secretNames: List<String>,
    val artifacts: List<ArtifactConfig>,
    val pwa: Boolean,
    val pwaOverrides: Map<String, String>,
)
