package com.vyzorix.capability

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One terminal session.
 *
 * Threading contract
 * ------------------
 * The master fd is owned by this object. Exactly **one** thread may read it
 * (the reader loop started by the owner), and any thread may write to it.
 *
 * Closing is the dangerous operation: closing an fd while another thread is
 * blocked in a read on it does not fail cleanly — the fd number is released and
 * can be handed to an unrelated file, so the reader then reads from the wrong
 * thing. To avoid that, [close] does not call `close(2)` itself. It flips [isClosed]
 * and waits for the reader to observe it and stop. The reader, as the sole owner,
 * performs the actual close via [releaseFd].
 *
 * [read] polls with a timeout rather than blocking, so a closed session is
 * noticed promptly without a signal.
 */
class PtySession internal constructor(
    val id: String,
    val masterFd: Int,
    val slaveFd: Int,
    val slavePath: String,
    val childPid: Int,
    val deviceId: String,
    val operatorId: String,
) {
    private val closed = AtomicBoolean(false)
    private val exitCode = AtomicInteger(Int.MIN_VALUE)
    private val lastActivityMs = AtomicReference(System.currentTimeMillis())

    val isClosed: Boolean get() = closed.get()

    /** Milliseconds since the last read or write. Used for idle expiry. */
    val idleForMs: Long get() = System.currentTimeMillis() - lastActivityMs.get()

    /**
     * True once the child has been reaped, with [childExitCode] valid.
     * A negative exit code means the child died from that signal.
     */
    val hasExited: Boolean get() = exitCode.get() != Int.MIN_VALUE

    val childExitCode: Int get() = exitCode.get()

    /**
     * Reads output. Returns [PtyReadResult.Timeout] when nothing arrived within
     * [timeoutMs], which is normal for an idle shell.
     */
    fun read(maxBytes: Int = DEFAULT_READ_SIZE, timeoutMs: Int = DEFAULT_READ_TIMEOUT_MS): PtyResult<PtyReadOutcome> {
        if (closed.get()) {
            return PtyResult.Err(PtyError(PtyError.Kind.SESSION_CLOSED, "session $id is closed"))
        }

        val result = NativePty.readMaster(masterFd, maxBytes, timeoutMs)
        if (result.isOk) {
            lastActivityMs.set(System.currentTimeMillis())
        }

        return result.asResult { raw ->
            when {
                raw.eof -> PtyReadOutcome.Eof
                raw.timedOut -> PtyReadOutcome.Timeout
                else -> PtyReadOutcome.Data(raw.bytes ?: ByteArray(0))
            }
        }
    }

    /** Writes input to the session. */
    fun write(data: ByteArray): PtyResult<Unit> {
        if (closed.get()) {
            return PtyResult.Err(PtyError(PtyError.Kind.SESSION_CLOSED, "session $id is closed"))
        }

        val result = NativePty.writeMaster(masterFd, data)
        if (result.isOk) {
            lastActivityMs.set(System.currentTimeMillis())
        }

        return result.asResult { }
    }

    /** Applies a terminal geometry change. */
    fun resize(rows: Int, cols: Int): PtyResult<Unit> =
        NativePty.setWindowSize(masterFd, rows, cols).asResult { }

    /**
     * Reaps the child if it has exited. Safe to call repeatedly; once the child
     * is reaped this reports [PtyResult.Ok] without touching the kernel again.
     */
    fun reapIfExited(): PtyResult<Boolean> {
        if (hasExited) {
            return PtyResult.Ok(true)
        }

        return NativePty.reapChild(childPid).asResult { raw ->
            val reaped = (raw.fds ?: IntArray(0)).getOrElse(0) { 0 } == 1
            if (reaped) {
                exitCode.set((raw.fds ?: IntArray(0)).getOrElse(1) { 0 })
            }
            reaped
        }
    }

    /**
     * Marks the session closed and signals the child to stop.
     *
     * This does **not** close the master fd: the reader owns it and must release
     * it via [releaseFd] once it has stopped reading. Calling this is safe from
     * any thread and is idempotent.
     *
     * The child kill is best-effort. A session with no child (or one running
     * where the native library is unavailable) still transitions to closed,
     * because the caller's intent — stop using this session — is satisfiable
     * regardless.
     */
    fun close(): PtyResult<Unit> {
        if (!closed.compareAndSet(false, true)) {
            return PtyResult.Ok(Unit)
        }

        if (childPid <= 0) {
            return PtyResult.Ok(Unit)
        }

        // A live shell does not exit on its own, so it is killed rather than
        // asked politely. The kill is best-effort: the session is closed either
        // way, because the caller's intent is to stop using it.
        NativePty.killChild(childPid)
        return PtyResult.Ok(Unit)
    }

    /**
     * Closes the master fd. Must only be called by the thread that owns the
     * reader, after it has stopped reading.
     */
    internal fun releaseFd() {
        if (childPid > 0) {
            NativePty.killChild(childPid)
        }
        if (masterFd >= 0) {
            NativePty.closeFd(masterFd)
        }
    }

    companion object {
        const val DEFAULT_READ_SIZE = 4096
        const val DEFAULT_READ_TIMEOUT_MS = 250
    }
}

/** What a read produced. */
sealed interface PtyReadOutcome {
    /** Bytes were read. */
    data class Data(val bytes: ByteArray) : PtyReadOutcome {
        override fun equals(other: Any?): Boolean =
            other is Data && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Nothing arrived within the timeout. Not an error. */
    data object Timeout : PtyReadOutcome

    /** The slave side closed. */
    data object Eof : PtyReadOutcome
}
