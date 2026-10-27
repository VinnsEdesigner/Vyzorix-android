package com.vyzorix.capability.fd

/**
 * The control message that carries descriptors over a unix socket.
 *
 * A wrapper rather than a bare array so the wire shape lives in one place: the
 * sender and receiver agree on what a message is.
 */
data class AncillaryData(
    /** Descriptors carried by this message. The receiver owns them. */
    val descriptors: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is AncillaryData) return false
        return descriptors.contentEquals(other.descriptors)
    }

    override fun hashCode(): Int = descriptors.contentHashCode()

    override fun toString(): String = "AncillaryData(descriptors=${descriptors.size})"
}
