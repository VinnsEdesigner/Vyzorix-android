package com.vyzorix.bridge

import android.content.Context
import android.os.Build
import com.vyzorix.permissions.PermissionRegistry
import com.vyzorix.permissions.PermissionRequester
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
 *
 * Permission enforcement lives here, not in the handlers. The router consults
 * [PermissionRegistry] for the API being called and refuses it when the runtime
 * permission is missing, so a handler never requests a permission itself and the
 * 120-API surface cannot scatter that logic. The dialog path is separate: it is
 * driven from the app, and a call over the socket is either permitted or refused.
 */
class ApiRouter(
    private val permissions: PermissionRequester? = null,
) {
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
     * Names of every registered API, sorted.
     *
     * Used to mint a session's allowlist on the device: a shell session may call
     * every API the bridge serves, and the set is stated explicitly rather than
     * left empty, because an empty allowlist permits nothing (boundary contract
     * §7). When the server resolves a per-session allowlist, that replaces this.
     */
    fun names(): List<String> = handlers.keys.sorted()

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

        // The single permission gate. A missing runtime permission is a refusal,
        // not a prompt: a request over the socket has no Activity to attach a
        // dialog to, and blocking a dispatch thread on a dialog that cannot appear
        // would stall the pool. The app asks for permissions before it needs them.
        if (!permissionGranted(api)) {
            return ApiOutcome.fail(
                BridgeErrorKind.FORBIDDEN,
                "runtime permission not granted for $api",
            )
        }

        return try {
            handler.handle(ApiCall(context, token, args))
        } catch (t: Throwable) {
            ApiOutcome.fail(BridgeErrorKind.INTERNAL, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * True when the API's runtime permission is held, or it needs none.
     *
     * Without a requester (a test, or a build with no app module) an API that needs
     * a permission is refused: fail-closed, matching the token rule.
     *
     * The SDK rule lives in [PermissionRegistry.needsNoPermission], so this method
     * and the requester cannot disagree about whether a permission applies on the
     * running device.
     */
    private fun permissionGranted(api: String): Boolean {
        if (PermissionRegistry.needsNoPermission(api)) return true

        // The SDK rule lives in the registry, so this method and the requester cannot
        // disagree about whether a permission applies on the running device.
        if (!PermissionRegistry.requiresPermissionOn(api, Build.VERSION.SDK_INT)) return true

        val def = PermissionRegistry.forApi(api) ?: return true
        val requester = permissions ?: return false

        return requester.isGranted(def.androidPermission)
    }
}
