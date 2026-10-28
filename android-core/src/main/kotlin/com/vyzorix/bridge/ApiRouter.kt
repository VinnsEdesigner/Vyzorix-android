package com.vyzorix.bridge

import android.content.Context
import org.json.JSONObject

/**
 * One Android API exposed over the bridge.
 *
 * A handler is registered under a name and returns either a JSON string or a
 * failure. It runs on the dispatch pool, never on a socket reader thread, so a
 * slow call cannot stall framing.
 *
 * The whole [ApiCall] is passed rather than a bare argument map so a handler that
 * needs the caller's scope (a session-opening handler must bind the session to the
 * token's device and operator) has it, without every handler taking a token
 * parameter it ignores.
 */
fun interface ApiHandler {
    fun handle(call: ApiCall): ApiOutcome
}

/** Everything one API call needs: the app context, the caller's token, its args. */
class ApiCall(
    val context: Context,
    val token: SessionToken,
    val args: Map<String, String>,
)

/** Result of an API call: exactly one of [json] and [error] is set. */
class ApiOutcome private constructor(
    val json: String?,
    val error: BridgeError?,
) {
    val isOk: Boolean get() = error == null

    companion object {
        fun ok(json: String): ApiOutcome = ApiOutcome(json, null)

        fun ok(fields: JSONObject): ApiOutcome = ApiOutcome(fields.toString(), null)

        fun fail(
            kind: String,
            detail: String,
        ): ApiOutcome = ApiOutcome(null, BridgeError(kind, detail))
    }
}

/**
 * Maps an API name to its handler.
 *
 * Adding an API is one line here plus one file in `apis/`, which is the
 * extension model the architecture calls for.
 */
class ApiRouter {
    private val handlers = LinkedHashMap<String, ApiHandler>()

    /** Registers [handler] under [name]. A duplicate name is a programming error. */
    fun register(
        name: String,
        handler: ApiHandler,
    ): ApiRouter {
        require(name != API_AUTH_NAME) { "$API_AUTH_NAME is reserved" }
        require(handlers.put(name, handler) == null) { "api $name already registered" }

        return this
    }

    /**
     * Dispatches a call. [token] is the validated session token, already checked
     * for expiry; the scope check happens here.
     */
    fun dispatch(
        context: Context,
        token: SessionToken,
        api: String,
        args: Map<String, String>,
    ): ApiOutcome {
        val handler =
            handlers[api]
                ?: return ApiOutcome.fail(BridgeErrorKind.UNKNOWN_API, "no api named $api")

        if (!token.permits(api)) {
            return ApiOutcome.fail(
                BridgeErrorKind.FORBIDDEN,
                "session ${token.sessionId} is not scoped for $api",
            )
        }

        return try {
            handler.handle(ApiCall(context, token, args))
        } catch (t: Throwable) {
            ApiOutcome.fail(BridgeErrorKind.INTERNAL, "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
