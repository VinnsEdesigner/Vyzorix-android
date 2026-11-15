package com.vyzorix.connector.deploy

import com.vyzorix.connector.config.AppConfig
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One entry in the supervisor's `apps.json` (see `native/lib/include/vyzorix/app_registry.h`).
 *
 * `pid` and `pidStartTicks` are the supervisor's record of the running process and
 * are written as 0 here: the supervisor fills them. A definition carries neither
 * `expose` nor any secret — exposure lives in `meta/`, and a secret lives in a
 * `0600` `.env` the supervisor reads at start.
 */
@Serializable
data class RegistryApp(
    val name: String,
    val dir: String,
    val cmd: String,
    val port: Int,
    val env: Map<String, String> = emptyMap(),
    val autostart: Boolean,
    val pid: Int = 0,
    @SerialName("pidStartTicks") val pidStartTicks: Long = 0,
)

@Serializable
private data class RegistryDocument(
    val apps: List<RegistryApp>,
)

/**
 * Reads and writes the supervisor's `apps.json`.
 *
 * The registry is the file the supervisor watches and the file a user edits, so
 * it holds only what the supervisor's `App` struct holds. The deploy config is
 * richer (artifacts, secret names, exposure) and must not be copied here: a secret in
 * this file would be readable by every app in the container.
 *
 * Writes are atomic — temp then rename — so a crash mid-write leaves either the old
 * file or the new one, never a half-written registry the supervisor refuses to parse.
 */
class AppRegistryWriter(
    private val json: Json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        },
) {
    /** The registry at [path], or an empty list when there is no file yet. */
    fun read(path: File): List<RegistryApp> {
        if (!path.isFile) return emptyList()

        return json.decodeFromString<RegistryDocument>(path.readText()).apps
    }

    /** Writes [apps] to [path] atomically. */
    fun write(
        path: File,
        apps: List<AppConfig>,
    ) {
        val document =
            RegistryDocument(
                apps.map { app ->
                    RegistryApp(
                        name = app.name,
                        dir = app.dir,
                        cmd = app.cmd,
                        port = app.port ?: 0,
                        env = app.env,
                        autostart = app.autostart,
                    )
                },
            )

        val text = json.encodeToString(document)
        path.parentFile?.mkdirs()

        val temp = File(path.parentFile, "${path.name}.vyzorix-tmp")
        temp.writeText(text)
        if (!temp.renameTo(path)) {
            temp.delete()
            throw IOException("could not replace ${path.absolutePath}")
        }
    }
}
