package com.vyzorix.capability.usb

import java.util.concurrent.ConcurrentHashMap

/**
 * Holds open USB devices and their file descriptors.
 *
 * This is the USB twin of `PtyBroker`: it owns the one resource that matters — the
 * fd `UsbManager.openDevice()` returns — and hands a duplicate to a session, which
 * is the capability model's "the fd is the grant". A session
 * that does not hold the device's channel cannot write to it, and closing the
 * channel is what revokes access: the device's fd is closed, so nothing downstream
 * can use it.
 *
 * It talks to the platform only through [UsbHost], so the whole broker is testable
 * with a fake host and no device attached.
 */
class UsbBroker(
    private val host: UsbHost,
    private val maxOpen: Int = DEFAULT_MAX_OPEN,
) {
    /** One open device: the platform name and the fd handed out for it. */
    private class Open(
        val name: String,
        val fd: Int,
    )

    private val open = ConcurrentHashMap<String, Open>()

    /** Every attached device, permission state included. Never throws. */
    fun devices(): List<UsbDeviceInfo> = host.devices()

    /**
     * Opens the device called [name] and records its fd.
     *
     * Idempotent: a second call for an already-open device returns the existing fd
     * rather than opening again, because two fds for one device would be two
     * capabilities to revoke and one would leak.
     */
    fun attach(name: String): UsbResult<Int> {
        open[name]?.let { return UsbResult.Ok(it.fd) }

        if (open.size >= maxOpen) {
            return UsbResult.Err(
                UsbError(
                    UsbError.Kind.TOO_MANY_OPEN,
                    "already holding $maxOpen open usb device(s)",
                ),
            )
        }

        val fd = host.open(name)
        if (fd < 0) {
            return UsbResult.Err(
                UsbError(
                    UsbError.Kind.OPEN_FAILED,
                    "could not open $name (no such device, or permission not granted)",
                ),
            )
        }

        open[name] = Open(name, fd)

        return UsbResult.Ok(fd)
    }

    /**
     * Closes [name] and forgets it. Idempotent: closing a device that is not open
     * is not an error, because a session close races the device being unplugged.
     */
    fun detach(name: String): UsbResult<Unit> {
        val entry = open.remove(name) ?: return UsbResult.Ok(Unit)

        host.close(entry.name)

        return UsbResult.Ok(Unit)
    }

    /** True when [name] is currently open. */
    fun isOpen(name: String): Boolean = open.containsKey(name)

    /** How many devices are open. Used by the service's diagnostics. */
    fun openCount(): Int = open.size

    companion object {
        /**
         * Open devices held at once.
         *
         * A device is a phone: it has one OTG port, and a hub is unusual. Four is
         * generous enough for a hub and small enough that a leaked fd is bounded.
         */
        const val DEFAULT_MAX_OPEN = 4
    }
}
