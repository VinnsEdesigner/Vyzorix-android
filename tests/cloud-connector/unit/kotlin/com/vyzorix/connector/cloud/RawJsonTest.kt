package com.vyzorix.connector.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RawJson] must return the exact bytes of a field, because the command signature
 * covers `args` verbatim. A re-encoding would normalise key order or whitespace and
 * the signature would stop matching — a failure that only appears for frames whose
 * args are not already canonical.
 */
class RawJsonTest {
    @Test
    fun `returns an object value verbatim`() {
        val json = """{"type":"command","args":{"b":2,"a":1}}"""

        assertEquals("""{"b":2,"a":1}""", RawJson.field(json, "args"))
    }

    @Test
    fun `preserves key order and inner whitespace`() {
        val json = """{"args":{ "z" : 1 , "a" : [1, 2] }}"""

        assertEquals("""{ "z" : 1 , "a" : [1, 2] }""", RawJson.field(json, "args"))
    }

    @Test
    fun `returns a string value with its quotes`() {
        val json = """{"command":"SHELL_EXEC","args":"x"}"""

        assertEquals("\"x\"", RawJson.field(json, "args"))
    }

    @Test
    fun `returns a scalar`() {
        val json = """{"timestamp":1767225600000,"args":{}}"""

        assertEquals("1767225600000", RawJson.field(json, "timestamp"))
    }

    @Test
    fun `ignores a nested key of the same name`() {
        // The nested "args" must not be mistaken for the top-level one.
        val json = """{"other":{"args":"nested"},"args":"top"}"""

        assertEquals("\"top\"", RawJson.field(json, "args"))
    }

    @Test
    fun `returns null when the key is absent`() {
        assertNull(RawJson.field("""{"type":"command"}""", "args"))
    }

    @Test
    fun `handles a brace inside a string`() {
        // A naive scanner would treat the `}` inside the string as the object end.
        val json = """{"args":{"cmd":"echo }"},"x":1}"""

        assertEquals("""{"cmd":"echo }"}""", RawJson.field(json, "args"))
    }

    @Test
    fun `handles an escaped quote inside a string`() {
        val json = """{"args":{"cmd":"a\"b"},"x":1}"""

        assertEquals("""{"cmd":"a\"b"}""", RawJson.field(json, "args"))
    }
}
