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
import com.vyzorix.bridge.ApiCatalogue
import com.vyzorix.bridge.ApiRouter
import com.vyzorix.bridge.SessionTokenRegistry
import com.vyzorix.bridge.apis.SessionApi
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

    /** The one-shot install thread, kept so `onDestroy` can join it. */
    private var installThread: Thread? = null

    /** The Layer 3 supervisor process, when it is running. */
    private var supervisor: Process? = null

    /** How many times the supervisor has been restarted, for the backoff. */
    @Volatile
    private var supervisorRestarts = 0

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

        // Install the container's userspace if it is not already there, before
        // anything asks whether the engine is ready. Off the main thread: it extracts
        // ~145 MB on first boot, and `start()` runs from `onStartCommand`.
        //
        // A failure is not fatal — the device runs bridge-only — so this does not
        // block the bridge or the cloud connection below. The watchdog's
        // `isEngineReady` gate then keeps the terminal closed until it succeeds, and
        // the next boot retries.
        installThread =
            Thread({
                // RootfsBootstrap pairs the installer with the build's EngineConfig,
                // so the wiring lives in one place rather than here. Idempotent: on a
                // device that already has the rootfs this is three filesystem checks.
                val ready = RootfsBootstrap.ensureInstalled(this)
                Log.i(TAG, "rootfs install finished; engine ready=$ready")

                // The extended tier (`git`, `python3`, `tmux`) is fetched on the same
                // thread but after the core tier, and only once the engine is ready:
                // it is ~108 MB over the network, so it must not delay the core
                // install, and there is no point fetching tooling into a userspace
                // that is not there. A failure here is logged and ignored — the
                // engine works without it — and the next boot retries.
                if (ready) {
                    // The Layer 3 binaries go in before the extended tier: the
                    // supervisor is what runs apps, and it is small and bundled, so
                    // it must not wait on a 108 MB download.
                    val layer3 = RootfsBootstrap.ensureLayer3(this)
                    Log.i(TAG, "layer 3 install finished; supervisor=$layer3")

                    if (layer3) {
                        startSupervisor()
                    }

                    val extended = RootfsBootstrap.ensureExtended(this)
                    Log.i(TAG, "extended tier install finished; installed=$extended")
                }
            }, "vyzorix-rootfs-install").apply {
                isDaemon = true
                start()
            }

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
    private fun terminalShellRequest(
        sessionId: String,
        token: String,
    ): PtyRequest =
        if (ProotLauncher.isReady(this)) {
            // Wrap in tmux when it is available so a dropped WebSocket reattaches to
            // the same shell instead of losing it (§6.3). The session name is the
            // terminal's own id, so a reconnect with the same id attaches and a second
            // terminal gets its own. `hasTmux` gates it because tmux lives in the
            // extended tier, which is fetched on demand — a terminal opened before it
            // lands gets a plain shell, which is the correct degradation.
            val tmux =
                if (ProotLauncher.hasTmux(this)) {
                    TMUX_SESSION_PREFIX + sessionId
                } else {
                    null
                }

            ProotLauncher.loginShellRequest(this, token, tmuxSession = tmux)
        } else {
            ProotLauncher.uncontainedShellRequest(this, token)
        }

    /**
     * Starts the Layer 3 supervisor inside the container, and relays its log.
     *
     * Run as a **plain child process**, not a PTY session: the supervisor is a
     * daemon, and giving it a terminal would only add a reader whose output goes
     * nowhere. Its stderr is captured on a daemon thread and written to logcat, so
     * `logcat` shows supervisor output alongside the service's own.
     *
     * A failure is logged, not fatal: a device whose supervisor did not start still
     * has the engine, the bridge and the terminal. The next boot retries.
     */
    private fun startSupervisor() {
        if (!ProotLauncher.hasSupervisor(this)) {
            Log.w(TAG, "supervisor binary is not installed; not starting it")

            return
        }

        try {
            val builder =
                ProcessBuilder(ProotLauncher.supervisorArgs(this))
                    .redirectErrorStream(true)
                    // The proot launcher reads PROOT_TMP_DIR from its own
                    // environment, before it execs anything.
                    .apply {
                        environment()[ProotLauncher.TMP_DIR_ENV] =
                            ProotLauncher.tmpDir(this@VyzorixForegroundService).absolutePath
                    }

            val process = builder.start()
            supervisor = process

            // One thread does both jobs — relay the log, and watch for the exit — so
            // there is no window where the process has died but nothing noticed.
            //
            // A restart loop is required, not defensive: the supervisor is a child of
            // the app process, and Android's phantom-process killer reaps children
            // (Android 12+, ~32 concurrent) without the app's involvement. The
            // supervisor prints "ready" and is gone seconds later, with no tombstone
            // and no exit log, which is what that looks like from inside. The app
            // cannot prevent the kill, so it notices and starts it again.
            Thread({ watchSupervisor(process) }, "vyzorix-supervisor").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "supervisor started")
        } catch (t: Throwable) {
            Log.e(TAG, "could not start the supervisor", t)
        }
    }

    /**
     * Relays the supervisor's output to logcat, then restarts it when it exits.
     *
     * The restart is bounded by a backoff so a supervisor that cannot start — a
     * corrupt binary, a missing bind — does not become a tight fork loop. It stops
     * once the service itself is stopping, because a shutdown must not spawn one.
     */
    private fun watchSupervisor(process: Process) {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                Log.i("VyzorixSupervisor", line)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "supervisor log relay ended: ${t.message}")
        }

        val code =
            try {
                process.waitFor()
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()

                return
            }

        Log.w(TAG, "supervisor exited (code $code)")

        if (!running.get()) {
            // The service is going down; do not start another one.
            return
        }

        supervisorRestarts++
        if (supervisorRestarts > MAX_SUPERVISOR_RESTARTS) {
            // Give up loudly rather than spin. The engine, the bridge and the
            // terminal all still work; only apps are unavailable.
            Log.e(TAG, "supervisor exited $supervisorRestarts times; not restarting it")

            return
        }

        // Back off, so a supervisor that dies immediately does not fork in a loop.
        val delayMs =
            minOf(SUPERVISOR_BACKOFF_STEP_MS * supervisorRestarts, SUPERVISOR_BACKOFF_MAX_MS)
        try {
            Thread.sleep(delayMs)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()

            return
        }

        if (running.get()) {
            startSupervisor()
        }
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

        // Every read-only API comes from the catalogue, so the registration list is a
        // value the catalog test and `apis.txt` can both read rather than a chain of
        // calls nothing else can see. `session` is the one exception: it needs this
        // service's session table, so it is constructed with callbacks.
        return ApiCatalogue
            .registerAll(ApiRouter(permissions = PermissionRequester(this)))
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

        // The install is I/O, so it is not interruptible mid-write; joining briefly
        // is better than leaving a half-written rootfs for the next boot to clear.
        installThread?.let { thread ->
            runCatching { thread.join(INSTALL_JOIN_TIMEOUT_MS) }
        }
        installThread = null

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

        /**
         * Prefix for a terminal's tmux session name.
         *
         * Prefixed so a container session is recognisable in `tmux ls` and
         * cannot collide with a name an operator picks by hand.
         */
        private const val TMUX_SESSION_PREFIX = "vyzorix-"

        /**
         * How many times the supervisor is restarted before giving up.
         *
         * The phantom-process killer can reap it repeatedly, so a few restarts are
         * normal; an unbounded loop would be a fork bomb.
         */
        private const val MAX_SUPERVISOR_RESTARTS = 50

        /** The supervisor restart backoff: linear, from one step up to a ceiling. */
        private const val SUPERVISOR_BACKOFF_STEP_MS = 1000L
        private const val SUPERVISOR_BACKOFF_MAX_MS = 30_000L
        private const val CHANNEL_ID = "vyzorix-engine"
        private const val NOTIFICATION_ID = 1
        private const val SHUTDOWN_TIMEOUT_MS = 3000L

        /**
         * How long `onDestroy` waits for a rootfs install to finish.
         *
         * Short on purpose: extraction takes seconds, and blocking service teardown on
         * it would trade a rare half-written rootfs for a guaranteed slow shutdown.
         * The next boot clears and retries either way.
         */
        private const val INSTALL_JOIN_TIMEOUT_MS = 2000L

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
