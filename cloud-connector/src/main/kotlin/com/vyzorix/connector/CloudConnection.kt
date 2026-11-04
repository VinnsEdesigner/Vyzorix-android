package com.vyzorix.connector

import android.content.Context
import android.util.Log
import com.vyzorix.bridge.ApiRouter
import com.vyzorix.connector.bridge.IdentityApi
import com.vyzorix.connector.cloud.CloudError
import com.vyzorix.connector.cloud.CloudJson
import com.vyzorix.connector.cloud.CommandResultFrame
import com.vyzorix.connector.cloud.DeviceWsClient
import com.vyzorix.connector.cloud.EnrollRequest
import com.vyzorix.connector.cloud.EnrollmentClient
import com.vyzorix.connector.command.CommandDispatcher
import com.vyzorix.connector.config.ConfigError
import com.vyzorix.connector.config.ConfigStore
import com.vyzorix.connector.enroll.PairingCode
import com.vyzorix.connector.enroll.PairingNotification
import com.vyzorix.connector.identity.DeviceFactsCollector
import com.vyzorix.connector.identity.DeviceSigner
import com.vyzorix.service.CloudLink
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString

/**
 * The cloud connection, end to end: enroll, hold the WebSocket, apply the config,
 * dispatch commands.
 *
 * This is the object the foreground service owns — the "one WS owner" the
 * architecture requires. Everything below it is a pure component with its own
 * tests; this wires them and holds the lifecycle.
 *
 * The flow, from REGISTRATION_CONTRACT.md §3:
 *  1. On first run, generate the keypair (in the signer) and enroll. The server
 *     returns a pairing code; show it in a notification.
 *  2. Hold the WebSocket open. The operator claims the code at the web page; the
 *     server pushes the signed config over this socket.
 *  3. Verify the config with the pinned server key, apply it, update the
 *     notification. If the device was offline at claim time, re-fetch by version.
 *  4. Commands arrive on the same socket: verify, run, report.
 */
class CloudConnection(
    private val deps: Deps,
) : CloudLink {
    /**
     * Everything the connection needs, as one object.
     *
     * Grouped rather than listed on the constructor so the parameter count stays
     * sane and the factory can build it field by field.
     */
    data class Deps(
        val context: Context,
        val signer: DeviceSigner,
        val uuid: String,
        val appVersion: String,
        val enrollmentClient: EnrollmentClient,
        val configStore: ConfigStore,
        val wsClientFactory: (DeviceWsClient.Listener) -> DeviceWsClient,
        val dispatcherFactory: (deviceId: String) -> CommandDispatcher,
        val notification: PairingNotification,
        val tierHint: Int = DeviceFactsCollector.TIER_UNROOTED,
    )

    private val context = deps.context
    private val signer = deps.signer
    private val uuid = deps.uuid
    private val appVersion = deps.appVersion
    private val enrollmentClient = deps.enrollmentClient
    private val configStore = deps.configStore
    private val wsClientFactory = deps.wsClientFactory
    private val dispatcherFactory = deps.dispatcherFactory
    private val notification = deps.notification
    private val tierHint = deps.tierHint
    private val running = AtomicBoolean(false)
    private var wsClient: DeviceWsClient? = null
    private var dispatcher: CommandDispatcher? = null
    private var deviceId: String = ""

    /**
     * Starts the connection: enroll if needed, then open the socket.
     *
     * Idempotent. Called by the service on first boot and on every start; a device
     * that is already enrolled skips straight to the socket.
     */
    override fun start() {
        if (!running.compareAndSet(false, true)) return

        ensureEnrolled()
        openSocket()
    }

    /** Stops the connection. The service calls this from `onDestroy`. */
    override fun stop() {
        running.set(false)
        wsClient?.close()
        wsClient = null
    }

    /** Sends a command result over the socket. */
    fun sendResult(result: CommandResultFrame): Boolean {
        val client = wsClient ?: return false

        return client.sendText(CloudJson.json.encodeToString(result))
    }

    /** Sends a binary PTY frame. */
    fun sendBinary(bytes: ByteArray): Boolean = wsClient?.sendBinary(bytes) ?: false

    /** Sends a text frame (telemetry, for example). */
    fun sendText(text: String): Boolean = wsClient?.sendText(text) ?: false

    /** The device id, once known. Empty before enroll completes. */
    override fun deviceId(): String = deviceId

    /**
     * Registers the identity API, so the container can read the device's uuid and
     * public key over the bridge.
     *
     * The connector owns both values, which is why the API is registered from here
     * rather than built by the service.
     */
    override fun registerApis(router: ApiRouter) {
        val identity =
            IdentityApi(
                uuidProvider = { uuid },
                publicKeyProvider = { signer.publicKeyPem() },
            )
        router.register(IdentityApi.NAME, identity)
    }

    private companion object {
        const val TAG = "CloudConnection"
    }

    @Suppress("SwallowedException")
    private fun ensureEnrolled() {
        // The server's enroll is idempotent for a pending device, so a device that
        // lost its code simply gets a fresh one. A device that is already claimed
        // returns 409, which means onboarding is done and there is nothing to show.
        val facts =
            DeviceFactsCollector.collect(
                context = context,
                deviceUuid = uuid,
                appVersion = appVersion,
                tierHint = tierHint,
            )

        val response =
            try {
                enrollmentClient.enroll(
                    EnrollRequest(
                        deviceUuid = facts.deviceUuid,
                        publicKey = signer.publicKeyPem(),
                        model = facts.model,
                        manufacturer = facts.manufacturer,
                        osVersion = facts.osVersion,
                        appVersion = facts.appVersion,
                        deviceClass = facts.deviceClass,
                        tierHint = facts.tierHint,
                    ),
                )
            } catch (e: CloudError) {
                if (e.isAlreadyClaimed) {
                    // Onboarding is done: no code to show, go straight to the socket.
                    Log.i(TAG, "device is already claimed; skipping the pairing code")

                    return
                }

                // A rate limit or a server fault is worth retrying; a validation error
                // is not, and retrying it would only repeat it.
                Log.i(TAG, "enroll did not complete (retryable=${e.isRetryable}): ${e.message}")

                return
            }

        deviceId = response.deviceId
        notification.show(
            PairingCode.from(
                code = response.pairingCode,
                display = response.pairingDisplay,
                expiresAt = response.expiresAt,
                expiresInSeconds = response.expiresInSeconds,
            ),
        )
    }

    private fun openSocket() {
        val listener =
            object : DeviceWsClient.Listener {
                override fun onTextFrame(raw: String) {
                    handleTextFrame(raw)
                }

                override fun onPtyFrame(bytes: ByteArray) {
                    // Interactive PTY over the cloud socket is a later slice; the
                    // operator terminal is served by the bridge today. Binary frames
                    // are ignored here rather than mis-decoded.
                }

                override fun onConnected() {
                    // The socket is the trigger to reconcile the config: a device that
                    // was offline at claim time has none, and one whose config changed
                    // holds a stale version.
                    refetchConfigIfStale()
                }

                override fun onDisconnected(
                    reason: String,
                    willRetry: Boolean,
                ) {
                    // Nothing to do: the client schedules its own reconnect. Kept as a
                    // seam for a future liveness signal.
                }
            }

        wsClient = wsClientFactory(listener)
        dispatcher = dispatcherFactory(deviceId)
        wsClient?.connect()
    }

    private fun handleTextFrame(raw: String) {
        val handled = dispatcher?.handle(raw) ?: return

        when (handled) {
            is CommandDispatcher.Handled.Result -> sendResult(handled.result)
            is CommandDispatcher.Handled.Config -> applyConfig(handled.version, handled.document)
            is CommandDispatcher.Handled.Dropped -> {
                // A refused frame is dropped; the dispatcher has already logged the
                // reason. Nothing goes back to the server for a frame it did not send.
            }
        }
    }

    // A config that does not verify is refused and the previous one kept: the device
    // stays configured with what it had, and because the version stays stale the next
    // reconnect re-fetches.
    @Suppress("SwallowedException")
    private fun applyConfig(
        version: Int,
        document: String,
    ) {
        try {
            val config = configStore.apply(document)
            notification.showConfigured(config.name)
            Log.i(TAG, "applied config version $version")
        } catch (e: ConfigError) {
            Log.w(TAG, "refused a config: ${e.message}")
        }
    }

    /**
     * Re-fetches the config when the server's version differs from the held one.
     *
     * This is what makes a missed push recoverable: the device asks for its own
     * config by version, and applies it if it is new. The version the server reports
     * comes from the config frame itself, so a device that has none always asks.
     */
    @Suppress("SwallowedException")
    private fun refetchConfigIfStale() {
        // The version to compare against is only known once a config frame arrives or
        // a fetch returns one. Asking unconditionally on connect is correct and cheap:
        // the server returns the same document if nothing changed, and applying an
        // identical version is a no-op.
        val requestSigner = wsRequestSigner() ?: return

        try {
            val response = enrollmentClient.fetchConfig(requestSigner)
            if (configStore.isStale(response.version)) {
                applyConfig(response.version, response.document)
            }
        } catch (e: CloudError) {
            // Network or auth failure: the next reconnect tries again. Nothing to do
            // here, and the version stays stale so the retry is not skipped.
            Log.d(TAG, "config re-fetch failed: ${e.message}")
        }
    }

    /** The per-request signer for the config re-fetch. Null before a device id exists. */
    private fun wsRequestSigner(): com.vyzorix.connector.cloud.DeviceRequestSigner? {
        if (deviceId.isEmpty()) return null

        return com.vyzorix.connector.cloud.DeviceRequestSigner(
            signer = signer,
            deviceId = deviceId,
        )
    }
}
