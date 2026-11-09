package com.vyzorix.connector.config

import java.nio.file.Paths

/**
 * A refusal with a distinct, machine-readable reason (FR-16). [detail] names the
 * offending app or artifact.
 */
data class Refusal(
    val reason: String,
    val detail: String,
)

/**
 * The static rules the device re-checks on a verified config: every artifact
 * reference carries a digest (§5.3), and every `cmd` stays inside its app's own
 * directory (§8). The server refuses these at compile time too; the device
 * re-checks because it is the thing that runs the command.
 *
 * The `cmd` existence check is not here — it needs the artifact fetched, so it
 * belongs to the apply path.
 */
class ConfigValidator {
    /** The first refusal, or null when every app passes. */
    fun validate(apps: List<AppConfig>): Refusal? {
        for (app in apps) {
            validateArtifacts(app)?.let { return it }
            validateCmd(app)?.let { return it }
        }

        return null
    }

    private fun validateArtifacts(app: AppConfig): Refusal? {
        for (artifact in app.artifacts) {
            if (artifact.sha256.isEmpty()) {
                return Refusal(
                    "unhashed_reference",
                    "app.${app.name}.artifact.${artifact.name} has no sha256",
                )
            }
        }

        return null
    }

    private fun validateCmd(app: AppConfig): Refusal? {
        val cmd = app.cmd
        val appDir = "$APPS_ROOT${app.name}/"

        if (!cmd.startsWith("/")) {
            return Refusal("cmd_not_absolute", "app.${app.name}.cmd is not an absolute path")
        }
        if (cmd.any { it in METACHARACTERS }) {
            return Refusal(
                "cmd_metacharacter",
                "app.${app.name}.cmd contains a shell metacharacter",
            )
        }
        if (!cmd.startsWith(appDir)) {
            return Refusal("cmd_escape", "app.${app.name}.cmd is outside $appDir")
        }

        // A `..` segment escapes without changing the prefix check, so the cleaned
        // path must still be inside the directory. Lexical; the symlink check is the
        // apply path's.
        if (!Paths
                .get(cmd)
                .normalize()
                .toString()
                .startsWith(appDir)
        ) {
            return Refusal("cmd_escape", "app.${app.name}.cmd escapes the app directory")
        }

        return null
    }

    private companion object {
        const val APPS_ROOT = "/home/apps/"

        /** The shell characters the `cmd` rule refuses. `cmd` runs with no shell. */
        val METACHARACTERS = ";|&$`><(){}*?~!\n\r".toSet()
    }
}
