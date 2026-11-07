package com.vyzorix.capability.usb

/**
 * One attached USB device, as the app can see it through `UsbManager`.
 *
 * This is the platform's view, not a parsed descriptor: `vendorId`/`productId`
 * identify the device, and the string fields are whatever the device reports (and
 * may be empty). Nothing here needs a permission to read — `serial` is the
 * exception and is only populated once permission is held.
 */
data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val productName: String,
    val manufacturerName: String,
    val serial: String,
    val interfaceCount: Int,
    val hasPermission: Boolean,
)

/**
 * The seam over `UsbManager`.
 *
 * `UsbManager` is a final Android class, so the broker cannot be unit-tested
 * against it directly. This interface is the whole surface the broker needs, and
 * a fake implementation drives the tests — the same shape `PtySessionPort` uses
 * for the terminal. The real implementation is the only place that touches the
 * framework.
 */
interface UsbHost {
    /** Every attached device, permission state included. Never throws. */
    fun devices(): List<UsbDeviceInfo>

    /**
     * Opens [name] and returns its file descriptor, or a negative value on failure
     * (no such device, or permission not held).
     *
     * The fd is what the capability model hands to the container; `openDevice`
     * requires the permission to have been granted first.
     */
    fun open(name: String): Int

    /** Closes a device previously opened with [open]. Idempotent. */
    fun close(name: String)
}
