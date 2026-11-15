package com.vyzorix.connector.command

/**
 * The `APP_*` command executors.
 *
 * Each is a thin translation from a cloud command to a supervisor control call. The
 * work lives in the supervisor; these exist so the command plane can reach it.
 *
 * ## Why every one of them reports rather than swallows
 *
 * A device that receives a command it does not
 * implement must report a **failed** result, so the user learns it did not run
 * instead of watching it sit `delivered` forever. The same applies to a command that
 * is implemented but could not be carried out — a missing app name, an unreachable
 * supervisor — and these executors therefore always return an outcome, never a
 * silent success.
 */
object AppCommands {
    /** `APP_START` — start a named app. */
    class Start(
        private val client: AppControlClient,
    ) : CommandExecutor {
        override fun execute(args: String): CommandOutcome = run(client, args, "start")
    }

    /** `APP_STOP` — stop a named app. */
    class Stop(
        private val client: AppControlClient,
    ) : CommandExecutor {
        override fun execute(args: String): CommandOutcome = run(client, args, "stop")
    }

    /** `APP_RESTART` — stop then start. */
    class Restart(
        private val client: AppControlClient,
    ) : CommandExecutor {
        override fun execute(args: String): CommandOutcome = run(client, args, "restart")
    }

    /**
     * The shared body: read the app name, call the supervisor, report.
     *
     * `app` is the argument the server's command carries; `name` is accepted as an
     * alias because it is the natural word and a dispatcher on the other side may
     * send either. Both are checked rather than guessing one.
     */
    private fun run(
        client: AppControlClient,
        args: String,
        op: String,
    ): CommandOutcome {
        val name =
            try {
                AppCommandArgs.name(args)
            } catch (e: Exception) {
                return CommandOutcome.failed("could not read the app name: ${e.message}")
            }

        if (name.isEmpty()) {
            return CommandOutcome.failed("$op requires an app name (\"app\" or \"name\")")
        }

        return client.call(op, mapOf("name" to name))
    }
}
