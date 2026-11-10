package com.vyzorix.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The device's single persistent notification, and the state it shows.
 *
 * This is the whole user-visible surface of a headless agent: there is no app screen,
 * and nothing about the agent is driven by opening the app. One notification, one id,
 * moved through three states:
 *
 *  - [needsStart]  — the accessibility service is on and the container is not running.
 *    The only action is **Start Vyzorix**, a button that starts the foreground
 *    service. Tapping it does *not* open the app; there is no content intent at all,
 *    so the body of the notification is inert.
 *  - [waiting]     — the container is running and the device is unclaimed. Shows the
 *    pairing code to type at the web page.
 *  - [running]     — the device is claimed and configured. Shows the device name.
 *
 * One id ([NOTIFICATION_ID]) across all states, so a state change *replaces* the
 * notification rather than stacking a second one. The same id is the foreground
 * service's, so the service's own notification and this are the same object — a
 * device shows exactly one Vyzorix notification.
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
                .setStyle(
                    Notification
                        .BigTextStyle()
                        .bigText("Not running. Tap Start Vyzorix to begin."),
                ).addAction(startAction())
                .build(),
        )
    }

    /** The container is running and waiting to be claimed; show the pairing code. */
    fun waiting(code: String) {
        post(
            base()
                .setContentTitle("Vyzorix — waiting to be claimed")
                .setContentText("Enter code $code at vyzorix.com/pair")
                .setStyle(
                    Notification
                        .BigTextStyle()
                        .bigText("Enter code $code at vyzorix.com/pair"),
                ).build(),
        )
    }

    /** The device is claimed and configured. */
    fun running(name: String) {
        post(runningNotification(name))
    }

    /**
     * The "running" notification as an object, for `startForeground`.
     *
     * The foreground service must hand the system a Notification in the same call
     * that promotes it, so it cannot go through [post]. It builds the same object
     * [running] would, so the foreground notification and the state machine agree.
     */
    fun runningNotification(name: String = ""): Notification {
        val text = if (name.isBlank()) "Running." else "Running as $name."

        return base()
            .setContentTitle("Vyzorix — running")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build()
    }

    /**
     * The shared builder.
     *
     * `setOngoing(true)` makes it persistent; the content intent is deliberately
     * absent so a tap on the body cannot open anything. Only the explicit action in
     * [needsStart] does something, and it starts the *service*, never an activity.
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
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

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

        private const val ACTION_REQUEST_CODE = 1
    }
}
