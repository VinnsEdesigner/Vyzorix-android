package com.vyzorix.connector.command

/**
 * Runs one command type and returns its result.
 *
 * An executor is deliberately small: it is handed the command's args and returns
 * what happened. It does not verify signatures (the dispatcher does, before it is
 * called) and it does not know about the WebSocket.
 */
fun interface CommandExecutor {
    fun execute(args: String): CommandOutcome
}

/** What an executor reports. */
data class CommandOutcome(
    val success: Boolean,
    val output: String,
) {
    companion object {
        fun ok(output: String): CommandOutcome = CommandOutcome(true, output)

        fun failed(reason: String): CommandOutcome = CommandOutcome(false, reason)
    }
}
