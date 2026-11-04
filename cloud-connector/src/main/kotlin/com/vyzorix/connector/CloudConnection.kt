package com.vyzorix.connector

import android.content.Context
import android.util.Log
import com.vyzorix.bridge.ApiRouter
import com.vyzorix.connector.bridge.IdentityApi
import com.vyzorix.connector.cloud.CloudError
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
import com.vyzorix.connector.terminal.TerminalRelay
import com.vyzorix.service.CloudLink
import com.vyzorix.service.PtySessionPort
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * The device's id as the server knows it.
     *
     * It is the uuid the device generated and enrolled with — the server stores the
     * uuid as the device id — so it is known from the first run, before any network
     * call. That matters for the config re-fetch: a device that is already claimed
     * must still be able to ask for its config, and it needs this id to sign the
     * request.
     */
    private var deviceId: String = uuid

    /** The terminal relay, built once a [PtySessionPort] is attached. */
    private var terminalRelay: TerminalRelay? = null

    /**
     * Starts the connection: enroll if needed, then open the socket.
     *
     * Idempotent. Called by the service on first boot and on every start; a device
     * that is already enrolled skips straight to the socket.
     *
     * The work runs on a background thread because [start] is called from the
     * service's `onStartCommand`, which is the main thread: enroll does a blocking
     * HTTP call, and OkHttp refuses to run on the main thread
     * (`NetworkOnMainThreadException`). Doing it here rather than making every
     * caller remember keeps the one-correct-way rule.
     */
    override fun start() {
        if (!running.compareAndSet(false, true)) return

        Thread({
            if (!running.get()) return@Thread

            ensureEnrolled()

            if (!running.get()) return@Thread

            openSocket()
        }, "vyzorix-cloud-start").apply { isDaemon = true }.start()
    }

    /** Stops the connection. The service calls this from `onDestroy`. */
    override fun stop() {
        running.set(false)
        // A terminal cannot outlive the socket it runs over: close every session so
        // no shell is left running with no way to reach it.
        terminalRelay?.let { relay ->
            Log.i(TAG, "stopping: reaping ${relay.openCount()} terminal session(s)")
            relay.closeAll("connection_stopped")
        }
        wsClient?.close()
        wsClient = null
    }

    /** Sends a command result over the socket. */
    fun sendResult(result: CommandResultFrame): Boolean {
        val client = wsClient ?: return false

        return client.sendText(result.toJson())
    }

    /** Sends a binary PTY frame. */
    fun sendBinary(bytes: ByteArray): Boolean = wsClient?.sendBinary(bytes) ?: false

    /** Sends a text frame (telemetry, for example). */
    fun sendText(text: String): Boolean = wsClient?.sendText(text) ?: false

    /** The device id, once known. Empty before enroll completes. */
    override fun deviceId(): String = deviceId

    /**
     * Attaches the PTY side, so operator terminal frames on the cloud socket reach
     * the device's shells.
     *
     * The relay is built here because this is where the socket lives, but the shells
     * are the service's; the port is the only thing it needs from it.
     */
    override fun attachPtyPort(port: PtySessionPort) {
        terminalRelay =
            TerminalRelay(
                port = port,
                sendFrame = { frame -> sendBinary(frame) },
                onSessionClosed = { sessionId, reason ->
                    Log.i(TAG, "terminal $sessionId ended ($reason)")
                },
            )
    }

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
            } catch (
                @Suppress("SwallowedException") e: IOException,
            ) {
                // Any transport failure — DNS, TLS, a blocked cleartext request — must
                // not escape: this runs inside the foreground service's start path, so
                // an uncaught exception takes the whole service down and the device
                // stops answering commands. The socket's reconnect loop retries.
                Log.w(TAG, "enroll failed at the transport: ${e.message}")

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
                    val relay = terminalRelay
                    if (relay == null) {
                        Log.w(TAG, "a binary PTY frame arrived with no port attached; dropped")

                        return
                    }

                    if (!relay.onFrame(bytes)) {
                        Log.w(TAG, "dropped a malformed PTY frame")
                    }
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
                    // A terminal cannot outlive the socket: close every session so no
                    // shell is left running with no way to reach it. The relay re-opens
                    // sessions only when the operator asks again, which is correct —
                    // the operator's own terminal shows the disconnect.
                    terminalRelay?.let { relay ->
                        Log.i(
                            TAG,
                            "socket dropped: reaping ${relay.openCount()} terminal session(s)",
                        )
                        relay.closeAll("socket_disconnected")
                    }
                }
            }

        wsClient = wsClientFactory(listener)
        dispatcher = dispatcherFactory(deviceId)
        wsClient?.connect()
    }

    private fun handleTextFrame(raw: String) {
        val dispatcher =
            this.dispatcher ?: run {
                Log.w(TAG, "a frame arrived before the dispatcher existed; dropped")

                return
            }

        val handled = dispatcher.handle(raw)

        when (handled) {
            is CommandDispatcher.Handled.Result -> {
                // Report the outcome; if the socket is gone the send is lost, and the
                // device's HTTP fallback (POST .../command/:id/result) is what a real
                // deployment uses to close that gap.
                val sent = sendResult(handled.result)
                Log.i(
                    TAG,
                    "command ${handled.result.dispatchId} finished " +
                        "(success=${handled.result.success}), result sent=$sent",
                )
            }
            is CommandDispatcher.Handled.Config -> applyConfig(handled.version, handled.document)
            is CommandDispatcher.Handled.Dropped -> {
                // A refused frame is dropped. The reason is logged here rather than in
                // the dispatcher, which has no logger: a silently dropped frame is
                // impossible to diagnose from a device, and a config frame that failed
                // verification must leave a trace.
                Log.w(TAG, "dropped a frame: ${handled.reason}")
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
