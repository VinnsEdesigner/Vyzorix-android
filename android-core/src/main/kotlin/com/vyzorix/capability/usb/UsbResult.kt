package com.vyzorix.capability.usb

/** Outcome of a USB operation. Failures arrive as [Err]; nothing throws. */
sealed interface UsbResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : UsbResult<T>

    data class Err(
        val error: UsbError,
    ) : UsbResult<Nothing>
}

/** Why a USB operation failed. Mirrors `PtyError`'s shape for consistency. */
data class UsbError(
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        /** The device is not attached, or permission for it is not held. */
        OPEN_FAILED,

        /** The open-device cap is reached. */
        TOO_MANY_OPEN,

        /** No such device by that name. */
        NO_SUCH_DEVICE,

        /** The platform has no USB service (rare; a broken image). */
        NO_USB_SERVICE,

        INTERNAL,
    }

    override fun toString(): String = "${kind.name.lowercase()}: $detail"
}
