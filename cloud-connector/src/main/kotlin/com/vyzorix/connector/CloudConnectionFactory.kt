package com.vyzorix.connector

import android.content.Context
import com.vyzorix.capability.PtyBroker
import com.vyzorix.connector.cloud.DeviceRequestSigner
import com.vyzorix.connector.cloud.DeviceWsClient
import com.vyzorix.connector.cloud.EnrollmentClient
import com.vyzorix.connector.cloud.NetworkMonitor
import com.vyzorix.connector.command.AppCommands
import com.vyzorix.connector.command.AppControlClient
import com.vyzorix.connector.command.CommandDispatcher
import com.vyzorix.connector.command.ShellExecExecutor
import com.vyzorix.connector.config.ConfigStore
import com.vyzorix.connector.config.ConfigVerifier
import com.vyzorix.connector.config.PinnedServerKeys
import com.vyzorix.connector.deploy.AppRegistryWriter
import com.vyzorix.connector.deploy.ArtifactFetcher
import com.vyzorix.connector.deploy.ControlSocketSupervisor
import com.vyzorix.connector.deploy.DeployApplier
import com.vyzorix.connector.deploy.FileExposeTokens
import com.vyzorix.connector.identity.DeviceSigner
import com.vyzorix.connector.identity.DeviceUuidStore
import com.vyzorix.connector.identity.KeystoreDeviceIdentity
import com.vyzorix.connector.secret.HttpSecretTransport
import com.vyzorix.connector.secret.SecretKey
import com.vyzorix.connector.secret.SecretStore
import com.vyzorix.connector.telemetry.DeviceTelemetrySource
import com.vyzorix.connector.update.ApkInstaller
import com.vyzorix.connector.update.UpdateChecker
import com.vyzorix.connector.update.UpdateClient
import com.vyzorix.service.DeviceNotification
import com.vyzorix.service.UpdateMarker
import java.io.File
import java.security.KeyStore
import okhttp3.OkHttpClient

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
        /** The build's versionCode; the update check compares the server's against it. */
        appVersionCode: Int,
        /** The install step. Supplied by the app: it needs an Activity and a provider. */
        apkInstaller: ApkInstaller,
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
        // The second Keystore key, for decrypting an app secret. Separate from the
        // identity key and never exposed over the bridge.
        val secretKey = SecretKey(KeyStore.getInstance(SecretKey.ANDROID_KEYSTORE))
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
                                // The the Vyzorix CLI app lifecycle. The supervisor lives in the
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
                notification = DeviceNotification(context),
                // Telemetry needs the platform (battery, uptime), so the source is built
                // here; the cadence and the frame live in the connection.
                telemetrySource = DeviceTelemetrySource(context),
                // The OTA flow. Staged under files/updates, which is the one path the
                // FileProvider exposes to the installer (see app's file_paths.xml).
                //
                // The callbacks drive the notification through the update: a progress
                // bar while it downloads, "downloaded, opening the installer", and —
                // after the install restarts the app — "reconnecting". The last state
                // is posted here rather than from the app's own startup, because only
                // this process knows an update was in flight.
                updateChecker =
                    UpdateChecker(
                        client =
                            UpdateClient(
                                baseUrl = baseUrl,
                                http = http,
                                // The report is keyed by device id; the uuid is known
                                // here (it is the enrollment identity).
                                deviceId = { uuid },
                            ),
                        installer = apkInstaller,
                        currentVersionCode = appVersionCode,
                        stagingDir = File(context.filesDir, "updates"),
                        onProgress = { check, percent ->
                            DeviceNotification(context).downloadingUpdate(check.version, percent)
                        },
                        onDownloaded = { DeviceNotification(context).updateDownloaded(it.version) },
                        onInstalling = { check ->
                            // Write the marker before the install, so the process the
                            // install starts can show "reconnecting" — this process is
                            // about to be replaced and cannot post that state itself.
                            UpdateMarker.mark(context, check.version)
                            DeviceNotification(context).applyingUpdate(check.version)
                        },
                        // A confirmed "no update available" means the offered install
                        // landed: the device now runs the newer build, so the Accept
                        // install prompt (and its button) is no longer needed. Until
                        // this fires, the prompt stays, so a cancelled install can be
                        // retried.
                        onUpToDate = { DeviceNotification(context).clearInstall() },
                    ),
                secretKeyPem = secretKey.publicKeyPem(),
                deployApplier = deployApplier(context, baseUrl, http, uuid, signer, secretKey),
                tierHint = tierHint,
                // The notification's network line is only as good as this: a validated
                // internet connection, read live, so "Connection stable" is not claimed
                // over a captive-portal Wi-Fi that reaches nothing.
                connectivityUp = { NetworkMonitor.isUp(context) },
            ),
        )
    }

    /**
     * The deploy applier: fetch, verify and install a config's apps, and write the
     * supervisor's registry.
     *
     * Everything it touches lives under `files/apps` — the host path the proot
     * launcher binds to `/home/apps` in the container — so the install renames stay on
     * one filesystem and land where the supervisor reads them.
     */
    private fun deployApplier(
        context: Context,
        baseUrl: String,
        http: OkHttpClient,
        uuid: String,
        signer: DeviceSigner,
        secretKey: SecretKey,
    ): DeployApplier {
        val appsRoot = File(context.filesDir, APPS_DIR)
        val signerForDevice = DeviceRequestSigner(signer, uuid)

        return DeployApplier(
            fetcher = ArtifactFetcher(http),
            registry = AppRegistryWriter(),
            supervisor = ControlSocketSupervisor(appControl(context)),
            stagingDir = File(appsRoot, STAGING_DIR),
            secrets =
                SecretStore(
                    key = secretKey,
                    transport =
                        HttpSecretTransport(
                            baseUrl = baseUrl,
                            http = http,
                            deviceId = uuid,
                            requestSigner = signerForDevice,
                        ),
                ),
            // App-private, NOT under files/apps: that subdirectory is bound to
            // /home/apps and readable by every app in the container.
            exposeTokens = FileExposeTokens(File(context.filesDir, "expose-tokens")),
        )
    }

    /**
     * A client for the the Vyzorix CLI supervisor's control socket.
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

    /** Scratch space for downloads and unpacking, inside the app-storage directory. */
    private const val STAGING_DIR = ".deploy-staging"

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

    /**
     * File-backed config storage: the current document and the previous known-good
     * one, both kept verbatim (`keep_last = 2`). Two files rather than a
     * directory of versions: the recovery path that matters is "put back what worked",
     * one prior version answers it, and disk is the scarce resource on a phone.
     */
    private class FileConfigStorage(
        context: Context,
    ) : com.vyzorix.connector.config.ConfigStorage {
        private val file = java.io.File(context.filesDir, "vyzorix.v")
        private val lastGood = java.io.File(context.filesDir, "vyzorix.last-good.v")

        override fun read(): String? = if (file.exists()) file.readText() else null

        override fun write(document: String) {
            file.writeText(document)
        }

        override fun readLastGood(): String? = if (lastGood.exists()) lastGood.readText() else null

        override fun writeLastGood(document: String) {
            lastGood.writeText(document)
        }

        override fun clear() {
            file.delete()
            lastGood.delete()
        }
    }
}
