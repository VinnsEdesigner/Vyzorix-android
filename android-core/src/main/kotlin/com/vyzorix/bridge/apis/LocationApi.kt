package com.vyzorix.bridge.apis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import com.vyzorix.bridge.ApiCall
import com.vyzorix.bridge.ApiHandler
import com.vyzorix.bridge.ApiOutcome
import com.vyzorix.bridge.ApiTags
import com.vyzorix.bridge.BridgeErrorKind
import com.vyzorix.bridge.VyzorixApi
import org.json.JSONObject

/**
 * The device's last known location, and whether a provider can serve one.
 *
 * The **last known** fix rather than a fresh one. Asking a provider for a current
 * position means subscribing and waiting, which would block a dispatch thread on a
 * satellite — the router runs handlers on a pool, and a handler that waits for a
 * fix stalls the pool for every other call. The last known fix is already in the
 * framework's cache, so it is a read, which is what this API is.
 *
 * `ACCESS_FINE_LOCATION` is a dangerous permission. It is required here because
 * `getLastKnownLocation` returns nothing useful without it, and the router refuses
 * the call before the handler runs when the grant is absent — so reaching the body
 * means the permission is held. A null fix is then a real answer: the device has
 * the permission but no cached position yet, which a user should see as
 * "unknown", not as an error.
 */
@VyzorixApi(
    tags = [
        ApiTags.DATA_PRIVATE,
        ApiTags.OP_READ,
        ApiTags.PROTECTION_DANGEROUS,
        ApiTags.VISIBILITY_PRIVATE,
    ],
)
object LocationApi : ApiHandler {

    const val NAME = "location"

    /**
     * The `handle` body reads the last known fix, which needs a location permission.
     *
     * Lint's `MissingPermission` is suppressed rather than propagated because the
     * check is not local: `ApiRouter.dispatch` consults `PermissionRegistry` and
     * refuses the call before any handler runs, so reaching this body means the
     * grant is held. Lint cannot see across the router's interface dispatch, so the
     * suppression states the invariant where it is enforced. [lastKnown] keeps its
     * own `@RequiresPermission` annotation as the readable declaration.
     */
    @SuppressLint("MissingPermission")
    override fun handle(call: ApiCall): ApiOutcome {
        val manager =
            call.context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "no location service",
                )

        // Providers that exist on this device, so "no fix" can be told from
        // "no provider at all". An emulator with no location set is the first case.
        val providers =
            try {
                manager.allProviders
            } catch (e: Exception) {
                return ApiOutcome.fail(
                    BridgeErrorKind.INTERNAL,
                    "location providers unavailable: ${e.message}",
                )
            }

        // The most accurate cached fix across providers. GPS first, then network:
        // a network fix is coarse but better than nothing, and reporting the best
        // available is the honest answer to "where is this device".
        var best: android.location.Location? = null
        for (provider in ORDERED_PROVIDERS) {
            if (!providers.contains(provider)) continue

            val fix = lastKnown(manager, provider)

            // Most recent wins: a fresh coarse fix is more useful than a stale exact one.
            val current = best
            if (fix != null && (current == null || fix.time > current.time)) {
                best = fix
            }
        }

        val result =
            JSONObject()
                .put("available", true)
                .put("providers", providers.size)
                .put("hasFix", best != null)

        best?.let { fix ->
            result
                .put("latitude", fix.latitude)
                .put("longitude", fix.longitude)
                .put("accuracyMeters", fix.accuracy.toDouble())
                .put("fixAgeMillis", System.currentTimeMillis() - fix.time)
                .put("provider", fix.provider.orEmpty())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                result.put("verticalAccuracyMeters", fix.verticalAccuracyMeters.toDouble())
            }
        }

        return ApiOutcome.ok(result)
    }

    /**
     * The cached fix for one provider, or null when it has none.
     *
     * Annotated because lint cannot see the router's permission gate: the check
     * happens in `ApiRouter.permissionGranted`, which refuses the call before this
     * handler runs, so by the time this executes the grant is held. The annotation
     * states that invariant where lint can read it rather than suppressing the
     * warning at the call site.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun lastKnown(
        manager: LocationManager,
        provider: String,
    ): android.location.Location? =
        try {
            manager.getLastKnownLocation(provider)
        } catch (e: SecurityException) {
            // The router checked the permission, so this is the platform disagreeing
            // about a provider's own requirement. Skip it rather than failing the
            // whole call: another provider may answer. Logged so the skip is visible.
            Log.d(TAG, "provider $provider refused the read: ${e.message}")
            null
        }

    /**
     * Providers in preference order. GPS is the most accurate; network and passive
     * are fallbacks for a device indoors or with no GPS hardware.
     */
    private val ORDERED_PROVIDERS =
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )

    private const val TAG = "LocationApi"
}
