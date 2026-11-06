package com.vyzorix.connector

import android.content.Context
import com.vyzorix.capability.PtyBroker
import com.vyzorix.connector.cloud.DeviceWsClient
import com.vyzorix.connector.cloud.EnrollmentClient
import com.vyzorix.connector.command.AppCommands
import com.vyzorix.connector.command.AppControlClient
import com.vyzorix.connector.command.CommandDispatcher
import com.vyzorix.connector.command.ShellExecExecutor
import com.vyzorix.connector.config.ConfigStore
import com.vyzorix.connector.config.ConfigVerifier
import com.vyzorix.connector.config.PinnedServerKeys
import com.vyzorix.connector.enroll.PairingNotification
import com.vyzorix.connector.identity.DeviceSigner
import com.vyzorix.connector.identity.DeviceUuidStore
import com.vyzorix.connector.identity.KeystoreDeviceIdentity
import com.vyzorix.connector.telemetry.DeviceTelemetrySource
import java.io.File
import java.security.KeyStore

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
     * Builds the connection, or null when there is no cloud configuration.
     *
     * A null return is a deliberate state: a build with no cloud runs over the bridge
     * alone. It happens only when the base URL or the pinned key is *empty* — not
     * when they are malformed. An anchor that will not parse throws
     * [PinnedKeyError], because a device that cannot verify what the server sends
     * must not present itself as configured; the caller decides how loud to be.
     */
    fun create(
        context: Context,
        baseUrl: String,
        serverPublicKeyPem: String,
        appVersion: String,
        tierHint: Int = 0,
    ): CloudConnection? {
        if (baseUrl.isEmpty() || serverPublicKeyPem.isEmpty()) return null

        // The anchor is a *set* of keys, so a rotation can overlap. A malformed anchor
        // throws rather than returning null: "no anchor configured" is a deliberate
        // bridge-only build, while "an anchor that will not parse" is a fault, and the
        // two must not look the same. See PinnedServerKeys.
        val serverKeys = PinnedServerKeys.parse(serverPublicKeyPem)

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
                configStore = ConfigStore(FileConfigStorage(context), ConfigVerifier(serverKeys)),
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
                        pinnedServerKeys = serverKeys,
                        executors =
                            mapOf(
                                "SHELL_EXEC" to ShellExecExecutor(PtyBroker()),
                                // The Layer 3 app lifecycle. The supervisor lives in the
                                // container and is reached over its control socket; the
                                // path is the host side of the `/home/apps` bind the
                                // proot launcher installs, so it is `files/apps/...` here
                                // and `/home/apps/...` inside.
                                "APP_START" to AppCommands.Start(appControl(context)),
                                "APP_STOP" to AppCommands.Stop(appControl(context)),
                                "APP_RESTART" to AppCommands.Restart(appControl(context)),
                            ),
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
     * A client for the Layer 3 supervisor's control socket.
     *
     * `files/apps/supervisor.sock` is the host path of the container's
     * `/home/apps/supervisor.sock`: the proot launcher binds `files/apps` to
     * `/home/apps`, so the two names are the same file. Using the host path means the
     * connector needs no knowledge of the container at all.
     */
    private fun appControl(context: Context): AppControlClient =
        AppControlClient(File(context.filesDir, "$APPS_DIR/$SUPERVISOR_SOCKET"))

    /** Must match `ProotLauncher.APPS_DIR` — the app-storage directory. */
    private const val APPS_DIR = "apps"

    /** Must match the supervisor's socket name inside that directory. */
    private const val SUPERVISOR_SOCKET = "supervisor.sock"

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
