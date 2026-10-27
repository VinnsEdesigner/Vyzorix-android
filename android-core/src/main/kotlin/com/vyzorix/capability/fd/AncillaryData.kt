package com.vyzorix.capability.fd

/**
 * The control-message payload that carries descriptors over a unix socket.
 *
 * A thin wrapper rather than a raw array so the wire shape lives in one place:
 * the receiving side and the sending side agree on what a "message" is, and a
 * future header field has an obvious home.
 */
data class AncillaryData(
    /** Descriptors carried by this message. The receiver owns them. */
    val descriptors: IntArray,
    /** Non-descriptor bytes accompanying the message. */
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (other !is AncillaryData) return false
        return descriptors.contentEquals(other.descriptors) && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * descriptors.contentHashCode() + payload.contentHashCode()

    override fun toString(): String =
        "AncillaryData(descriptors=${descriptors.size}, payload=${payload.size}B)"
}
