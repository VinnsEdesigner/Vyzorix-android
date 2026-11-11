package com.vyzorix.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 *  - [running]               — claimed and running. Says whether the connection is up:
 *                              "Connection stable and running" or "No network connection,
 *                              but still running".
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
    /** The container is off and waiting for the operator to start it. */
    fun needsStart() {
        post(
            base()
                .setContentTitle("Vyzorix")
                .setContentText("Not running. Tap Start Vyzorix to begin.")
                .setBigText("Not running. Tap Start Vyzorix to begin.")
                .addAction(startAction())
                .build(),
        )
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

    /** The running line, with the network qualifier the operator asked for. */
    private fun runningText(
        name: String,
        networkUp: Boolean,
    ): String {
        val who = if (name.isBlank()) "" else " as $name"

        return if (networkUp) {
            "Connection stable and running$who."
        } else {
            "No network connection, but still running$who."
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

        /** The brand accent. `setColor` tints the icon, app name and emphasis text. */
        val BURGUNDY: Int = Color.rgb(128, 0, 32)

        private const val ACTION_REQUEST_CODE = 1
        private const val PROGRESS_MAX = 100
    }
}
