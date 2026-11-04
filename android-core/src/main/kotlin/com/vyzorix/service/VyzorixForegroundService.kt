package com.vyzorix.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.vyzorix.bridge.AndroidSocketServer
import com.vyzorix.bridge.ApiRouter
import com.vyzorix.bridge.SessionTokenRegistry
import com.vyzorix.bridge.apis.BatteryApi
import com.vyzorix.bridge.apis.DeviceInfoApi
import com.vyzorix.bridge.apis.LogcatApi
import com.vyzorix.bridge.apis.NetworkApi
import com.vyzorix.bridge.apis.SessionApi
import com.vyzorix.bridge.apis.StorageApi
import com.vyzorix.capability.PtyBroker
import com.vyzorix.capability.PtyError
import com.vyzorix.capability.PtyReader
import com.vyzorix.capability.PtyRequest
import com.vyzorix.capability.PtyResult
import com.vyzorix.engine.ProotLauncher
import com.vyzorix.permissions.PermissionRequester
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The foreground service. It owns every layer: the wake lock that keeps the
 * process schedulable, the bridge socket, the PTY broker and its readers, and the
 * engine.
 *
 * Everything lives here because there is exactly one of each: one socket, one
 * engine, one place that decides when to restart. A second owner would mean two
 * copies of that state.
 *
 * `START_STICKY` so the system restarts it after a kill. Child processes are not
 * phantom processes precisely because they belong to this service, which is why
 * the engine runs under it rather than as a detached daemon.
 *
 * On the phantom-process killer (Android 12+, ~32 concurrent children): it is not
 * a targetSdk-gated behaviour and there is no public or hidden `ActivityManager`
 * API to disable it — `setPhantomProcessList` does not exist, so a reflection call
 * for it would throw and silently do nothing. The lever is the global setting
 * `settings_enable_monitor_phantom_procs`, writable only from shell/root or via
 * Shizuku (documented future work). Until then the bound is respected by keeping
 * the PTY session cap low, and the architecture doc's §1.4 stands: the OEM killer,
 * not the phantom killer, is the survival risk.
 */
class VyzorixForegroundService : Service() {

    private val running = AtomicBoolean(false)
    private lateinit var tokens: SessionTokenRegistry
    private lateinit var router: ApiRouter
    private lateinit var broker: PtyBroker
    private lateinit var bridge: AndroidSocketServer
    private lateinit var watchdog: ServiceWatchdog
    private var cloudLink: CloudLink? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Live PTY readers, keyed by channel. The reader owns the fd and closes it. */
    private val readers = ConcurrentHashMap<Int, PtyReader>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Re-post the foreground notification on every start, not just in onCreate.
        // The system requires every startForegroundService() to be answered by a
        // startForeground() call, and onCreate runs only once per service lifetime:
        // a second start (a boot receiver racing the app, or a caller starting an
        // already-running service) was therefore never answered, and the process was
        // killed with "did not then call Service.startForeground()".
        startForeground(NOTIFICATION_ID, buildNotification())

        if (running.compareAndSet(false, true)) {
            start()
        }

        return START_STICKY
    }

    private fun start() {
        tokens = SessionTokenRegistry()
        broker = PtyBroker()
        router = buildRouter()

        bridge =
            AndroidSocketServer(
                context = this,
                tokens = tokens,
                router = router,
                onPtyInput = ::onPtyInput,
                onPtyCredit = ::onPtyCredit,
                channelOwnedBy = broker::channelBelongsToSession,
                // The §9 binding: a token must be for this device. A provider, not a
                // value, because enrollment finishes after this server is built — the
                // device id is empty until then, and the server binds it from the
                // moment it arrives. Same shape as buildTerminalPort's deviceId.
                selfDeviceId = { cloudLink?.deviceId()?.takeIf { it.isNotEmpty() } },
            )

        if (!bridge.start()) {
            Log.e(TAG, "bridge failed to start; service is up but unreachable")
        }

        watchdog =
            ServiceWatchdog(
                isEngineReady = { ProotLauncher.isReady(this) },
                onEngineLost = { Log.w(TAG, "engine unavailable; will retry") },
            ).also { it.start() }

        // The cloud connection, if the app installed one. Started after the bridge so
        // the APIs it registers are ready before a frame can arrive, and after the
        // broker exists so the terminal port can be handed over.
        cloudLink = CloudLinkProvider.get()
        cloudLink?.registerApis(router)
        cloudLink?.attachPtyPort(buildTerminalPort())
        cloudLink?.start()

        Log.i(TAG, "service started")
    }

    /**
     * Builds the PTY port the cloud terminal relay drives.
     *
     * The shell is the same proot login shell the bridge's terminal uses, so an
     * operator's cloud terminal and a local one are the same environment. The
     * allowlist is the full local API set, stated explicitly rather than left empty,
     * because an empty allowlist permits nothing (boundary contract §7).
     */
    private fun buildTerminalPort(): PtySessionPort =
        ServicePtySessionPort(
            broker = broker,
            tokens = tokens,
            // A provider, not a value: the device id is not known until enrollment
            // finishes, which is after this port is built.
            deviceId = { cloudLink?.deviceId().orEmpty() },
            apiAllowlist = { router.names().toSet() },
            shellRequest = ::terminalShellRequest,
            // The container shell cannot start until the rootfs is installed; the
            // port refuses rather than opening a pty for a shell that dies at once.
            canOpen = {
                ProotLauncher.isReady(this) || TerminalShellPolicy.isUncontainedShellAllowed()
            },
        )

    /**
     * The shell a terminal session runs.
     *
     * The container's login shell when the engine is installed, which is the
     * architecture's terminal. When it is not — the rootfs asset has not shipped yet
     * (see `ANDROID_SERVICE_ARCHITECTURE.md` §6.1.1) — a plain device shell is used
     * **only** if [TerminalShellPolicy] has explicitly allowed it, because that shell
     * runs as the app's uid and can read the app's private files, which the container
     * exists to prevent.
     */
    private fun terminalShellRequest(token: String): PtyRequest =
        if (ProotLauncher.isReady(this)) {
            ProotLauncher.loginShellRequest(this, token)
        } else {
            ProotLauncher.uncontainedShellRequest(this, token)
        }

    /**
     * Registers every API. Adding one is a line here plus a file in `apis/`.
     *
     * The session API needs the service's session table, so it is constructed with
     * the two callbacks rather than registered as a singleton object.
     */
    private fun buildRouter(): ApiRouter {
        val sessionApi =
            SessionApi(
                openSession = ::openSession,
                closeSession = ::closeSession,
                resizeSession = ::resizeSession,
                channelOwnedBy = broker::channelBelongsToSession,
            )

        return ApiRouter(permissions = PermissionRequester(this))
            .register(BatteryApi.NAME, BatteryApi)
            .register(DeviceInfoApi.NAME, DeviceInfoApi)
            .register(StorageApi.NAME, StorageApi)
            .register(NetworkApi.NAME, NetworkApi)
            .register(LogcatApi.NAME, LogcatApi)
            .register(SessionApi.NAME, sessionApi)
    }

    /**
     * Opens a PTY session, launches the engine on it, and starts its reader.
     * Returns the channel number, which is what the client uses on subsequent
     * frames.
     *
     * The token is issued here and handed to the child through its environment, so
     * the container can reach the bridge and be identified. The request is built
     * after the token exists for exactly that reason.
     *
     * The session's allowlist is the full local API set, stated explicitly: a shell
     * session is an operator's terminal and may call every bridge API. It is spelled
     * out rather than left empty because an empty allowlist permits nothing — the
     * fail-closed rule from the boundary contract (§7). The server does not resolve a
     * per-session allowlist yet; when it does, it replaces this set.
     */
    fun openSession(
        sessionId: String,
        deviceId: String,
        operatorId: String,
    ): PtyResult<Int> {
        val token =
            tokens.issue(
                sessionId = sessionId,
                deviceId = deviceId,
                operatorId = operatorId,
                apiAllowlist = router.names().toSet(),
            )

        val opened =
            broker.open(
                sessionId,
                deviceId,
                operatorId,
                ProotLauncher.loginShellRequest(this, token.token),
            )
        if (opened is PtyResult.Err) return opened

        val channel = (opened as PtyResult.Ok).value
        val session =
            broker.sessionForChannel(channel)
                ?: return PtyResult.Err(
                    PtyError(PtyError.Kind.INTERNAL, "channel $channel vanished"),
                )

        val reader =
            PtyReader(
                session = session,
                onOutput = { bytes -> bridge.pushPtyOutput(channel, sessionId, bytes) },
                onExit = { code -> onSessionExit(channel, code) },
            )

        readers[channel] = reader
        reader.start()

        return PtyResult.Ok(channel)
    }

    /** Closes a session. The reader observes the flag and closes the fd. */
    fun closeSession(channel: Int): PtyResult<Unit> {
        val reader =
            readers.remove(channel)
                ?: return PtyResult.Err(
                    PtyError(PtyError.Kind.NO_SUCH_SESSION, "no channel $channel"),
                )

        val closed = broker.close(channel)
        if (closed is PtyResult.Ok) {
            tokens.revokeSession(closed.value.id)
        }

        // The reader closes the fd; wait so the caller knows it is gone.
        reader.awaitStopped()

        return PtyResult.Ok(Unit)
    }

    /** Applies a terminal size to a live session. */
    fun resizeSession(
        channel: Int,
        rows: Int,
        cols: Int,
    ): PtyResult<Unit> {
        val session =
            broker.sessionForChannel(channel)
                ?: return PtyResult.Err(
                    PtyError(PtyError.Kind.NO_SUCH_SESSION, "no channel $channel"),
                )

        return session.resize(rows, cols)
    }

    /**
     * The reader reports its session ended. Its token is revoked so a shell that
     * exited cannot be re-driven, and a lifecycle event is pushed so a client can
     * tell a dead shell from a quiet one.
     */
    private fun onSessionExit(
        channel: Int,
        exitCode: Int,
    ) {
        readers.remove(channel)
        val session = broker.sessionForChannel(channel)
        if (session != null) {
            broker.close(channel)
            tokens.revokeSession(session.id)
        }
        bridge.pushEvent(EVENT_SESSION_EXIT, "{\"channel\":$channel,\"exit\":$exitCode}")
        Log.i(TAG, "session channel $channel exited with $exitCode")
    }

    private fun onPtyInput(
        channel: Int,
        bytes: ByteArray,
    ) {
        val session = broker.sessionForChannel(channel) ?: return
        session.write(bytes)
    }

    private fun onPtyCredit(
        channel: Int,
        bytes: Int,
    ) {
        readers[channel]?.grantCredit(bytes)
    }

    override fun onDestroy() {
        running.set(false)

        // Close every session so each reader releases its fd. Clearing `readers`
        // without closing the broker would leak one descriptor per live session.
        readers.keys.toList().forEach { channel ->
            broker.close(channel)
            readers.remove(channel)?.awaitStopped(SHUTDOWN_TIMEOUT_MS)
        }
        readers.clear()

        if (::bridge.isInitialized) bridge.stop()
        if (::tokens.isInitialized) tokens.sweep()
        if (::watchdog.isInitialized) watchdog.stop()
        cloudLink?.stop()
        cloudLink = null

        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        // Held for the service's lifetime, with no timeout, and that is the point:
        // the agent must stay schedulable to answer a command or keep a shell
        // alive. It is released in onDestroy, and a PARTIAL lock does not keep the
        // screen on. Lint's WakelockTimeout warning is suppressed with that reason
        // rather than silenced globally.
        @Suppress("WakelockTimeout")
        wakeLock =
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:engine").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Vyzorix engine",
                    NotificationManager.IMPORTANCE_LOW,
                )
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("Vyzorix")
            .setContentText("Device engine running")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VyzorixService"
        private const val CHANNEL_ID = "vyzorix-engine"
        private const val NOTIFICATION_ID = 1
        private const val SHUTDOWN_TIMEOUT_MS = 3000L

        /** Event topic pushed when a PTY session's child exits. */
        const val EVENT_SESSION_EXIT = "session.exit"

        fun start(context: Context) {
            val intent = Intent(context, VyzorixForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
