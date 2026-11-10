package com.vyzorix.connector.config

import com.vyzorix.libv.VNode

/**
 * The structural no-secret check. A config may
 * name a secret (`secret NAME`); it may never carry a value. Ported from the
 * server's `internal/infrastructure/configscan`, which the device must agree with.
 */
class ConfigSecretScan {
    /** The key that carried a secret, or null when the config is clean. */
    fun offendingKey(root: VNode): String? = scanNode(root, inApp = false)

    private fun scanNode(
        node: VNode,
        inApp: Boolean,
    ): String? {
        scanNodeValue(node, inApp)?.let { return it }

        val childInApp = inApp || node.tokens.firstOrNull() == APP
        for (child in node.children) {
            scanNode(child, childInApp)?.let { return it }
        }

        return null
    }

    private fun scanNodeValue(
        node: VNode,
        inApp: Boolean,
    ): String? {
        val key = node.tokens.firstOrNull() ?: return null

        if (key == ENV) {
            val token = node.tokens.getOrNull(1) ?: return null

            return scanEnvValue(token)
        }

        // `secret` is the name-only field, but only inside an app. A node whose
        // value looks like a credential is refused even there.
        if (key == SECRET && inApp) {
            val value = node.tokens.drop(1).joinToString(" ")
            val carriesValue =
                node.tokens.size > 1 &&
                    (
                        STRONG_SECRET_SHAPE.containsMatchIn(value) ||
                            ENV_SECRET_SHAPE.containsMatchIn(value)
                    )

            return if (carriesValue) SECRET else null
        }

        if (SECRET_KEY_PATTERN.containsMatchIn(key)) {
            return key
        }

        // The whole token run: a PEM header has spaces, so libv splits it.
        val value = node.tokens.drop(1).joinToString(" ")
        if (node.tokens.size > 1 && STRONG_SECRET_SHAPE.containsMatchIn(value)) {
            return key
        }

        return null
    }

    private fun scanEnvValue(token: String): String? {
        val cut = token.indexOf('=')
        if (cut < 0) {
            return ENV
        }

        val key = token.substring(0, cut)
        val value = token.substring(cut + 1)

        if (SECRET_KEY_PATTERN.containsMatchIn(key)) {
            return "$ENV.$key"
        }
        if (STRONG_SECRET_SHAPE.containsMatchIn(value) || ENV_SECRET_SHAPE.containsMatchIn(value)) {
            return "$ENV.$key"
        }

        return null
    }

    private companion object {
        const val ENV = "env"
        const val SECRET = "secret"
        const val APP = "app"

        // Bare `key` is absent: an env var named KEY is ordinary configuration.
        val SECRET_KEY_PATTERN =
            Regex("(?i)(password|passwd|secret|token|hmac|api[_-]?key|private[_-]?key|credential)")

        // Credential shapes regardless of context. Bare hex/base64 are absent here:
        // an artifact's sha256 is 64 hex characters and would trip them.
        val STRONG_SECRET_SHAPE =
            Regex(
                "-----BEGIN [A-Z ]+-----" +
                    "|^eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}$" +
                    "|^[a-z][a-z0-9+.-]*://[^/@\\s:]+:[^/@\\s]+@",
            )

        // Only a secret in an env value, where a digest is not expected.
        val ENV_SECRET_SHAPE = Regex("^[0-9a-fA-F]{40,}$|^[A-Za-z0-9+/]{40,}={0,2}$")
    }
}
