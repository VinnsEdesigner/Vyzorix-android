package com.vyzorix.connector.enroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * The enrollment notification: the device's only user-visible surface.
 *
 * There is no app UI — the web page is the UI. The device shows a system
 * notification carrying the pairing code, the operator types it at
 * `vyzorix.com/pair`, and the notification updates when the device is configured.
 * See doc/REGISTRATION_CONTRACT.md §3.
 *
 * The channel is IMPORTANCE_DEFAULT rather than LOW: this is the one thing the user
 * must act on, and a silent notification would defeat the flow. It is not HIGH,
 * because the code is not urgent enough to interrupt — a buzz would be noise.
 *
 * Posting needs `POST_NOTIFICATIONS` on API 33+. The app requests it on first
 * launch; a device that has not granted it still enrolls (the server side works),
 * it simply cannot show the code, which the caller surfaces as a degraded state.
 */
class PairingNotification(
    private val context: Context,
) {
    /**
     * Posts or updates the notification with the pairing code.
     *
     * Uses the same notification id each time, so an update replaces rather than
     * stacks — a device that re-enrolls (a new code after expiry) shows one
     * notification, not a growing list.
     */
    fun show(code: PairingCode) {
        ensureChannel()

        val notification =
            builder()
                .setContentTitle("Vyzorix setup")
                .setContentText("Enter code ${code.display} at vyzorix.com/pair")
                .setStyle(
                    Notification
                        .BigTextStyle()
                        .bigText("Enter code ${code.display} at vyzorix.com/pair"),
                ).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build()

        post(notification)
    }

    /** Updates the notification once the device is claimed and configured. */
    fun showConfigured(name: String) {
        ensureChannel()

        val text = if (name.isBlank()) "Vyzorix is running." else "Vyzorix is running as $name."

        val notification =
            builder()
                .setContentTitle("Vyzorix")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build()

        post(notification)
    }

    /** Removes the notification (for example, when enrollment is refused for good). */
    fun clear() {
        notificationManager()?.cancel(NOTIFICATION_ID)
    }

    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

    private fun post(notification: Notification) {
        // The permission may be missing on API 33+; a SecurityException here must not
        // crash the service, because enrollment itself has already succeeded.
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
                "Vyzorix setup",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        /** Distinct from the foreground service's channel and id. */
        const val CHANNEL_ID = "vyzorix-enrollment"
        const val NOTIFICATION_ID = 2
    }
}
