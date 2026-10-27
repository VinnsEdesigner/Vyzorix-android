package com.vyzorix.capability.fd

import com.vyzorix.capability.NativePty
import com.vyzorix.capability.PtyError
import com.vyzorix.capability.PtyResult
import com.vyzorix.capability.asResult

/**
 * Sends and receives file descriptors over a connected unix socket.
 *
 * This is how a capability crosses a process boundary: the broker holds a master
 * PTY fd (later a USB device fd), and hands a duplicate to the Debian-side
 * process. The receiver gets a working descriptor without opening a device node,
 * which matters because the receiver runs inside proot where device nodes are not
 * reliably reachable.
 *
 * Descriptors sent this way are duplicated into the receiver by the kernel. Both
 * sides hold their own copy and must close independently.
 */
object FileDescriptorPasser {

    /**
     * Sends descriptors over [socketFd].
     *
     * The socket must be connected (a `socketpair` end, or an accepted
     * connection). Sending on an unconnected socket fails with
     * `FD_PASS_FAILED`.
     */
    fun send(socketFd: Int, descriptors: IntArray): PtyResult<Unit> {
        if (socketFd < 0) {
            return PtyResult.Err(
                PtyError(PtyError.Kind.INVALID_ARGUMENT, "socket fd is $socketFd"),
            )
        }
        if (descriptors.isEmpty()) {
            return PtyResult.Err(
                PtyError(PtyError.Kind.INVALID_ARGUMENT, "no descriptors to send"),
            )
        }

        return NativePty.sendFds(socketFd, descriptors).asResult { }
    }

    /**
     * Receives descriptors from [socketFd], waiting at most [timeoutMs].
     *
     * Returns [PtyError.Kind.TIMED_OUT] if nothing arrived; the caller owns any
     * descriptors returned in [AncillaryData.descriptors].
     */
    fun receive(socketFd: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): PtyResult<AncillaryData> {
        if (socketFd < 0) {
            return PtyResult.Err(
                PtyError(PtyError.Kind.INVALID_ARGUMENT, "socket fd is $socketFd"),
            )
        }

        return NativePty.receiveFds(socketFd, timeoutMs).asResult { raw ->
            AncillaryData(descriptors = raw.fds ?: IntArray(0), payload = raw.bytes ?: ByteArray(0))
        }
    }

    const val DEFAULT_TIMEOUT_MS = 5000
}
