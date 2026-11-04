package com.vyzorix.service

import com.vyzorix.bridge.ApiRouter

/**
 * The cloud connection, as the foreground service sees it.
 *
 * The service owns exactly one of these — the architecture's "one WS owner" — but
 * it does not construct it: the implementation lives in `cloud-connector`, which
 * depends on `android-core`, so the dependency cannot point back. The app builds
 * the concrete connection and installs it here; the service only starts and stops
 * it.
 *
 * This keeps the layering honest: `android-core` never learns about HTTP, the
 * WebSocket or the vyzorix.v format, and the cloud code never reaches into the
 * service's internals.
 */
interface CloudLink {
    /**
     * Starts the connection: enroll if needed, then hold the socket. Idempotent.
     * Called once the service is up.
     */
    fun start()

    /** Stops the connection and releases the socket. Called from `onDestroy`. */
    fun stop()

    /**
     * Registers any bridge APIs the connection provides.
     *
     * The connection owns the device identity, so it is the one that can expose it
     * over `android.sock`; registering here keeps the service's router unaware of
     * where the identity comes from.
     */
    fun registerApis(router: ApiRouter)

    /** The device id once enrolled, or empty. Used for logging. */
    fun deviceId(): String = ""
}

/**
 * Where the app installs the [CloudLink] before starting the service.
 *
 * A static holder rather than a constructor argument because the service is started
 * by the system from an `Intent`, with no opportunity to inject one. The app sets
 * it in `Application.onCreate`, which always runs first.
 *
 * Null is the default and a valid state: a build with no cloud configuration (a
 * test, or a device used only over the bridge) simply has no connection.
 */
object CloudLinkProvider {
    @Volatile
    private var link: CloudLink? = null

    fun install(link: CloudLink?) {
        this.link = link
    }

    fun get(): CloudLink? = link
}
