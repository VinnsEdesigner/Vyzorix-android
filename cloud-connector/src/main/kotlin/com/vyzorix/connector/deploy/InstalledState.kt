package com.vyzorix.connector.deploy

import com.vyzorix.connector.config.AppConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What the device is actually running: the config version it holds, and for each app
 * the digests of the artifacts it installed.
 *
 * ## Why this exists
 *
 * Until now the only record of "what is running" was the server's own
 * `applied_at`, written from a `device_ready` frame. When an apply failed, that
 * frame was still recorded as applied (see the server's `handleDeviceReady`), so
 * the console showed a deploy that had never landed and nothing noticed. The
 * device held the truth and never said it.
 *
 * This file is that truth, written by the applier at the moment an install
 * succeeds — so it describes what is on disk, not what was requested. Telemetry
 * carries it on every heartbeat, and the server compares it to the config it
 * expects. A mismatch is drift, visible continuously rather than only when
 * someone happens to deploy.
 *
 * ## Why the digests, not just the version
 *
 * The version alone catches a failed apply (the device still holds the old one).
 * The per-artifact digests catch the deeper drift the version cannot: an app
 * directory edited by hand, an artifact replaced, a partially unpacked tree. The
 * digest is the one the device *verified* when it installed, so it is the same
 * value the manifest declared — which is what makes it comparable.
 *
 * ## Why the file is in `files/apps`
 *
 * Beside the registry, because it describes the registry's contents. It holds no
 * secret — every digest is already in the signed config — so it is safe in the
 * directory the container can read.
 */
@Serializable
data class InstalledState(
    @SerialName("version") val version: Int = 0,
    /**
     * App name → artifact name → the sha256 the device verified when it installed.
     *
     * A map of maps rather than a flat list so a reader can name the app that
     * drifted without parsing a key, and so the encoding is stable.
     */
    @SerialName("apps") val apps: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        const val FILE_NAME = "installed.json"
    }
}

/**
 * Reads and writes [InstalledState], and derives the per-app digest the telemetry
 * heartbeat reports.
 *
 * Writes are atomic — temp then rename — for the same reason the registry's are: a
 * crash mid-write must leave either the old file or the new one, never a
 * half-written one that reads as "nothing installed".
 */
class InstalledStateStore(
    private val json: Json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        },
) {
    /** The state at [path], or an empty one when there is no file yet. */
    fun read(path: File): InstalledState {
        if (!path.isFile) return InstalledState()

        return try {
            json.decodeFromString<InstalledState>(path.readText())
        } catch (e: Exception) {
            // A file that will not parse is treated as "nothing known", which reads as
            // drift rather than as a healthy device — the safe direction. It is logged
            // rather than silently swallowed, because a state file that will not parse
            // is a bug worth seeing, not a normal condition.
            android.util.Log.w(
                "InstalledState",
                "unreadable installed state at $path: ${e.message}",
            )

            InstalledState()
        }
    }

    /** Writes [state] to [path] atomically. */
    fun write(
        path: File,
        state: InstalledState,
    ) {
        val text = json.encodeToString(state)
        path.parentFile?.mkdirs()

        val temp = File(path.parentFile, "${path.name}.vyzorix-tmp")
        temp.writeText(text)
        if (!temp.renameTo(path)) {
            temp.delete()
            throw IOException("could not replace ${path.absolutePath}")
        }
    }

    /** The state describing [apps] at config [version], from what was installed. */
    fun stateFor(
        version: Int,
        apps: List<AppConfig>,
    ): InstalledState =
        InstalledState(
            version = version,
            apps =
                apps.associate { app ->
                    app.name to app.artifacts.associate { it.name to it.sha256 }
                },
        )

    /** Removes the file, so a device that lost its apps reports no state rather than a stale one. */
    fun clear(path: File) {
        path.delete()
    }

    companion object {
        /**
         * The digest telemetry reports for one app, or null when the app has no
         * installed artifacts.
         *
         * **This algorithm is a wire contract and is duplicated on the server**
         * (`deploy.AppStateDigest`). Both sides must produce the same string for the
         * same artifacts, or every device reads as drifted. The shape is deliberately
         * trivial to reimplement: each artifact as `name=sha256`, sorted by artifact
         * name, joined by newlines, hashed with SHA-256 and truncated to 16 hex
         * characters. The app name is *not* in the body — it is the key this digest is
         * filed under, so including it would add nothing.
         *
         * Sorted, because a map has no order and the digest must not depend on one.
         * Truncated, because it travels on every heartbeat and 64 bits of a digest
         * over a handful of artifacts is ample to notice a change.
         */
        fun digest(artifacts: Map<String, String>): String? {
            if (artifacts.isEmpty()) return null

            val body =
                artifacts.entries
                    .sortedBy { it.key }
                    .joinToString("\n") { (name, sha) -> "$name=$sha" }

            val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
            return hash.take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
        }

        /** 8 bytes → 16 hex characters. */
        private const val DIGEST_BYTES = 8
    }
}

/**
 * A store bound to the file it writes.
 *
 * The applier needs both, and passing them as two parameters let them be given
 * different paths — a mistake that would be invisible until the heartbeat reported
 * state nobody could find. Binding them makes that unrepresentable.
 *
 * It is a thin wrapper, not a second store: [record] derives the state and writes it
 * through the same [InstalledStateStore] the telemetry reader uses, so the two sides
 * cannot disagree about the shape.
 */
class InstalledStateTarget(
    private val path: File,
    private val store: InstalledStateStore = InstalledStateStore(),
) {
    /** Records [apps] as installed at config [version]. */
    fun record(
        version: Int,
        apps: List<AppConfig>,
    ) {
        store.write(path, store.stateFor(version, apps))
    }

    /** The state last recorded, or an empty one when nothing has been. */
    fun read(): InstalledState = store.read(path)

    companion object {
        /** The target beside an app-storage directory, which is where the registry is. */
        fun beside(appsRoot: File): InstalledStateTarget =
            InstalledStateTarget(File(appsRoot, InstalledState.FILE_NAME))

        /**
         * The target under an app's private storage.
         *
         * The one place the `apps/` subdirectory is named, so a caller cannot pair this
         * with the wrong directory — the applier and the telemetry reader both resolve
         * through here.
         */
        fun under(filesDir: File): InstalledStateTarget = beside(File(filesDir, APPS_DIR))

        /** The app-storage subdirectory, matching the applier's and the supervisor's. */
        const val APPS_DIR = "apps"
    }
}
