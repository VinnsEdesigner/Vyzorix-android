package com.vyzorix.connector.deploy

import com.vyzorix.connector.config.AppConfig
import java.io.File

/**
 * The `cmd` rule, re-checked on the device after the artifacts are installed
 * The server checks `cmd` lexically at compile time, but it cannot check the two
 * things that need the files: that the path, **after resolving symlinks**, is still
 * inside the app's own directory, and that it exists and is executable
 * A symlink inside a fetched artifact pointing outside the app directory
 * is the escape this catches.
 *
 * The check is the device's because the device is the thing that runs the command, and
 * a config could have been signed by a compromised or older server.
 */
object AppCmdCheck {
    /**
     * Returns the refusal reason for [app]'s `cmd` under [appDir] (the host path of
     * `/home/apps/<app>`), or null when it is safe to run.
     */
    fun check(
        app: AppConfig,
        appDir: File,
    ): String? {
        val root = appDir.canonicalFile
        val target = hostPath(app, appDir)

        // Rule 3: the resolved path must still be inside the app directory. A symlink
        // that points out is refused, which is what makes the prefix rule hold.
        val resolved = target.canonicalFile
        if (!resolved.path.startsWith("${root.path}/")) {
            return "cmd_escape"
        }

        // Rule 4: it must exist and be executable.
        if (!resolved.isFile || !resolved.canExecute()) {
            return "cmd_not_executable"
        }

        return null
    }

    /** `/home/apps/<app>/x` under [appDir] -> `<appDir>/x`. */
    private fun hostPath(
        app: AppConfig,
        appDir: File,
    ): File {
        val prefix = "/home/apps/${app.name}/"
        val relative = app.cmd.removePrefix(prefix)

        return File(appDir, relative)
    }
}
