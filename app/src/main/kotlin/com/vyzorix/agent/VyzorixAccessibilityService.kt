package com.vyzorix.agent

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.vyzorix.service.DeviceNotification

/**
 * The enable-gate: the operator turns this on in Settings > Accessibility, and that
 * act is what arms the agent and hides the launcher icon.
 *
 * ## Why accessibility, and what it is *not*
 *
 * This service observes nothing and acts on nothing. It does not read the screen,
 * inject input, or look at other apps. The inherited [onAccessibilityEvent] has no
 * body of its own because the base class requires the override; it does nothing with
 * the event. Its only job is to be a switch the operator flips once.
 *
 * That is the whole reason it exists: Android gives a normal app no reliable way to
 * run before the user has opened it (OEMs block BOOT_COMPLETED until first launch,
 * and background starts are restricted). An accessibility service is the one user
 * gesture that says "yes, run" without the user ever opening an app screen — and it
 * is the signal that lets this app retire its own launcher icon.
 *
 * ## What enabling it does
 *
 * 1. Hides the launcher icon (see [hideLauncherIcon]). The app is headless, so once
 *    it is armed there is nothing to open; the icon only exists to get the operator
 *    here in the first place.
 * 2. Posts the persistent notification in its
 *    [DeviceNotification.needsStart] state: **Start Vyzorix**. Enabling the
 *    service arms the agent; it does not start the engine. The engine starts when the
 *    operator taps that action, so starting is explicit and visible.
 *
 * Disabling the service reverses the first step — the icon comes back, so a device
 * can always be reached again.
 */
class VyzorixAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility enabled; hiding the launcher icon and arming the agent")

        hideLauncherIcon(hidden = true)
        DeviceNotification(this).needsStart()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Required override; intentionally empty. This service observes nothing.
    }

    override fun onInterrupt() {
        // Required override; intentionally empty. This service performs no feedback.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "accessibility disabled; restoring the launcher icon")

        hideLauncherIcon(hidden = false)

        return super.onUnbind(intent)
    }

    /**
     * Shows or hides the launcher icon.
     *
     * `COMPONENT_ENABLED_STATE_DISABLED` on the launcher alias removes the icon from
     * the app drawer without disabling the app: the accessibility service, the
     * foreground service, and the boot receiver all keep working, because only the
     * alias is disabled, not the package. That is the point — the app stays fully
     * functional and completely invisible.
     *
     * It targets [LaunchActivity]'s alias (see the manifest), not the activity class,
     * so the activity can still be started by an explicit intent if one is ever
     * needed. Only the launcher entry goes away.
     */
    private fun hideLauncherIcon(hidden: Boolean) {
        val alias = ComponentName(this, LAUNCHER_ALIAS)
        val state =
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }

        runCatching {
            packageManager.setComponentEnabledSetting(
                alias,
                state,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.w(TAG, "could not change the launcher icon state", it) }
    }

    private companion object {
        const val TAG = "VyzorixAccessibility"

        /** The launcher-visible alias declared in the manifest. */
        const val LAUNCHER_ALIAS = "com.vyzorix.agent.LauncherAlias"
    }
}
