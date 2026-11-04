package com.vyzorix.connector.cloud

/**
 * Pulls one top-level field's **raw JSON text** out of a frame without
 * re-serialising it.
 *
 * The command signature covers the exact `args` bytes the server put on the wire.
 * Parsing into a map and re-encoding would normalise key order and whitespace, and
 * the signature would no longer match — a subtle failure that only appears for
 * frames whose `args` are not already canonical. This returns the original
 * substring, so verification sees the same bytes the server signed.
 *
 * It is a small, deliberately narrow scanner rather than a general JSON parser:
 * it finds one key at the top level and returns its value's extent. A full parse
 * still happens later, once the signature is trusted, via kotlinx.serialization.
 */
internal object RawJson {
    /** The raw text of the top-level `"key": <value>`, or null when absent. */
    fun field(
        json: String,
        key: String,
    ): String? {
        val colon = findValueStart(json, key) ?: return null

        return valueSlice(json, colon)
    }

    /**
     * The offset just past the `:` for the top-level [key], or null.
     *
     * Skips strings and nested objects/arrays while scanning, so a `"args"` nested
     * inside another object is not mistaken for the top-level one. Depth 1 is
     * "directly inside the root object": the opening `{` of the frame puts us at
     * depth 1, and a key that is a sibling of the frame's fields is seen there.
     */
    private fun findValueStart(
        json: String,
        key: String,
    ): Int? {
        val needle = "\"$key\""
        var i = 0
        var depth = 0
        val n = json.length

        while (i < n) {
            val c = json[i]
            when (c) {
                '"' -> {
                    // A string at this position: if it is the key and we are directly
                    // inside the root object, the next non-space char is the ':'.
                    val end = stringEnd(json, i)
                    if (depth == 1 && json.regionMatches(i, needle, 0, needle.length)) {
                        var j = i + needle.length
                        while (j < n && json[j].isWhitespace()) j++
                        if (j < n && json[j] == ':') return j + 1
                    }
                    i = end
                }
                '{', '[' -> {
                    depth++
                    i++
                }
                '}', ']' -> {
                    depth--
                    i++
                }
                else -> i++
            }
        }

        return null
    }

    /** The index just past the closing quote of the string starting at [start]. */
    private fun stringEnd(
        json: String,
        start: Int,
    ): Int {
        var i = start + 1
        while (i < json.length) {
            when (json[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }

        return json.length
    }

    /** The raw value slice beginning at [afterColon] (skipping leading spaces). */
    private fun valueSlice(
        json: String,
        afterColon: Int,
    ): String? {
        var i = afterColon
        val n = json.length
        while (i < n && json[i].isWhitespace()) i++
        if (i >= n) return null

        return when (json[i]) {
            '"' -> json.substring(i, stringEnd(json, i))
            '{', '[' -> json.substring(i, compoundEnd(json, i))
            else -> {
                // A scalar: read until a delimiter.
                var j = i
                while (j < n && json[j] !in ",}] \t\r\n") j++
                json.substring(i, j)
            }
        }
    }

    /** The index just past the compound value (object/array) starting at [start]. */
    private fun compoundEnd(
        json: String,
        start: Int,
    ): Int {
        var i = start
        var depth = 0
        while (i < json.length) {
            when (json[i]) {
                '"' -> i = stringEnd(json, i)
                '{', '[' -> {
                    depth++
                    i++
                }
                '}', ']' -> {
                    depth--
                    i++
                    if (depth == 0) return i
                }
                else -> i++
            }
        }

        return json.length
    }
}
