package com.vyzorix.connector.deploy

import java.io.File
import java.security.SecureRandom

/**
 * The LAN expose token for an app.
 *
 * The token is **device-generated, not carried in the config**: a config may not
 * carry a secret, and the supervisor's own meta store
 * is where an expose token lives. It must be stable across redeploys, or a device
 * that redeploys silently changes the URL a client was given.
 *
 * It must also not be readable by the container: `files/apps` is bound to
 * `/home/apps` and proot runs as root inside, so a token written there is readable by
 * every app. The production implementation therefore stores it outside that tree.
 */
fun interface ExposeTokenSource {
    fun tokenFor(app: String): String
}

/** Generates a token per app and remembers it for the process lifetime. For tests. */
class InMemoryExposeTokens : ExposeTokenSource {
    private val tokens = mutableMapOf<String, String>()

    override fun tokenFor(app: String): String = tokens.getOrPut(app) { randomToken() }
}

/**
 * A token per app, persisted in one file **outside** the container's bind.
 *
 * The file is `KEY=value` lines in app-private storage (`context.filesDir`, the parent
 * of `files/apps` — the subdirectory is bound, the parent is not). Mode is left to the
 * app sandbox, which is the same protection every other private file has.
 */
class FileExposeTokens(
    private val file: File,
) : ExposeTokenSource {
    private val tokens: MutableMap<String, String> by lazy { load() }

    override fun tokenFor(app: String): String {
        tokens[app]?.let { return it }

        val token = randomToken()
        tokens[app] = token
        save()

        return token
    }

    private fun load(): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()

        return file
            .readLines()
            .mapNotNull { line ->
                val cut = line.indexOf('=')
                if (cut <= 0) null else line.substring(0, cut) to line.substring(cut + 1)
            }.toMap()
            .toMutableMap()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(tokens.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=$v" })
    }
}

/** 128 bits of randomness, hex. */
internal fun randomToken(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)

    return bytes.joinToString("") { "%02x".format(it) }
}
