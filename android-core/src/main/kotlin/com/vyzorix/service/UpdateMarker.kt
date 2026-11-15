package com.vyzorix.service

import android.content.Context
import java.io.File

/**
 * Remembers that an update was installed, so the next process start can say so.
 *
 * The agent downloads an update, hands the APK to the system installer, and is
 * restarted by the install — a fresh process. That process has no memory of the
 * update, so without a marker it would come up saying "running" as if nothing had
 * happened, and the user would never see the update land.
 *
 * So the install step writes this marker first; the next start reads it, shows the
 * "reconnecting" notification, and clears it. The window is bounded: a marker older
 * than [VALID_FOR_MS] is ignored and deleted, so an install the user cancelled — which
 * leaves a marker but never restarts the app — does not produce a stale "updating"
 * message days later.
 */
object UpdateMarker {
    private const val FILE = "update-applying"
    private const val VALID_FOR_MS = 5 * 60 * 1000L

    /** Records that [version] is being installed. */
    fun mark(
        context: Context,
        version: String,
    ) {
        runCatching {
            marker(context).writeText("$version\n${System.currentTimeMillis()}")
        }
    }

    /**
     * The version currently being applied, or null.
     *
     * Returns the version only within [VALID_FOR_MS] of [mark]; a stale marker is
     * cleared and reported as null.
     */
    fun applyingVersion(context: Context): String? {
        val file = marker(context)
        if (!file.isFile) return null

        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val version = lines.getOrNull(0)?.trim().orEmpty()
        val at = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L

        if (version.isEmpty() || System.currentTimeMillis() - at > VALID_FOR_MS) {
            clear(context)

            return null
        }

        return version
    }

    /** Clears the marker. */
    fun clear(context: Context) {
        runCatching { marker(context).delete() }
    }

    private fun marker(context: Context): File = File(context.filesDir, FILE)
}
