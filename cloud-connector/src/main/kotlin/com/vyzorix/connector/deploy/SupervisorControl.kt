package com.vyzorix.connector.deploy

import com.vyzorix.connector.command.AppControlClient

/**
 * The supervisor operations a deploy needs. An interface so the applier can be tested
 * without a live control socket; the real implementation is a thin adapter over
 * [AppControlClient].
 */
interface SupervisorControl {
    /** True when the supervisor answers a ping. */
    fun ping(): Boolean

    /** Stops the app named [name]. */
    fun stop(name: String): Boolean

    /**
     * Exposes [name] on [bind]. A non-loopback bind requires [token]; the supervisor
     * refuses `0.0.0.0` without one (Layer 3 §4.4 P2, fail closed).
     */
    fun expose(
        name: String,
        bind: String,
        token: String,
    ): Boolean

    /** Removes [name]'s exposure. */
    fun unexpose(name: String): Boolean
}

/** [SupervisorControl] over the supervisor's control socket. */
class ControlSocketSupervisor(
    private val control: AppControlClient,
) : SupervisorControl {
    override fun ping(): Boolean = control.call("ping").success

    override fun stop(name: String): Boolean = control.call("stop", mapOf("name" to name)).success

    override fun expose(
        name: String,
        bind: String,
        token: String,
    ): Boolean =
        control.call("expose", mapOf("name" to name, "bind" to bind, "token" to token)).success

    override fun unexpose(name: String): Boolean =
        control.call("unexpose", mapOf("name" to name)).success
}
