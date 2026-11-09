package com.vyzorix.connector.deploy

import com.vyzorix.connector.config.AppConfig
import com.vyzorix.connector.config.ArtifactConfig
import com.vyzorix.connector.config.DeviceConfig
import com.vyzorix.connector.secret.SecretResolution
import com.vyzorix.connector.secret.SecretStore
import com.vyzorix.engine.TarGz
import java.io.File
import java.io.IOException

/**
 * Applies a verified config: fetch each artifact, verify its digest, install it
 * atomically, write secrets and the supervisor's registry
 * (VYZORIX_DEPLOY_CONTRACT.md §9.1).
 *
 * A failed apply changes nothing. Every artifact is fetched and unpacked into
 * staging before any live directory is touched, so a bad artifact leaves the
 * previous copies running. Installation is a per-artifact rename — the staged
 * directory over `<app>/<artifact>` — which keeps the app's other files (its data,
 * its `.env`) intact: replacing the whole app directory would delete exactly the
 * state §9.3 says is retained.
 *
 * The supervisor is pinged first: a config that lands without a running supervisor
 * would leave every app silently stopped (§13.6).
 *
 * @param stagingDir scratch space for downloads and unpacking. Its parent is the
 *   app-storage directory — `files/apps` on the host, bound to `/home/apps` in the
 *   container — so every rename stays on one filesystem.
 * @param secrets written into each app's directory before the registry points at it,
 *   or null when the build carries no secret key.
 */
class DeployApplier(
    private val fetcher: ArtifactFetcher,
    private val registry: AppRegistryWriter,
    private val supervisor: SupervisorControl,
    private val stagingDir: File,
    private val secrets: SecretStore? = null,
    private val pwa: PwaManifest = PwaManifest(),
) {
    /** Applies [config]. Returns the outcome; an expected refusal is not thrown. */
    fun apply(config: DeviceConfig): ApplyResult {
        if (!supervisor.ping()) {
            return ApplyResult.refused(
                config.version,
                "supervisor_unreachable",
                "the app runtime did not answer a ping",
            )
        }

        val pending = mutableListOf<Install>()
        for (app in config.apps) {
            prepare(config.version, app, pending)?.let { return it }
        }

        // Resolve every secret before touching the live tree: a secret that will not
        // open must abort with the device unchanged.
        val resolved = mutableMapOf<String, Map<String, String>>()
        for (app in config.apps) {
            when (val resolution = resolveSecrets(app)) {
                is SecretResolution.Resolved -> resolved[app.name] = resolution.values
                is SecretResolution.Undecryptable ->
                    return ApplyResult.refused(
                        config.version,
                        "secret_undecryptable",
                        "${app.name}.${resolution.key}",
                    )
            }
        }

        val removed =
            registry.read(registryPath()).map { it.name }.filterNot { name ->
                config.apps.any { it.name == name }
            }

        purge(config.apps)
        install(pending)
        writeSecrets(config, resolved)
        writePwaManifests(config)
        registry.write(registryPath(), config.apps)
        removed.forEach { supervisor.stop(it) }

        return ApplyResult.ok(config.version)
    }

    /** Fetches and stages every artifact of [app], or returns a refusal. */
    private fun prepare(
        version: Int,
        app: AppConfig,
        pending: MutableList<Install>,
    ): ApplyResult? {
        for (artifact in app.artifacts) {
            val tag = "${app.name}-${artifact.name}-${artifact.arch ?: ANY_ARCH}"
            val download = File(stagingDir, tag)
            fetch(version, artifact, download)?.let { return it }

            val staged = File(stagingDir, "$tag$STAGING_SUFFIX")
            staged.deleteRecursively()
            unpack(download, staged)
            pending += Install(File(File(appsRoot(), app.name), destinationOf(artifact)), staged)
        }

        return null
    }

    private fun fetch(
        version: Int,
        artifact: ArtifactConfig,
        download: File,
    ): ApplyResult? =
        try {
            fetcher.fetch(artifact.url, artifact.sha256, download)

            null
        } catch (e: DigestMismatch) {
            ApplyResult.refused(version, "digest_mismatch", "${artifact.name}: ${e.message}")
        } catch (e: IOException) {
            ApplyResult.refused(version, "artifact_fetch_failed", "${artifact.name}: ${e.message}")
        }

    /** Unpacks [download] into [staged]. The digest was verified by the fetch. */
    private fun unpack(
        download: File,
        staged: File,
    ) {
        staged.mkdirs()

        download.inputStream().use { TarGz.extract(it, staged, expectedSha256 = "") }
    }

    /** Where an artifact unpacks, relative to the app's directory. */
    private fun destinationOf(artifact: ArtifactConfig): String =
        if (artifact.kind == WEBROOT) "${artifact.name}/$WEBROOT_DIR" else artifact.name

    /**
     * Installs the staged artifacts.
     *
     * The first point the live tree is touched, so everything before it is free to
     * fail.
     */
    private fun install(pending: List<Install>) {
        for (install in pending) {
            install.live.parentFile?.mkdirs()
            val backup = File(install.live.parentFile, "${install.live.name}$BACKUP_SUFFIX")
            backup.deleteRecursively()
            if (install.live.exists()) {
                install.live.renameTo(backup)
            }
            if (!install.staged.renameTo(install.live)) {
                backup.renameTo(install.live)

                throw IOException("could not install ${install.live.absolutePath}")
            }
            backup.deleteRecursively()
        }
    }

    /** Deletes the data of an app that declares `purge` (§9.3, FR-20). */
    private fun purge(apps: List<AppConfig>) {
        for (app in apps) {
            if (app.purge) {
                File(appsRoot(), app.name).deleteRecursively()
            }
        }
    }

    /** Resolves [app]'s secrets, or reports the store is absent as no secrets. */
    private fun resolveSecrets(app: AppConfig): SecretResolution =
        secrets?.resolve(app) ?: SecretResolution.Resolved(emptyMap())

    /** Writes the resolved secrets into each app's directory. */
    private fun writeSecrets(
        config: DeviceConfig,
        resolved: Map<String, Map<String, String>>,
    ) {
        val store = secrets ?: return

        for (app in config.apps) {
            store.writeEnv(File(appsRoot(), app.name), resolved[app.name].orEmpty())
        }
    }

    /** Writes each app's PWA manifest, when it declares one (§6). */
    private fun writePwaManifests(config: DeviceConfig) {
        for (app in config.apps) {
            pwa.write(app, File(appsRoot(), app.name))
        }
    }

    private fun appsRoot(): File = stagingDir.parentFile ?: stagingDir

    private fun registryPath(): File = File(appsRoot(), REGISTRY_NAME)

    private data class Install(
        val live: File,
        val staged: File,
    )

    private companion object {
        const val REGISTRY_NAME = "apps.json"
        const val WEBROOT = "webroot"
        const val WEBROOT_DIR = "www"
        const val STAGING_SUFFIX = ".stage"
        const val BACKUP_SUFFIX = ".old"
        const val ANY_ARCH = "any"
    }
}
