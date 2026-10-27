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
    data class Ok<T>(val value: T) : PtyResult<T>
    data class Err(val error: PtyError) : PtyResult<Nothing>
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
        fun fromNative(status: Int, detail: String): PtyError = PtyError(
            when (status) {
                1 -> Kind.SYSCALL_FAILED
                2 -> Kind.INVALID_ARGUMENT
                3 -> Kind.NO_SUCH_SESSION
                4 -> Kind.TOO_MANY_SESSIONS
                5 -> Kind.SESSION_EXISTS
                6 -> Kind.SESSION_CLOSED
                7 -> Kind.TIMED_OUT
                8 -> Kind.EXEC_FAILED
                9 -> Kind.FD_PASS_FAILED
                else -> Kind.INTERNAL
            },
            detail,
        )
    }
}

/** Converts the raw native result into a [PtyResult]. */
inline fun <T> NativePtyResult.asResult(transform: (NativePtyResult) -> T): PtyResult<T> =
    if (isOk) PtyResult.Ok(transform(this)) else PtyResult.Err(PtyError.fromNative(status, detail))
