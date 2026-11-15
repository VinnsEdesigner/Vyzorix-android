package com.vyzorix.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import com.vyzorix.R

/**
 * The device's single persistent notification, and the states it shows.
 *
 * This is the whole user-visible surface of a headless agent: there is no app screen,
 * and nothing about the agent is driven by opening the app. One notification, one id,
 * moved through its states:
 *
 *  - [needsStart]            — armed but not running. The only action is **Start Vyzorix**.
 *  - [startingNotification]  — the object `startForeground` needs at promotion.
 *  - [waiting]               — running and unclaimed; shows the pairing code.
 *  - [running]               — claimed and running. Names the device and says whether the
 *                              connection is up: "Connection stable and running as <name>"
 *                              or "No network connection, running as <name> but offline".
 *  - [downloadingUpdate]     — an OTA download in flight, with a progress bar.
 *  - [updateDownloaded]      — downloaded and verified; the installer is opening.
 *  - [applyingUpdate]        — installed; the app is coming back up and reconnecting.
 *
 * One id ([NOTIFICATION_ID]) across all states, so a state change *replaces* the
 * notification rather than stacking a second one. The same id is the foreground
 * service's, so the service's own notification and this are the same object.
 *
 * ## The colour
 *
 * Every state carries the same [BURGUNDY] accent, set with `setColor`. On Android the
 * notification's colour tints the small icon, the app name and the emphasis text, so a
 * single `setColor` gives the whole notification a burgundy identity without a custom
 * layout. It is applied in [base], so no state can forget it.
 */
class DeviceNotification(
    private val context: Context,
) {
    /** The container is off and waiting for the user to start it. */
    fun needsStart() {
        val builder =
            base()
                .setContentTitle("Vyzorix")
                .setContentText("Not running. Tap Start Vyzorix to begin.")
                .setBigText("Not running. Tap Start Vyzorix to begin.")
                .addAction(startAction())

        // Offer the admin grant only while it is not held: the action opens the
        // system's "Activate device admin app?" screen, which is the only way to grant
        // it, and it is noise once granted.
        if (!isDeviceAdmin()) {
            builder.addAction(adminAction())
        }

        post(builder.build())
    }

    /** True when this app is already a device administrator. */
    private fun isDeviceAdmin(): Boolean {
        val policy =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false

        return policy.isAdminActive(
            ComponentName(context.packageName, VyzorixDeviceAdminReceiver::class.java.name),
        )
    }

    /**
     * The "Grant admin" action: opens the device-admin activation screen.
     *
     * A device administrator is granted by the user, from a system screen; an app
     * cannot grant itself the role. So the action opens that screen — the same shape
     * as the accessibility hop — and the user confirms. Declining is fine: the
     * agent runs without the admin powers and reports them as unavailable.
     */
    private fun adminAction(): Notification.Action {
        val intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(context.packageName, VyzorixDeviceAdminReceiver::class.java.name),
                )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, ADMIN_REQUEST_CODE, intent, flags)

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Notification.Action.Builder(null, "Grant admin", pending)
            } else {
                @Suppress("DEPRECATION")
                Notification.Action.Builder(0, "Grant admin", pending)
            }

        return builder.build()
    }

    /**
     * The "starting" notification as an object, for `startForeground`.
     *
     * The foreground service must hand the system a Notification in the same call that
     * promotes it, so it cannot go through [post]. This builds the same object the
     * other states would, so the foreground notification and the state machine agree.
     */
    fun startingNotification(): Notification =
        base()
            .setContentTitle("Vyzorix")
            .setContentText("Starting…")
            .setBigText("Starting…")
            .build()

    /** Running and unclaimed; show the pairing code. */
    fun waiting(code: String) {
        val text = "Enter code $code at vyzorix.com/pair"
        post(
            base()
                .setContentTitle("Vyzorix — waiting to be claimed")
                .setContentText(text)
                .setBigText(text)
                .build(),
        )
    }

    /**
     * Claimed and running. [networkUp] is whether the device currently has a usable
     * network — the caller tracks the socket/connectivity, and this only renders it.
     */
    fun running(
        name: String,
        networkUp: Boolean = true,
    ) {
        val text = runningText(name, networkUp)
        post(
            base()
                .setContentTitle("Vyzorix — running")
                .setContentText(text)
                .setBigText(text)
                .build(),
        )
    }

    /**
     * An OTA download is in flight. [percent] is 0–100; a negative value means the
     * total size is unknown, so an indeterminate bar is shown rather than a fake one.
     */
    fun downloadingUpdate(
        version: String,
        percent: Int,
    ) {
        val text =
            if (percent >= 0) {
                "Downloading update $version — $percent%"
            } else {
                "Downloading update $version…"
            }

        val builder =
            base()
                .setContentTitle("Vyzorix — downloading update")
                .setContentText(text)
                .setBigText(text)

        if (percent >= 0) {
            builder.setProgress(PROGRESS_MAX, percent, false)
        } else {
            builder.setProgress(PROGRESS_MAX, 0, true)
        }

        post(builder.build())
    }

    /**
     * The update is downloaded and verified, and the system installer is being opened.
     *
     * A third-party app cannot install a package silently, so the agent has done all
     * it can: the verified APK is handed to the installer, which is what appears next.
     */
    fun updateDownloaded(version: String) {
        val text = "Version $version downloaded. Opening the installer…"
        post(
            base()
                .setContentTitle("Vyzorix — update downloaded")
                .setContentText(text)
                .setBigText(text)
                .build(),
        )
    }

    /**
     * Builds the "update ready to install" notification.
     *
     * This is the headless app's one route to an install: Android 10+ blocks a
     * background service from starting the installer directly, so the launch has to
     * come from the user tapping this. [tapIntent] opens the installer.
     *
     * ## The "Accept install" action, and why it stays
     *
     * The system installer's own prompt can be dismissed by accident, and once it is
     * gone there is no way to bring it back — the APK is staged but the user has
     * no control left. So the action is explicit and the notification is
     * **ongoing**: it cannot be swiped away, and every tap of **Accept install**
     * re-opens the installer for the same staged, verified APK. It is re-posted on
     * each OTA pass (see `UpdateChecker`), so the button is present until the install
     * is actually confirmed — which the next process shows by reporting the newer
     * version. Only then is it cleared ([clearInstall]).
     *
     * It is a *second* notification id so it does not replace the persistent status
     * one, and a distinct channel at DEFAULT importance, because a prompt the user
     * must tap must be visible and must not be silenced by the status channel's low
     * importance. `setOnlyAlertOnce` keeps a re-post from buzzing again.
     */
    fun installReady(
        version: String,
        tapIntent: PendingIntent,
    ): Notification {
        ensureInstallChannel()

        return Notification
            .Builder(context, INSTALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.vyzorix_notification_icon)
            .setColor(BURGUNDY)
            .setContentTitle("Vyzorix — update ready to install")
            .setContentText("Tap to install version $version")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Version $version is downloaded and verified. Tap Accept install to " +
                        "open the installer.",
                ),
            ).setContentIntent(tapIntent)
            .addAction(installAction(tapIntent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Cancels the "update ready to install" notification.
     *
     * Called once the running build is no longer older than the offered one — the
     * install went through. Until this runs the notification (and its Accept install
     * action) stays, so an install that was cancelled can always be retried.
     */
    fun clearInstall() {
        runCatching {
            notificationManager()?.cancel(INSTALL_NOTIFICATION_ID)
        }
    }

    /**
     * The container's userspace is being installed — a first-boot download that takes
     * minutes on a slow link.
     *
     * This exists because the alternative was a silent stall: on a fresh install the
     * agent has no container yet, so the terminal produces nothing and the status
     * notification sat at "Starting…" with no explanation, which reads as a hang. [stage]
     * says which part is being fetched so the wait is attributable, and the bar is
     * indeterminate (the byte count is not known until the response headers arrive).
     *
     * It is its own notification id and channel, so it does **not** fight the persistent
     * status notification, which the foreground service owns and the cloud connection
     * moves through its own states. Both are visible at once: "preparing" says why the
     * agent is not up yet, and the status one says what the service is doing.
     */
    fun preparingContainer(stage: String) {
        ensurePreparingChannel()

        val text = "Setting up the container — $stage. This can take a few minutes…"
        val notification =
            Notification
                .Builder(context, PREPARING_CHANNEL_ID)
                .setSmallIcon(R.drawable.vyzorix_notification_icon)
                .setColor(BURGUNDY)
                .setContentTitle("Vyzorix — preparing")
                .setContentText(text)
                .setStyle(
                    Notification.BigTextStyle().bigText(
                        "Setting up the container — $stage.\n\n" +
                            "The first run downloads the container's userspace, which takes " +
                            "a few minutes on a slow connection. The agent starts once it is " +
                            "ready.",
                    ),
                ).setProgress(PROGRESS_MAX, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()

        runCatching {
            notificationManager()?.notify(PREPARING_NOTIFICATION_ID, notification)
        }
    }

    /** Removes the "preparing" notification once the container is up (or has failed). */
    fun clearPreparingContainer() {
        runCatching {
            notificationManager()?.cancel(PREPARING_NOTIFICATION_ID)
        }
    }

    private fun ensurePreparingChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(PREPARING_CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                PREPARING_CHANNEL_ID,
                "Vyzorix setup",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /** The "Accept install" action: re-opens the installer for the staged APK. */
    private fun installAction(tapIntent: PendingIntent): Notification.Action {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Notification.Action.Builder(null, "Accept install", tapIntent)
            } else {
                @Suppress("DEPRECATION")
                Notification.Action.Builder(0, "Accept install", tapIntent)
            }

        return builder.build()
    }

    private fun ensureInstallChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(INSTALL_CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                INSTALL_CHANNEL_ID,
                "Vyzorix updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** Installed; the app is coming back up and reconnecting. */
    fun applyingUpdate(version: String) {
        post(applyingUpdateNotification(version))
    }

    /**
     * The "applying update" notification as an object, for `startForeground`.
     *
     * The service posts this from its own `onCreate` when it starts up after an
     * install (the update marker), so it must build an object the same way
     * [startingNotification] does.
     */
    fun applyingUpdateNotification(version: String): Notification {
        val text = "Version $version installed. One moment — reconnecting…"

        return base()
            .setContentTitle("Vyzorix — updating")
            .setContentText(text)
            .setBigText(text)
            .setProgress(PROGRESS_MAX, 0, true)
            .build()
    }

    /** The running line, with the network qualifier the user asked for. */
    private fun runningText(
        name: String,
        networkUp: Boolean,
    ): String {
        val who = if (name.isBlank()) "" else " as $name"

        return if (networkUp) {
            "Connection stable and running$who."
        } else {
            "No network connection, running$who but offline."
        }
    }

    /**
     * The shared builder: the burgundy accent, the icon, persistence, and the channel.
     *
     * `setOngoing(true)` makes it persistent; the content intent is deliberately
     * absent so a tap on the body cannot open anything. Only the explicit actions do
     * something, and the Start action starts the *service*, never an activity.
     */
    private fun base(): Notification.Builder {
        ensureChannel()

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

        return builder
            .setSmallIcon(R.drawable.vyzorix_notification_icon)
            .setColor(BURGUNDY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    /** A small helper for the BigText style the states share. */
    private fun Notification.Builder.setBigText(text: String): Notification.Builder =
        setStyle(Notification.BigTextStyle().bigText(text))

    /** The "Start Vyzorix" action: a foreground-service start, never an activity. */
    private fun startAction(): Notification.Action {
        val intent =
            Intent(context, VyzorixForegroundService::class.java)
                .setAction(VyzorixForegroundService.ACTION_START)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, ACTION_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(context, ACTION_REQUEST_CODE, intent, flags)
            }

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Notification.Action.Builder(null, "Start Vyzorix", pending)
            } else {
                @Suppress("DEPRECATION")
                Notification.Action.Builder(0, "Start Vyzorix", pending)
            }

        return builder.build()
    }

    private fun post(notification: Notification) {
        // POST_NOTIFICATIONS may be ungranted on API 33+; a refused post must not
        // crash the caller, because the agent itself still works.
        runCatching {
            notificationManager()?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationManager(): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Vyzorix",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        /** The one channel and the one notification id this device ever shows. */
        const val CHANNEL_ID = "vyzorix"
        const val NOTIFICATION_ID = 1

        /**
         * The "tap to install" notification, a second id so it does not replace the
         * persistent status notification.
         */
        const val INSTALL_NOTIFICATION_ID = 2

        /** The install notification's channel, created on demand. */
        const val INSTALL_CHANNEL_ID = "vyzorix-install"

        /**
         * The "preparing the container" notification, a third id and channel. Its own id
         * so a first-boot download does not overwrite the persistent status notification,
         * which the foreground service owns.
         */
        const val PREPARING_NOTIFICATION_ID = 3
        const val PREPARING_CHANNEL_ID = "vyzorix-preparing"

        /** The brand accent. `setColor` tints the icon, app name and emphasis text. */
        val BURGUNDY: Int = Color.rgb(128, 0, 32)

        private const val ACTION_REQUEST_CODE = 1
        private const val ADMIN_REQUEST_CODE = 2
        private const val PROGRESS_MAX = 100
    }
}
