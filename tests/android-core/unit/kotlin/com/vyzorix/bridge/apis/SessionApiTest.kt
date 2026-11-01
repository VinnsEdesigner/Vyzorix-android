package com.vyzorix.bridge.apis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.SessionTokenRegistry
import com.vyzorix.capability.PtyError
import com.vyzorix.capability.PtyResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The session API is the door the terminal comes through, so its argument handling
 * and its use of the caller's token (never client-supplied device/operator) are
 * tested directly with fake callbacks.
 */
@RunWith(RobolectricTestRunner::class)
class SessionApiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val token =
        SessionTokenRegistry().issue(
            "s1",
            "dev-1",
            "op-1",
            apiAllowlist = setOf(SessionApi.NAME),
        )

    private fun call(args: Map<String, String>) = ApiCall(context, token, args)

    private fun api(
        open: (String, String, String) -> PtyResult<Int> = { _, _, _ -> PtyResult.Ok(0) },
        close: (Int) -> PtyResult<Unit> = { PtyResult.Ok(Unit) },
        resize: (Int, Int, Int) -> PtyResult<Unit> = { _, _, _ -> PtyResult.Ok(Unit) },
        // Owns every channel by default, so the tests that are about argument
        // handling are not also exercising the binding check. The binding tests
        // below pass an explicit predicate.
        owned: (Int, String) -> Boolean = { _, _ -> true },
    ) = SessionApi(open, close, resize, owned)

    @Test
    fun `open returns the channel and binds to the token's device and operator`() {
        var seenDevice: String? = null
        var seenOperator: String? = null
        val api =
            api(
                open = { _, device, operator ->
                    seenDevice = device
                    seenOperator = operator
                    PtyResult.Ok(3)
                },
            )

        val outcome = api.handle(call(mapOf("action" to "open", "sessionId" to "mine")))

        assertTrue(outcome.isOk)
        assertEquals("dev-1", seenDevice)
        assertEquals("op-1", seenOperator)
        assertEquals(3, JSONObject(outcome.json!!).getInt("channel"))
    }

    @Test
    fun `open defaults to the open action`() {
        assertTrue(api().handle(call(emptyMap())).isOk)
    }

    @Test
    fun `close without a channel is an argument error`() {
        val outcome = api().handle(call(mapOf("action" to "close")))

        assertEquals(BridgeErrorKind.INVALID_ARGS, outcome.error?.kind)
    }

    @Test
    fun `close maps no-such-session from the broker`() {
        val api = api(close = { PtyResult.Err(PtyError(PtyError.Kind.NO_SUCH_SESSION, "gone")) })

        val outcome = api.handle(call(mapOf("action" to "close", "channel" to "9")))

        assertEquals(BridgeErrorKind.NO_SUCH_SESSION, outcome.error?.kind)
    }

    @Test
    fun `resize requires channel, rows and cols`() {
        val api = api()

        assertEquals(
            BridgeErrorKind.INVALID_ARGS,
            api.handle(call(mapOf("action" to "resize", "channel" to "0"))).error?.kind,
        )
        assertEquals(
            BridgeErrorKind.INVALID_ARGS,
            api
                .handle(call(mapOf("action" to "resize", "channel" to "0", "rows" to "24")))
                .error
                ?.kind,
        )
    }

    @Test
    fun `resize forwards the geometry`() {
        var seen: Triple<Int, Int, Int>? = null
        val api =
            api(
                resize = { channel, rows, cols ->
                    seen = Triple(channel, rows, cols)
                    PtyResult.Ok(Unit)
                },
            )

        val outcome =
            api.handle(
                call(
                    mapOf("action" to "resize", "channel" to "2", "rows" to "40", "cols" to "120"),
                ),
            )

        assertTrue(outcome.isOk)
        assertEquals(Triple(2, 40, 120), seen)
    }

    @Test
    fun `an unknown action is an argument error`() {
        val outcome: ApiOutcome = api().handle(call(mapOf("action" to "detonate")))

        assertEquals(BridgeErrorKind.INVALID_ARGS, outcome.error?.kind)
    }

    @Test
    fun `close of a channel owned by another session is refused`() {
        var closed = false
        val api =
            api(close = {
                closed = true
                PtyResult.Ok(Unit)
            }, owned = { _, _ -> false })

        val outcome = api.handle(call(mapOf("action" to "close", "channel" to "9")))

        assertEquals(BridgeErrorKind.FORBIDDEN, outcome.error?.kind)
        assertFalse("a refused close must not reach the broker", closed)
    }

    @Test
    fun `resize of a channel owned by another session is refused`() {
        var resized = false
        val api =
            api(resize = {
                    _,
                    _,
                    _,
                ->
                resized = true
                PtyResult.Ok(Unit)
            }, owned = { _, _ -> false })

        val outcome =
            api.handle(
                call(mapOf("action" to "resize", "channel" to "3", "rows" to "24", "cols" to "80")),
            )

        assertEquals(BridgeErrorKind.FORBIDDEN, outcome.error?.kind)
        assertFalse("a refused resize must not reach the broker", resized)
    }

    @Test
    fun `a channel owned by this session is allowed`() {
        val api = api(owned = { channel, session -> channel == 4 && session == "s1" })

        assertTrue(api.handle(call(mapOf("action" to "close", "channel" to "4"))).isOk)
    }
}
