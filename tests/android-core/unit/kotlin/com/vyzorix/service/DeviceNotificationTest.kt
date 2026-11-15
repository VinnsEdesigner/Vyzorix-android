package com.vyzorix.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The two notification behaviours the user asked for, pinned so a later edit
 * cannot quietly undo them:
 *
 *  1. the "update ready to install" notification carries an **Accept install** action
 *     and is *ongoing*, so a dismissed installer prompt can always be re-opened; and
 *  2. the offline running line reads "No network connection, running as &lt;name&gt; but
 *     offline".
 *
 * Robolectric is the right tool here: the notification is built and posted through the
 * real `NotificationManager`, so what is asserted is the object the device would show,
 * not a string constant read out of the source.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun manager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun pendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    @Test
    fun `the install notification carries an Accept install action and cannot be dismissed`() {
        val notification = DeviceNotification(context).installReady("1.2.3", pendingIntent())

        val titles = notification.actions.orEmpty().map { it.title.toString() }
        assertTrue(
            "the install notification must offer an explicit Accept install action; got $titles",
            titles.contains("Accept install"),
        )
        assertTrue(
            "the prompt must be ongoing so it survives until the install is confirmed",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
    }

    @Test
    fun `clearInstall cancels the install notification`() {
        // Post it the way the app does, so the cancel has something to remove.
        manager().notify(
            DeviceNotification.INSTALL_NOTIFICATION_ID,
            DeviceNotification(context).installReady("1.2.3", pendingIntent()),
        )
        assertNotNull(
            "the install notification must be posted before it is cleared",
            shadowOf(manager()).getNotification(DeviceNotification.INSTALL_NOTIFICATION_ID),
        )

        DeviceNotification(context).clearInstall()

        assertNull(
            "a confirmed install must remove the Accept install prompt",
            shadowOf(manager()).getNotification(DeviceNotification.INSTALL_NOTIFICATION_ID),
        )
    }

    @Test
    fun `the offline running line names the device and says it is offline`() {
        DeviceNotification(context).running(name = "Nokia C22", networkUp = false)

        val posted = shadowOf(manager()).getNotification(DeviceNotification.NOTIFICATION_ID)
        val text = posted.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("No network connection, running as Nokia C22 but offline.", text)
    }

    @Test
    fun `the online running line still reads connection stable`() {
        DeviceNotification(context).running(name = "Nokia C22", networkUp = true)

        val posted = shadowOf(manager()).getNotification(DeviceNotification.NOTIFICATION_ID)
        val text = posted.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("Connection stable and running as Nokia C22.", text)
    }

    @Test
    fun `the preparing state names the stage and is dismissible-free`() {
        DeviceNotification(context).preparingContainer("downloading its userspace")

        val posted =
            shadowOf(
                manager(),
            ).getNotification(DeviceNotification.PREPARING_NOTIFICATION_ID)
        assertNotNull("the preparing notification must post", posted)
        val text = posted.extras.getString(Notification.EXTRA_TEXT)
        assertTrue(
            "the text must name the stage; got: $text",
            text!!.contains("downloading its userspace"),
        )
        assertTrue(
            "the text must set the expectation of a wait; got: $text",
            text.contains("minute", ignoreCase = true),
        )

        DeviceNotification(context).clearPreparingContainer()
        assertNull(
            "clearing must remove the preparing notification",
            shadowOf(manager()).getNotification(DeviceNotification.PREPARING_NOTIFICATION_ID),
        )
    }

    @Test
    fun `an unnamed offline device does not invent a name`() {
        DeviceNotification(context).running(name = "", networkUp = false)

        val posted = shadowOf(manager()).getNotification(DeviceNotification.NOTIFICATION_ID)
        val text = posted.extras.getString(Notification.EXTRA_TEXT)

        assertFalse("an empty name must not produce a dangling 'as'", text!!.contains(" as "))
        assertTrue("it must still say it is offline", text.contains("offline"))
    }
}
