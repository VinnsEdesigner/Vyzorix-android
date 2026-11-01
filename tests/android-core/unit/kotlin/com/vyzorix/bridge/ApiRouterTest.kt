package com.vyzorix.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Router behaviour: name resolution, the scope check, and that a throwing handler
 * becomes an error response rather than killing the dispatch thread.
 */
@RunWith(RobolectricTestRunner::class)
class ApiRouterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // The default is a populated allowlist: permits() is fail-closed, so an empty
    // one would deny everything and every case below would fail for the wrong
    // reason. Tests that want to prove denial pass an explicit allowlist.
    private fun token(
        allowlist: Set<String> = setOf("ping", "battery", "boom", "whoami", "x"),
    ): SessionToken = SessionTokenRegistry().issue("s1", "dev-1", "op-1", apiAllowlist = allowlist)

    @Test
    fun `dispatches a registered api`() {
        val router =
            ApiRouter().register("ping") { ApiOutcome.ok(JSONObject().put("pong", true)) }

        val outcome = router.dispatch(context, token(), "ping", emptyMap())

        assertTrue(outcome.isOk)
        assertTrue(outcome.json!!.contains("pong"))
    }

    @Test
    fun `an unknown api reports unknown_api`() {
        val router = ApiRouter()

        val outcome = router.dispatch(context, token(), "nope", emptyMap())

        assertEquals(BridgeErrorKind.UNKNOWN_API, outcome.error?.kind)
    }

    @Test
    fun `a token without scope is refused`() {
        val router = ApiRouter().register("battery") { ApiOutcome.ok(JSONObject()) }

        val outcome = router.dispatch(context, token(setOf("logcat")), "battery", emptyMap())

        assertEquals(BridgeErrorKind.FORBIDDEN, outcome.error?.kind)
    }

    @Test
    fun `an empty allowlist is refused`() {
        // Supersedes the earlier "empty means every API" reading of contract 2.5.
        // The allowlist is resolved on the server, so empty is deny-by-default; a
        // master-key reading turns a resolution that granted nothing into full
        // access. See documents/ANDROID_SERVICE_BOUNDARY_CONTRACT.md §7.
        val router = ApiRouter().register("battery") { ApiOutcome.ok(JSONObject()) }

        val outcome = router.dispatch(context, token(emptySet()), "battery", emptyMap())

        assertEquals(BridgeErrorKind.FORBIDDEN, outcome.error?.kind)
    }

    @Test
    fun `a throwing handler becomes an internal error, not a crash`() {
        val router = ApiRouter().register("boom") { error("kaboom") }

        val outcome = router.dispatch(context, token(), "boom", emptyMap())

        assertFalse(outcome.isOk)
        assertEquals(BridgeErrorKind.INTERNAL, outcome.error?.kind)
    }

    @Test
    fun `the auth name is reserved`() {
        val thrown =
            runCatching { ApiRouter().register(API_AUTH_NAME) { ApiOutcome.ok(JSONObject()) } }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a duplicate registration is a programming error`() {
        val router = ApiRouter().register("x") { ApiOutcome.ok(JSONObject()) }

        val thrown = runCatching { router.register("x") { ApiOutcome.ok(JSONObject()) } }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a handler sees the caller's token`() {
        var seen: SessionToken? = null
        val router =
            ApiRouter().register("whoami") { call ->
                seen = call.token
                ApiOutcome.ok(JSONObject().put("session", call.token.sessionId))
            }

        val active = token()
        router.dispatch(context, active, "whoami", emptyMap())

        assertEquals("s1", seen?.sessionId)
    }
}
