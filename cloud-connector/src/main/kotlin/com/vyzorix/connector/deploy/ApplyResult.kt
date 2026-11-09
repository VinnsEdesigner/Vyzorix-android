package com.vyzorix.connector.deploy

import java.io.IOException

/**
 * The outcome of an apply. [reason] is a machine-readable refusal code (FR-16) when
 * [success] is false; [detail] names the app or artifact.
 */
data class ApplyResult(
    val version: Int,
    val success: Boolean,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun ok(version: Int): ApplyResult = ApplyResult(version, true)

        fun refused(
            version: Int,
            reason: String,
            detail: String,
        ): ApplyResult = ApplyResult(version, false, reason, detail)
    }
}

/** An artifact whose bytes did not hash to its signed digest (FR-13, §9.2). */
class DigestMismatch(
    message: String,
) : IOException(message)
