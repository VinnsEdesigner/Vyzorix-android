package com.vyzorix.connector.deploy

import com.vyzorix.connector.command.AppControlClient

/**
 * The two supervisor operations a deploy needs. An interface so the applier can be
 * tested without a live control socket; the real implementation is a thin adapter
 * over [AppControlClient].
 */
interface SupervisorControl {
    /** True when the supervisor answers a ping. */
    fun ping(): Boolean

    /** Stops the app named [name]. */
    fun stop(name: String): Boolean
}

/** [SupervisorControl] over the supervisor's control socket. */
class ControlSocketSupervisor(
    private val control: AppControlClient,
) : SupervisorControl {
    override fun ping(): Boolean = control.call("ping").success

    override fun stop(name: String): Boolean = control.call("stop", mapOf("name" to name)).success
}
