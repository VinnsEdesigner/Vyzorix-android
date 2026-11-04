package com.vyzorix.connector

import android.content.Context
import com.vyzorix.capability.PtyBroker
import com.vyzorix.connector.cloud.DeviceWsClient
import com.vyzorix.connector.cloud.EnrollmentClient
import com.vyzorix.connector.command.CommandDispatcher
import com.vyzorix.connector.command.ShellExecExecutor
import com.vyzorix.connector.config.ConfigStore
import com.vyzorix.connector.config.ConfigVerifier
import com.vyzorix.connector.enroll.PairingNotification
import com.vyzorix.connector.identity.DeviceSigner
import com.vyzorix.connector.identity.DeviceUuidStore
import com.vyzorix.connector.identity.KeystoreDeviceIdentity
import com.vyzorix.connector.telemetry.DeviceTelemetrySource
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Builds a [CloudConnection] from a URL and the pinned server key.
 *
 * This lives in the connector, not the app, so the app does not need OkHttp or the
 * format library on its classpath — it names only this factory and the
 * [com.vyzorix.service.CloudLink] it returns. That keeps the dependency direction
 * one-way: app -> connector -> core, with core unaware of both.
 */
object CloudConnectionFactory {
    /**
     * Builds the connection, or null when the configuration is unusable.
     *
     * A null return is a valid state: a build with no cloud runs over the bridge
     * alone. It is also what a bad pinned key yields, because a key that cannot be
     * parsed cannot be trusted and continuing unverified would be worse than not
     * connecting.
     */
    fun create(
        context: Context,
        baseUrl: String,
        serverPublicKeyPem: String,
        appVersion: String,
        tierHint: Int = 0,
    ): CloudConnection? {
        if (baseUrl.isEmpty() || serverPublicKeyPem.isEmpty()) return null

        val serverKey = parsePublicKey(serverPublicKeyPem) ?: return null

        val signer: DeviceSigner =
            KeystoreDeviceIdentity(KeyStore.getInstance(KeystoreDeviceIdentity.ANDROID_KEYSTORE))
        val uuid = DeviceUuidStore(FileUuidStorage(context)).uuid()
        // One client for both HTTP and the WebSocket, with the WebSocket heartbeat
        // enabled: OkHttp's ping fails a silently dead link so the reconnect loop
        // runs, rather than the device writing into a black hole after a NAT rebind.
        val http = DeviceWsClient.buildHttpClient()

        val enrollmentClient =
            EnrollmentClient(
                baseUrl = baseUrl,
                http = http,
                deviceIdProvider = { uuid },
            )

        return CloudConnection(
            CloudConnection.Deps(
                context = context,
                signer = signer,
                uuid = uuid,
                appVersion = appVersion,
                enrollmentClient = enrollmentClient,
                configStore = ConfigStore(FileConfigStorage(context), ConfigVerifier(serverKey)),
                wsClientFactory = { listener ->
                    DeviceWsClient(
                        baseUrl = baseUrl,
                        deviceId = uuid,
                        signer = signer,
                        http = http,
                        listener = listener,
                    )
                },
                dispatcherFactory = { deviceId ->
                    CommandDispatcher(
                        deviceId = deviceId,
                        serverPublicKey = serverKey,
                        executors = mapOf("SHELL_EXEC" to ShellExecExecutor(PtyBroker())),
                    )
                },
                notification = PairingNotification(context),
                // Telemetry needs the platform (battery, uptime), so the source is built
                // here; the cadence and the frame live in the connection.
                telemetrySource = DeviceTelemetrySource(context),
                tierHint = tierHint,
            ),
        )
    }

    /**
     * Parses the pinned server public key: PEM SubjectPublicKeyInfo on P-256.
     *
     * Returns null on any failure. The key is the device's only trust anchor for
     * what the server sends, so an unparseable one disables the connection rather
     * than leaving it unverified.
     */
    @Suppress("SwallowedException") // An unparseable pinned key disables the connection.
    fun parsePublicKey(pem: String): PublicKey? =
        try {
            val body =
                pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace(Regex("\\s"), "")

            KeyFactory
                .getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
        } catch (t: Throwable) {
            null
        }

    /** File-backed uuid storage: the identity must survive a restart. */
    private class FileUuidStorage(
        context: Context,
    ) : com.vyzorix.connector.identity.UuidStorage {
        private val file = java.io.File(context.filesDir, "device.uuid")

        override fun read(): String? = if (file.exists()) file.readText().trim() else null

        override fun write(uuid: String) {
            file.writeText(uuid)
        }
    }

    /** File-backed config storage: the last verified document, kept verbatim. */
    private class FileConfigStorage(
        context: Context,
    ) : com.vyzorix.connector.config.ConfigStorage {
        private val file = java.io.File(context.filesDir, "vyzorix.v")

        override fun read(): String? = if (file.exists()) file.readText() else null

        override fun write(document: String) {
            file.writeText(document)
        }

        override fun clear() {
            file.delete()
        }
    }
}
