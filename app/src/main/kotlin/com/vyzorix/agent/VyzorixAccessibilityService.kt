package com.vyzorix.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.vyzorix.service.DeviceNotification

/**
 * The enable-gate: the user turns this on in Settings > Accessibility, and that
 * act is what arms the agent and hides the launcher icon.
 *
 * ## Why accessibility, and what it is *not*
 *
 * This service observes nothing and acts on nothing. It does not read the screen,
 * inject input, or look at other apps. The inherited [onAccessibilityEvent] has no
 * body of its own because the base class requires the override; it does nothing with
 * the event. Its only job is to be a switch the user flips once.
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
 *    it is armed there is nothing to open; the icon only exists to get the user
 *    here in the first place.
 * 2. Posts the persistent notification in its
 *    [DeviceNotification.needsStart] state: **Start Vyzorix**. Enabling the
 *    service arms the agent; it does not start the container. The container starts when the
 *    user taps that action, so starting is explicit and visible.
 *
 * Disabling the service reverses the first step — the icon comes back, so a device
 * can always be reached again.
 */
class VyzorixAccessibilityService : AccessibilityService() {
    override fun onCreate() {
        super.onCreate()

        // Re-assert the icon state as early as possible. On Android 13+ the service is
        // re-bound when the user flips the switch, but an OEM may deliver the
        // binding through a different path; doing it here as well as in
        // onServiceConnected means the icon hides on whichever fires first.
        LauncherIcon.setHidden(this, hidden = true)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility enabled; hiding the launcher icon and arming the agent")

        LauncherIcon.setHidden(this, hidden = true)
        DeviceNotification(this).needsStart()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Required override; intentionally empty. This service observes nothing.
    }

    override fun onInterrupt() {
        // Required override; intentionally empty. This service performs no feedback.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "accessibility disabled; restoring the launcher icon")

        LauncherIcon.setHidden(this, hidden = false)

        return super.onUnbind(intent)
    }

    private companion object {
        const val TAG = "VyzorixAccessibility"
    }
}
