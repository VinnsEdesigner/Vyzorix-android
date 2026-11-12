package com.vyzorix.service

import android.app.Notification
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
import com.vyzorix.bridge.apis.BluetoothControlApi
import com.vyzorix.bridge.apis.SessionApi
import com.vyzorix.bridge.apis.SmsReceivedApi
import com.vyzorix.bridge.apis.UsbAttachApi
import com.vyzorix.bridge.apis.UsbDetachApi
import com.vyzorix.bridge.apis.UsbStateApi
import com.vyzorix.capability.PtyBroker
import com.vyzorix.capability.PtyError
import com.vyzorix.capability.PtyReader
import com.vyzorix.capability.PtyRequest
import com.vyzorix.capability.PtyResult
import com.vyzorix.capability.usb.AndroidUsbHost
import com.vyzorix.capability.usb.UsbBroker
import com.vyzorix.container.ProotLauncher
import com.vyzorix.permissions.PermissionRequester
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The foreground service. It owns every layer: the wake lock that keeps the
 * process schedulable, the bridge socket, the PTY broker and its readers, and the
 * container.
 *
 * Everything lives here because there is exactly one of each: one socket, one
 * container, one place that decides when to restart. A second owner would mean two
 * copies of that state.
 *
 * `START_STICKY` so the system restarts it after a kill. Child processes are not
 * phantom processes precisely because they belong to this service, which is why
 * the container runs under it rather than as a detached daemon.
 *
 * On the phantom-process killer (Android 12+, ~32 concurrent children): it is not
 * a targetSdk-gated behaviour and there is no public or hidden `ActivityManager`
 * API to disable it — `setPhantomProcessList` does not exist, so a reflection call
 * for it would throw and silently do nothing. The lever is the global setting
 * `settings_enable_monitor_phantom_procs`, writable only from shell/root or via
 * Shizuku (documented future work). Until then the bound is respected by keeping
 * the PTY session cap low, and the rule stands: the OEM killer,
 * not the phantom killer, is the survival risk.
 */
class VyzorixForegroundService : Service() {

    private val running = AtomicBoolean(false)
    private lateinit var tokens: SessionTokenRegistry
    private lateinit var router: ApiRouter
    private lateinit var broker: PtyBroker
    private lateinit var usbBroker: UsbBroker
    private lateinit var smsReceived: SmsReceivedApi
    private lateinit var bluetoothControl: BluetoothControlApi
    private lateinit var bridge: AndroidSocketServer
    private lateinit var watchdog: ServiceWatchdog
    private var cloudLink: CloudLink? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Live PTY readers, keyed by channel. The reader owns the fd and closes it. */
    private val readers = ConcurrentHashMap<Int, PtyReader>()

    /**
     * The port an operator's terminal is opened through.
     *
     * Exposed so a caller that is **in this process** — an instrumented test, or a
     * future local client — can drive the production terminal path instead of
     * building its own. That matters because the token is minted here, from this
     * service's registry: a caller that mints its own gets a token the bridge has
     * never seen and every request is refused. Keeping the port private made the
     * container terminal untestable from the outside without duplicating the wiring.
     */
    @Volatile
    var terminalPort: PtySessionPort? = null
        private set

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
        startForeground(DeviceNotification.NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // The notification's "Start Vyzorix" button sends this action. It arrives
        // as a foreground-service start, so the service is already being promoted; the
        // action itself needs no special handling — the fact that onStartCommand ran is
        // the start. It is logged so the path is visible.
        if (intent?.action == ACTION_START) {
            Log.i(TAG, "start requested from the notification")
        }

        // Re-post the foreground notification on every start, not just in onCreate.
        // The system requires every startForegroundService() to be answered by a
        // startForeground() call, and onCreate runs only once per service lifetime:
        // a second start (a boot receiver racing the app, or a caller starting an
        // already-running service) was therefore never answered, and the process was
        // killed with "did not then call Service.startForeground()".
        startForeground(DeviceNotification.NOTIFICATION_ID, buildNotification())

        if (running.compareAndSet(false, true)) {
            instance = this
            start()
        }

        return START_STICKY
    }

    private fun start() {
        tokens = SessionTokenRegistry()
        broker = PtyBroker()
        usbBroker = UsbBroker(AndroidUsbHost(this))
        smsReceived = SmsReceivedApi()
        bluetoothControl = BluetoothControlApi()
        // Register the receiver before the router serves `sms_received`, or the API
        // reports `listening: false` for a device that is in fact watching nothing.
        // Idempotent, so a service restart does not stack receivers.
        smsReceived.start(this)
        // The discovery receiver must be registered before the router serves
        // bluetooth_control, or a scan finds nothing. Idempotent.
        bluetoothControl.start(this)
        router = buildRouter()

        // Install the container's userspace if it is not already there, before
        // anything asks whether the container is ready. Off the main thread: it extracts
        // ~145 MB on first boot, and `start()` runs from `onStartCommand`.
        //
        // A failure is not fatal — the device runs bridge-only — so this does not
        // block the bridge or the cloud connection below. The watchdog's
        // `isContainerReady` gate then keeps the terminal closed until it succeeds, and
        // the next boot retries.
        installThread =
            Thread({
                // The bundled pieces are unpacked first and the supervisor started, so the
                // shell is usable in seconds. The extended tier (~108 MB, optional) is
                // fetched **after** that, so a slow download never gates the container.
                val notification = DeviceNotification(this)
                var ready = false

                try {
                    // The core rootfs is bundled in the APK, so this is an unpack, not a
                    // download — fast, and it cannot fail on the network.
                    notification.preparingContainer("unpacking its userspace")
                    ready = RootfsBootstrap.ensureInstalled(this)
                    Log.i(TAG, "rootfs install finished; container ready=$ready")

                    if (ready) {
                        // Layer 3 is bundled too. The supervisor is started as soon as it
                        // is in place, because `ProotLauncher.hasSupervisor()` is the
                        // signal everything else uses to mean "the tree is ready".
                        notification.preparingContainer("unpacking the app runtime")
                        val layer3 = RootfsBootstrap.ensureLayer3(this)
                        Log.i(TAG, "layer 3 install finished; supervisor=$layer3")

                        if (layer3) {
                            startSupervisor()
                        }
                    }
                } finally {
                    // The shell is up (or the bundled unpack failed). Either way nothing
                    // more is *waited on*, so the notification comes down now; the
                    // extended fetch below runs without one.
                    notification.clearPreparingContainer()
                }

                // **The extended tier is fetched last, and that is the point.**
                //
                // It is `git`, `python3`, `tmux` — ~108 MB, optional, and the only part
                // that needs the network. Starting the supervisor before it means a fresh
                // install has a working shell in seconds instead of after the download,
                // which on a slow link is minutes. Its extraction writes over files in the
                // same tree (`libc.so.6`, `ld-linux`) — safe because `TarGz.writeFile`
                // extracts atomically (temp file + rename), so a process that already has
                // one mapped keeps the old inode and only a new exec sees the new one. A
                // failure here leaves the container fully usable, just without the optional
                // tooling; the next boot retries.
                if (ready) {
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
                // The binding: a token must be for this device. A provider, not a
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
                isContainerReady = { ProotLauncher.isReady(this) },
                onContainerLost = { Log.w(TAG, "container unavailable; will retry") },
            ).also { it.start() }

        // The cloud connection, if the app installed one. Started after the bridge so
        // the APIs it registers are ready before a frame can arrive, and after the
        // broker exists so the terminal port can be handed over.
        //
        // The port is built and published **unconditionally**, before the null check:
        // `cloudLink?.attachPtyPort(buildTerminalPort())` never built it when there was
        // no cloud link, because a safe call short-circuits its arguments. In
        // bridge-only mode — which is how a device runs until it is enrolled, and how
        // the instrumented tests run — the terminal path was therefore unavailable to
        // any in-process caller, even though the container, the broker and the bridge were
        // all up.
        cloudLink = CloudLinkProvider.get()
        cloudLink?.registerApis(router)
        val port = buildTerminalPort()
        cloudLink?.attachPtyPort(port)
        cloudLink?.start()

        Log.i(TAG, "service started")
    }

    /**
     * Builds the PTY port the cloud terminal relay drives.
     *
     * The shell is the same proot login shell the bridge's terminal uses, so an
     * operator's cloud terminal and a local one are the same environment. The
     * allowlist is the full local API set, stated explicitly rather than left empty,
     * because an empty allowlist permits nothing.
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
        ).also { terminalPort = it }

    /**
     * The shell a terminal session runs.
     *
     * The container's login shell when the container is installed, which is the
     * architecture's terminal. When it is not — the rootfs asset has not shipped yet
     * — a plain device shell is used
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
            // the same shell instead of losing it. The session name is the
            // terminal's own id, so a reconnect with the same id attaches and a second
            // terminal gets its own.
            //
            // **Disabled on device — see [TMUX_REATTACH_ENABLED].** The code is kept,
            // and gated rather than deleted, so the fix has somewhere to land and the
            // reason lives beside it.
            val tmux =
                if (TMUX_REATTACH_ENABLED && ProotLauncher.hasTmux(this)) {
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
     * has the container, the bridge and the terminal. The next boot retries.
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
                    // The proot launcher reads PROOT_TMP_DIR (and, on API <= 28,
                    // PROOT_NO_SECCOMP) from its own environment, before it execs
                    // anything.
                    .apply {
                        ProotLauncher.prootEnv(this@VyzorixForegroundService).forEach { entry ->
                            val cut = entry.indexOf('=')
                            environment()[entry.substring(0, cut)] = entry.substring(cut + 1)
                        }
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
            // Give up loudly rather than spin. The container, the bridge and the
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
        // The USB trio needs the broker that owns the open device descriptors, so
        // it is constructed here like `session` rather than being a catalogue
        // singleton. Registering them makes usb_attach/usb_detach reachable and
        // keeps `ApiCatalogue.names` (and so `apis.txt`) honest about them.
        return ApiCatalogue
            .registerAll(ApiRouter(permissions = PermissionRequester(this)))
            .register(SessionApi.NAME, sessionApi)
            .register(UsbStateApi.NAME, UsbStateApi(usbBroker))
            .register(UsbAttachApi.NAME, UsbAttachApi(usbBroker))
            .register(UsbDetachApi.NAME, UsbDetachApi(usbBroker))
            .register(SmsReceivedApi.NAME, smsReceived)
            .register(BluetoothControlApi.NAME, bluetoothControl)
    }

    /**
     * Opens a PTY session, launches the container on it, and starts its reader.
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
     * fail-closed rule. The server does not resolve a
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
        // Unregister the SMS receiver: a receiver outliving the service would leak
        // the context it was registered against, and the count it feeds no longer
        // has a reader.
        if (::smsReceived.isInitialized) smsReceived.stop(this)
        if (::bluetoothControl.isInitialized) bluetoothControl.stop(this)
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

        // The port holds the broker and the token registry, both of which this
        // service owns; clearing it keeps a stale reference from outliving them.
        terminalPort = null
        instance = null

        releaseWakeLock()

        // The container is stopping, so the persistent notification returns to the
        // "Start Vyzorix" state: the service's foreground notification goes
        // away with it, and this re-posts the one the operator can tap to start
        // again. Without it, stopping the container left no way back from the
        // notification.
        DeviceNotification(this).needsStart()

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
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:container").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * The foreground notification, in the "starting" state.
     *
     * It is deliberately *not* "running": this is posted the instant the service is
     * promoted, which is before enrollment has been attempted, and claiming "running"
     * there hid a stalled enrollment behind a reassuring label. The cloud connection
     * moves the same notification on to "waiting" (pairing code) and then "running"
     * (claimed) once those are actually true.
     *
     * It shares [DeviceNotification.NOTIFICATION_ID], so the service's notification
     * and the state machine's are the same object — one Vyzorix notification.
     */
    private fun buildNotification(): Notification {
        // A process that came up because an update was installed shows the "updating /
        // reconnecting" state rather than a bare "Starting…", so the operator sees the
        // update land. The marker is consumed here; the cloud connection overwrites
        // this with "running" once it reconnects.
        val applying = UpdateMarker.applyingVersion(this)
        if (applying != null) {
            UpdateMarker.clear(this)

            return DeviceNotification(this).applyingUpdateNotification(applying)
        }

        return DeviceNotification(this).startingNotification()
    }

    companion object {
        private const val TAG = "VyzorixService"

        /**
         * The running service, or null.
         *
         * A static reference to a service is normally a leak, and this one is
         * deliberately narrow: it is written in `onStartCommand`, cleared in
         * `onDestroy`, and read only by an instrumented test that is in the same
         * process. Its purpose is to let a test open a terminal through
         * [terminalPort] — the production path, with the production token — instead
         * of minting a token the bridge has never seen.
         */
        @Volatile
        private var instance: VyzorixForegroundService? = null

        /** The terminal port of the running service, or null when it is not up. */
        fun terminalPortOrNull(): PtySessionPort? = instance?.terminalPort

        /**
         * Prefix for a terminal's tmux session name.
         *
         * Prefixed so a container session is recognisable in `tmux ls` and
         * cannot collide with a name an operator picks by hand.
         */
        private const val TMUX_SESSION_PREFIX = "vyzorix-"

        /**
         * Whether a terminal's shell is wrapped in tmux for reattach. **Off.**
         *
         * A dropped WebSocket should reattach to the same shell, and
         * `tmux new-session -A` is how. It does not work on a device, and it fails in
         * a way that takes the whole terminal with it, so it is disabled here rather
         * than deleted — the fix has a place to land.
         *
         * The cause is SELinux, not tmux. tmux's *client* resolves its own tty name,
         * which reads the `devpts` directory, and Android's `untrusted_app` domain is
         * denied that read:
         *
         *     avc: denied { read } for comm="tmux: client" name="/" dev="devpts"
         *     scontext=u:r:untrusted_app:… tcontext=u:object_r:devpts:s0 tclass=dir
         *
         * The client then exits without attaching, and the operator gets a terminal
         * that echoes keystrokes and answers nothing. A bare `bash --login` never
         * resolves its tty name, which is why the identical PTY works without tmux.
         * Verified on the emulator: with tmux every API call returns nothing; without
         * it, every API answers. Nothing an app can do grants that read, so restoring
         * reattach needs a sepolicy change (not available to us) or a mechanism that
         * does not resolve a tty name.
         *
         * `loginShellArgs(tmuxSession=…)` remains unit-tested and correct — including
         * the `TMUX_TMPDIR` fix for proot's trailing-slash `realpath` — so flipping
         * this back on is the only change reattach needs once the tty read is solved.
         */
        private const val TMUX_REATTACH_ENABLED = false

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

        /**
         * The action the "Start Vyzorix" notification button sends.
         *
         * The button starts *this service*, never an activity: the agent has no UI
         * and must never be opened as an app.
         */
        const val ACTION_START = "com.vyzorix.service.action.START"
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
