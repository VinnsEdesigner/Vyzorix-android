package com.vyzorix.agent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Shows or hides the launcher icon by enabling or disabling the launcher
 * activity-alias declared in the manifest.
 *
 * ## Why the icon hides at all
 *
 * The agent is headless: once it is armed there is nothing to open, and the icon
 * only exists to bring the operator to the accessibility screen the first time. So
 * the icon is retired when the accessibility service is enabled and restored when it
 * is disabled — the operator can always reach the app again by turning the switch off.
 *
 * ## Why this is not just done once, in the service
 *
 * The hidden state used to be asserted only from
 * [VyzorixAccessibilityService.onServiceConnected]. That is a single moment, and it is
 * not always the right one: the state is a property of the package that any reinstall
 * or update resets, an OEM launcher may not refresh its drawer when the component
 * flips, and the service may not be re-bound on every process start. The result was an
 * icon that "never hides" on a real device even though the switch was on.
 *
 * So the state is [reconcile]d from the application's `onCreate` — which runs on every
 * process start, including the one the accessibility service is bound into — and
 * re-asserted from the service. Re-asserting an already-correct state is cheap and
 * idempotent, and it is what makes the icon track the switch rather than the timing of
 * one callback.
 */
object LauncherIcon {
    private const val TAG = "VyzorixLauncherIcon"

    /** The launcher-visible alias declared in the manifest. */
    const val ALIAS = "com.vyzorix.agent.LauncherAlias"

    /**
     * Sets the icon's state from whether the agent is armed: hidden while the
     * accessibility service is enabled, visible otherwise.
     *
     * Called on every process start. Idempotent, so calling it when the state is
     * already correct does nothing observable.
     */
    fun reconcile(context: Context) {
        setHidden(context, hidden = isArmed(context))
    }

    /** Shows or hides the icon. */
    fun setHidden(
        context: Context,
        hidden: Boolean,
    ) {
        val alias = ComponentName(context.packageName, ALIAS)
        val state =
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }

        // DONT_KILL_APP: only the alias changes, never the package, so the foreground
        // service, the bridge and any live session keep running.
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                alias,
                state,
                PackageManager.DONT_KILL_APP,
            )
        }.onSuccess {
            Log.i(TAG, "launcher icon hidden=$hidden")
        }.onFailure {
            // A failure here is not fatal — the agent runs either way — but it is the
            // whole reason the icon would stay visible, so it must be visible in the
            // log rather than swallowed.
            Log.w(TAG, "could not change the launcher icon state to hidden=$hidden", it)
        }
    }

    /**
     * True when this app's accessibility service is enabled in the secure settings.
     *
     * Read from `Settings.Secure` rather than from a service callback, so it is
     * answerable from `Application.onCreate` before any service is connected — which
     * is exactly the point: the icon state is reconciled at process start, not only
     * when a service happens to be bound.
     */
    fun isArmed(context: Context): Boolean {
        val enabled =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

        val me =
            ComponentName(context, VyzorixAccessibilityService::class.java)
                .flattenToString()

        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
