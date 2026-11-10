package com.vyzorix.connector.config

import com.vyzorix.libv.VNode

/** Reads the `apps` section out of a verified config. */
class AppConfigReader {
    /** The apps declared in [root], in document order. Empty when there is no apps section. */
    fun read(root: VNode): List<AppConfig> =
        root.find(APPS_PATH).flatMap { apps -> children(apps, APP) }.map { readApp(it) }

    private fun readApp(node: VNode): AppConfig =
        AppConfig(
            name = node.tokens.getOrNull(1) ?: "",
            dir = value(node, "dir") ?: "",
            cmd = value(node, "cmd") ?: "",
            port = value(node, "port")?.toIntOrNull(),
            autostart = value(node, "autostart") != "false",
            expose = value(node, "expose") ?: DEFAULT_EXPOSE,
            purge = value(node, "purge") == "true",
            env = readEnv(node),
            secretNames = children(node, "secret").mapNotNull { it.tokens.getOrNull(1) },
            artifacts = children(node, ARTIFACT).map { readArtifact(it) },
            pwa = value(node, "pwa") == "true",
            pwaOverrides = readPwaOverrides(node),
        )

    private fun readArtifact(node: VNode): ArtifactConfig =
        ArtifactConfig(
            name = node.tokens.getOrNull(1) ?: "",
            arch = value(node, "arch"),
            url = value(node, "url") ?: "",
            sha256 = value(node, "sha256") ?: "",
            kind = value(node, "kind") ?: DEFAULT_ARTIFACT_KIND,
        )

    private fun readEnv(app: VNode): Map<String, String> =
        children(app, "env")
            .mapNotNull { node ->
                val token = node.tokens.getOrNull(1) ?: return@mapNotNull null
                val cut = token.indexOf('=')
                if (cut < 0) null else token.substring(0, cut) to token.substring(cut + 1)
            }.toMap()

    private fun readPwaOverrides(app: VNode): Map<String, String> =
        children(app, "pwa")
            .flatMap { it.children }
            .mapNotNull { child ->
                val key = child.tokens.getOrNull(0) ?: return@mapNotNull null
                val fieldValue = child.tokens.getOrNull(1) ?: return@mapNotNull null
                key to fieldValue
            }.toMap()

    private fun children(
        node: VNode,
        key: String,
    ): List<VNode> = node.children.filter { it.tokens.firstOrNull() == key }

    private fun value(
        node: VNode,
        key: String,
    ): String? = children(node, key).firstOrNull()?.tokens?.getOrNull(1)

    private companion object {
        const val APPS_PATH = "device.apps"
        const val APP = "app"
        const val ARTIFACT = "artifact"
        const val DEFAULT_EXPOSE = "loopback"
        const val DEFAULT_ARTIFACT_KIND = "dir"
    }
}
