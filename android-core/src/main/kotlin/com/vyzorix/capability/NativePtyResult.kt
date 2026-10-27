package com.vyzorix.capability

/**
 * Raw return shape of every native call. Native code cannot build a Kotlin
 * sealed type, so it fills this and Kotlin converts it.
 *
 * Instantiated from C++ by name and signature — renaming this class or changing
 * the constructor breaks the bridge at runtime, not at compile time.
 */
class NativePtyResult(
    @JvmField val status: Int,
    @JvmField val detail: String,
    // Native passes null for the fields a given call does not use, so these are
    // nullable. Declaring them non-null makes the JNI constructor throw a
    // NullPointerException at the boundary instead of returning a result.
    @JvmField val fds: IntArray?,
    @JvmField val bytes: ByteArray?,
    @JvmField val timedOut: Boolean,
    @JvmField val eof: Boolean,
) {
    val isOk: Boolean get() = status == 0
}

/** Outcome of a native call. Failures arrive as [Err]; native never throws. */
sealed interface PtyResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : PtyResult<T>

    data class Err(
        val error: PtyError,
    ) : PtyResult<Nothing>
}

/**
 * Native status codes, mirroring the `Status` enum in `pty_broker.h`.
 *
 * Named rather than inlined so a change on either side has one obvious
 * counterpart to update.
 */
private object NativeStatusCode {
    const val SYSCALL_FAILED = 1
    const val INVALID_ARGUMENT = 2
    const val NO_SUCH_SESSION = 3
    const val TOO_MANY_SESSIONS = 4
    const val SESSION_EXISTS = 5
    const val SESSION_CLOSED = 6
    const val TIMED_OUT = 7
    const val EXEC_FAILED = 8
    const val FD_PASS_FAILED = 9
}

/** Why a native operation failed. */
data class PtyError(
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        SYSCALL_FAILED,
        INVALID_ARGUMENT,
        NO_SUCH_SESSION,
        TOO_MANY_SESSIONS,
        SESSION_EXISTS,
        SESSION_CLOSED,
        TIMED_OUT,
        EXEC_FAILED,
        FD_PASS_FAILED,
        INTERNAL,
    }

    override fun toString(): String = "${kind.name.lowercase()}: $detail"

    companion object {
        fun fromNative(
            status: Int,
            detail: String,
        ): PtyError =
            PtyError(
                when (status) {
                    NativeStatusCode.SYSCALL_FAILED -> Kind.SYSCALL_FAILED
                    NativeStatusCode.INVALID_ARGUMENT -> Kind.INVALID_ARGUMENT
                    NativeStatusCode.NO_SUCH_SESSION -> Kind.NO_SUCH_SESSION
                    NativeStatusCode.TOO_MANY_SESSIONS -> Kind.TOO_MANY_SESSIONS
                    NativeStatusCode.SESSION_EXISTS -> Kind.SESSION_EXISTS
                    NativeStatusCode.SESSION_CLOSED -> Kind.SESSION_CLOSED
                    NativeStatusCode.TIMED_OUT -> Kind.TIMED_OUT
                    NativeStatusCode.EXEC_FAILED -> Kind.EXEC_FAILED
                    NativeStatusCode.FD_PASS_FAILED -> Kind.FD_PASS_FAILED
                    else -> Kind.INTERNAL
                },
                detail,
            )
    }
}

/** Converts the raw native result into a [PtyResult]. */
inline fun <T> NativePtyResult.asResult(transform: (NativePtyResult) -> T): PtyResult<T> =
    if (isOk) PtyResult.Ok(transform(this)) else PtyResult.Err(PtyError.fromNative(status, detail))
