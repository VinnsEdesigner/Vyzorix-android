package com.vyzorix.connector.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The `APP_*` argument contract, on the JVM.
 *
 * The socket path needs a real socket and lives in the instrumented suite; this is
 * the half that is pure parsing, and it is the half a server-side change breaks
 * silently. Both `app` and `name` are accepted because the risk catalog and the
 * dashboard have each used one, and guessing one would break the other with no
 * error anywhere.
 */
class AppCommandArgsTest {
    @Test
    fun `the app field is read`() {
        assertEquals("flasher", AppCommandArgs.name("""{"app":"flasher"}"""))
    }

    @Test
    fun `the name field is read as an alias`() {
        assertEquals("flasher", AppCommandArgs.name("""{"name":"flasher"}"""))
    }

    @Test
    fun `app wins when both are present`() {
        // Deterministic, not arbitrary: `app` is the field the risk catalog names.
        assertEquals("first", AppCommandArgs.name("""{"app":"first","name":"second"}"""))
    }

    @Test
    fun `blank args read as no name`() {
        assertEquals("", AppCommandArgs.name(""))
        assertEquals("", AppCommandArgs.name("   "))
    }

    @Test
    fun `an object with neither field reads as no name`() {
        assertEquals("", AppCommandArgs.name("""{"other":"x"}"""))
    }

    @Test
    fun `unreadable args throw rather than becoming an empty name`() {
        // An empty name would reach the supervisor as "start the app called ''",
        // which is a confusing message for what is really a malformed frame.
        assertThrows(Exception::class.java) { AppCommandArgs.name("{not json") }
    }
}
