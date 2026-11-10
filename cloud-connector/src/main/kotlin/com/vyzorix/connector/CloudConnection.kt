package com.vyzorix.connector

import android.content.Context
import android.util.Log
import com.vyzorix.bridge.ApiRouter
import com.vyzorix.connector.bridge.IdentityApi
import com.vyzorix.connector.cloud.CloudError
import com.vyzorix.connector.cloud.CommandResultFrame
import com.vyzorix.connector.cloud.ConfigPoller
import com.vyzorix.connector.cloud.DeviceRequestSigner
import com.vyzorix.connector.cloud.DeviceWsClient
import com.vyzorix.connector.cloud.EnrollRequest
import com.vyzorix.connector.cloud.EnrollResponse
import com.vyzorix.connector.cloud.EnrollmentClient
import com.vyzorix.connector.command.CommandDispatcher
import com.vyzorix.connector.config.ConfigError
import com.vyzorix.connector.config.ConfigStore
import com.vyzorix.connector.deploy.ApplyResult
import com.vyzorix.connector.deploy.DeployApplier
import com.vyzorix.connector.deploy.DeployReporter
import com.vyzorix.connector.enroll.PairingCode
import com.vyzorix.connector.identity.DeviceFactsCollector
import com.vyzorix.connector.identity.DeviceSigner
import com.vyzorix.connector.telemetry.TelemetryReporter
import com.vyzorix.connector.terminal.TerminalRelay
import com.vyzorix.connector.update.UpdateChecker
import com.vyzorix.service.CloudLink
import com.vyzorix.service.DeviceNotification
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
 * The flow:
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
        /**
         * The public half of the secret-decryption key, for enroll, or empty when the
         * build has no secret key.
         */
        val secretKeyPem: String = "",
        val uuid: String,
        val appVersion: String,
        val enrollmentClient: EnrollmentClient,
        val configStore: ConfigStore,
        val wsClientFactory: (DeviceWsClient.Listener) -> DeviceWsClient,
        val dispatcherFactory: (deviceId: String) -> CommandDispatcher,
        val notification: DeviceNotification,
        // Supplies the telemetry readings. Built by the factory because it needs the
        // platform, but the cadence and the frame live here.
        val telemetrySource: TelemetryReporter.TelemetrySource,
        // The OTA flow, or null when the build has no updater (bridge-only, a test).
        // Run on connect: a device that has been offline checks as soon as it can
        // reach the server.
        val updateChecker: UpdateChecker? = null,
        /**
         * Applies a verified config's apps: fetch, verify, install, write the
         * registry. Null when the build has no deploy applier (a test, bridge-only).
         */
        val deployApplier: DeployApplier? = null,
        val tierHint: Int = DeviceFactsCollector.TIER_UNROOTED,
    )

    private val context = deps.context
    private val signer = deps.signer
    private val secretKeyPem = deps.secretKeyPem
    private val uuid = deps.uuid
    private val appVersion = deps.appVersion
    private val enrollmentClient = deps.enrollmentClient
    private val configStore = deps.configStore
    private val wsClientFactory = deps.wsClientFactory
    private val dispatcherFactory = deps.dispatcherFactory
    private val notification = deps.notification
    private val telemetrySource = deps.telemetrySource
    private val updateChecker = deps.updateChecker
    private val deployApplier = deps.deployApplier
    private val tierHint = deps.tierHint
    private val running = AtomicBoolean(false)
    private var wsClient: DeviceWsClient? = null
    private var dispatcher: CommandDispatcher? = null

    /** Reports telemetry while the socket is up. Null until the connection starts. */
    private var telemetry: TelemetryReporter? = null

    /**
     * Asks for the config on the enroll cadence while the device waits to be claimed.
     *
     * Null once a config has arrived, and until [ensureEnrolled] has a cadence to
     * give it. It is the fallback for the window where the socket is not up: a
     * claimed device whose push was missed must not sit unconfigured until something
     * else happens to trigger a fetch.
     */
    private var configPoller: ConfigPoller? = null

    /**
     * How often to ask for the config while waiting to be claimed, in seconds.
     *
     * Set from the enroll response; the default matches the server's when it does
     * not say. Used only until the device holds a config.
     */
    private var pollIntervalSeconds: Long = EnrollResponse.DEFAULT_POLL_INTERVAL_SECONDS

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
        // Stop reporting before the socket goes: a telemetry tick after close would
        // write into a closed socket.
        telemetry?.stop()
        telemetry = null
        // The poller's fetch needs the network and a signer; neither outlives the
        // service, so it is reaped with the rest rather than left ticking.
        configPoller?.stop()
        configPoller = null
        // Unlike a socket drop — which *detaches* sessions so their shells survive a
        // reconnect — an explicit stop reaps them. The service is going away, so the
        // port and its shells go with it; leaving a detached shell behind would leak
        // it past the lifetime of the thing that owns it.
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
                        secretKey = secretKeyPem,
                        model = facts.model,
                        manufacturer = facts.manufacturer,
                        osVersion = facts.osVersion,
                        apiLevel = facts.apiLevel,
                        appVersion = facts.appVersion,
                        deviceClass = facts.deviceClass,
                        buildId = facts.buildId,
                        securityPatch = facts.securityPatch,
                        supportedAbis = facts.supportedAbis,
                        ramBytes = facts.ramBytes,
                        storageBytes = facts.storageBytes,
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
        pollIntervalSeconds = response.pollIntervalSeconds
        notification.waiting(
            PairingCode
                .from(
                    code = response.pairingCode,
                    display = response.pairingDisplay,
                    expiresAt = response.expiresAt,
                    expiresInSeconds = response.expiresInSeconds,
                ).display,
        )

        // The code is shown; now wait to be claimed. The socket push is the primary
        // path, but a device whose socket is not up yet would otherwise sit
        // unconfigured after a claim, so the server's cadence is honoured here.
        startConfigPolling()
    }

    /**
     * Starts asking for the config on the cadence the enroll response gave.
     *
     * A no-op once a config is held: the poller is only for the unclaimed window,
     * and the socket push and the reconnect-time version check keep it current after
     * that. Idempotent.
     */
    private fun startConfigPolling() {
        if (configStore.current() != null) return
        if (configPoller != null) return

        val poller =
            ConfigPoller(
                intervalSeconds = pollIntervalSeconds,
                fetch = { fetchConfigOnce() },
            )
        configPoller = poller
        poller.start()
    }

    /**
     * Fetches the config once, applies it when it is new, and reports whether the
     * device now holds one. Used by the poller as its cadence tick.
     */
    private fun fetchConfigOnce(): Boolean {
        val requestSigner = wsRequestSigner() ?: return false

        return try {
            val response = enrollmentClient.fetchConfig(requestSigner)
            if (configStore.isStale(response.version)) {
                applyConfig(response.version, response.document)
            }

            // applyConfig stores it on success, so a config is held exactly when the
            // store reports one. A refused document leaves the store empty and the
            // poller keeps trying, which is what we want.
            configStore.current() != null
        } catch (e: CloudError) {
            // Offline, or not claimed yet. The next tick retries.
            Log.d(TAG, "config poll failed: ${e.message}")

            false
        }
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
                    // Reap any detached session that outlived its TTL while we were
                    // offline. A reconnect does not necessarily re-open every session
                    // (the operator may have closed the tab), so this is where the
                    // device's own bound is enforced rather than waiting for a re-OPEN
                    // that may never come.
                    terminalRelay?.let { relay ->
                        val reaped = relay.reapDetached()
                        if (reaped > 0) {
                            Log.i(TAG, "reaped $reaped detached terminal session(s) on connect")
                        }
                    }

                    // The socket is the trigger to reconcile the config: a device that
                    // was offline at claim time has none, and one whose config changed
                    // holds a stale version.
                    fetchConfigOnce()

                    // Report telemetry on this connection. Started here rather than at
                    // start() so a report is never attempted before there is a socket,
                    // and reported once immediately so a device that just came online is
                    // not invisible for a whole interval.
                    startTelemetry()

                    // Check for a newer build. On its own thread: the check is a quick
                    // request, but a download is tens of megabytes and must not stall
                    // the socket's reader. A device that is offline simply does not
                    // check; the next connect does.
                    updateChecker?.let { checker ->
                        Thread({ checker.runOnce() }, "vyzorix-update-check").apply {
                            isDaemon = true
                            start()
                        }
                    }
                }

                override fun onDisconnected(
                    reason: String,
                    willRetry: Boolean,
                ) {
                    // A terminal outlives the socket now. Rather than reaping every
                    // session, detach them: the shells keep running, their output is
                    // buffered up to a bound, and a reconnect re-opens the same session
                    // ids, which the relay re-binds to the surviving shells (see
                    // TerminalRelay.detachAll). A shell is only lost if the reconnect
                    // does not come before the detached TTL, or the service stops.
                    terminalRelay?.let { relay ->
                        Log.i(
                            TAG,
                            "socket dropped: detaching ${relay.openCount()} terminal session(s)",
                        )
                        relay.detachAll()
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
            notification.running(config.name)
            // A config has arrived, so the waiting is over: the socket push and the
            // reconnect-time version check own it from here.
            configPoller?.stop()
            configPoller = null
            // The config carries the reporting cadence, so applying it retunes the
            // telemetry reporter: the server can slow reporting across devices without a
            // new APK.
            telemetry?.updateInterval(config.heartbeatSeconds)
            Log.i(
                TAG,
                "applied config version $version (telemetry every ${config.heartbeatSeconds}s)",
            )

            applyApps(config)
        } catch (e: ConfigError) {
            Log.w(TAG, "refused a config: ${e.message}")
        }
    }

    /**
     * Applies the config's apps, and reports the outcome over the socket.
     *
     * A refusal is reported, not thrown: the config is verified and stored, so the
     * device stays configured with what it had, and the operator gets the reason
     * This runs off the frame-handling path — the fetch is a blocking
     * download — so it does not stall the socket.
     */
    private fun applyApps(config: com.vyzorix.connector.config.DeviceConfig) {
        val applier = deployApplier ?: return
        if (config.apps.isEmpty()) return

        Thread({
            val result =
                try {
                    applier.apply(config)
                } catch (
                    @Suppress("SwallowedException") e: Exception,
                ) {
                    // An unexpected failure (a rename that would not complete, a
                    // registry that could not be written) must still be reported: a
                    // silent apply thread is a deploy the operator never hears about.
                    Log.w(TAG, "deploy failed: ${e.message}")

                    ApplyResult.refused(config.version, "apply_failed", e.message.orEmpty())
                }

            if (!result.success) {
                Log.w(TAG, "deploy refused: ${result.reason} (${result.detail})")
                // The applier changed nothing, so restore the config the device is
                // actually running. The version then differs from the server's, so the
                // next reconnect re-fetches and retries.
                configStore.rollback()
            }
            sendText(DeployReporter.frame(result).toJson())
        }, "vyzorix-deploy-apply").apply { isDaemon = true }.start()
    }

    /**
     * Starts telemetry reporting on the current socket, reporting once immediately.
     *
     * Idempotent per connection: an `onConnected` after a reconnect restarts the
     * reporter against the new socket. The interval starts from the config if one is
     * held, so a reconnecting device reports at the operator's cadence from the
     * first tick.
     */
    private fun startTelemetry() {
        telemetry?.stop()

        val reporter =
            TelemetryReporter(
                source = telemetrySource,
                send = { frame -> wsClient?.sendText(frame) ?: false },
                deviceId = deviceId,
                initialIntervalSeconds =
                    configStore.current()?.heartbeatSeconds
                        ?: TelemetryReporter.DEFAULT_INTERVAL_SECONDS,
            )
        telemetry = reporter
        reporter.start()
    }

    /** The per-request signer for the config re-fetch. Null before a device id exists. */
    private fun wsRequestSigner(): DeviceRequestSigner? {
        if (deviceId.isEmpty()) return null

        return DeviceRequestSigner(
            signer = signer,
            deviceId = deviceId,
        )
    }
}
