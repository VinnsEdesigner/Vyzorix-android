package com.vyzorix.capability.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * The real [UsbHost]: the only file that touches `UsbManager`.
 *
 * Everything here is best-effort and never throws, because a USB API that throws
 * on a device with no OTG port is worse than one that reports an empty list. The
 * broker and the handlers work against [UsbHost], so this file stays a thin
 * translation of the framework's shape into ours.
 *
 * ## What this can and cannot do unrooted
 *
 * `UsbManager.getDeviceList()` and `hasPermission()` need no permission.
 * `openDevice()` needs the app to hold the platform's USB permission for that
 * device, which the user grants through a dialog the app must request with a
 * `PendingIntent`. `openDevice()` returns null when the permission is absent —
 * so [open] reports failure rather than blocking on a dialog it cannot show from
 * a socket dispatch thread.
 *
 * `getSerialNumber()` needs the permission too, and throws without it, so [serial]
 * is guarded and empty when unavailable.
 */
class AndroidUsbHost(
    private val context: Context,
) : UsbHost {
    override fun devices(): List<UsbDeviceInfo> {
        val manager = manager() ?: return emptyList()

        return try {
            manager.deviceList.map { (name, device) -> describe(manager, name, device) }
        } catch (t: Throwable) {
            // A framework failure here is reported as "no devices" rather than
            // propagated: the caller's job is to tell an operator what is attached,
            // and an exception would read as a broken API.
            Log.w(TAG, "could not enumerate usb devices", t)

            emptyList()
        }
    }

    override fun open(name: String): Int {
        val manager = manager() ?: return NO_FD

        val device = manager.deviceList[name] ?: return NO_FD
        if (!manager.hasPermission(device)) {
            // The permission dialog is the app's to raise, not this call's. Opening
            // without it returns null; report that as a failed open.
            return NO_FD
        }

        return try {
            val connection = manager.openDevice(device)
            if (connection == null) {
                NO_FD
            } else {
                connections[name] = connection
                connection.fileDescriptor
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not open usb device $name", t)

            NO_FD
        }
    }

    override fun close(name: String) {
        val connection = connections.remove(name) ?: return

        try {
            connection.close()
        } catch (t: Throwable) {
            Log.w(TAG, "could not close usb device $name", t)
        }
    }

    /** The open connections, so [close] can release them. One per device name. */
    private val connections = mutableMapOf<String, android.hardware.usb.UsbDeviceConnection>()

    @Suppress("SwallowedException") // A missing USB service is reported as null.
    private fun manager(): UsbManager? =
        try {
            context.getSystemService(Context.USB_SERVICE) as? UsbManager
        } catch (t: Throwable) {
            null
        }

    @Suppress("SwallowedException") // Serial needs permission; absent is reported empty.
    private fun describe(
        manager: UsbManager,
        name: String,
        device: UsbDevice,
    ): UsbDeviceInfo {
        val serial =
            try {
                if (manager.hasPermission(device)) device.serialNumber.orEmpty() else ""
            } catch (t: Throwable) {
                ""
            }

        return UsbDeviceInfo(
            name = name,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            productName = device.productName.orEmpty(),
            manufacturerName = device.manufacturerName.orEmpty(),
            serial = serial,
            interfaceCount = device.interfaceCount,
            hasPermission = manager.hasPermission(device),
        )
    }

    private companion object {
        const val TAG = "UsbHost"

        /** No descriptor. A real fd is never negative. */
        const val NO_FD = -1
    }
}
