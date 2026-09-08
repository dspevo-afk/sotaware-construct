package com.example.myapplication.stage9

import java.util.UUID

/**
 * Correlates a Drive consent result with the exact manager owner, identity, and
 * authorization attempt that launched it. The random operation id is
 * intentionally independent from the in-memory generation, which can restart
 * from the same numeric value after process recreation.
 */
internal data class PendingDriveAuthorizationResolution(
    val operationId: String,
    val authorityOwner: DriveAuthorizationAuthorityOwner,
    val generation: Long,
    val identity: GoogleIdentity
)

/** Opaque reference-identity token for one in-memory Drive authority owner. */
internal class DriveAuthorizationAuthorityOwner

internal class DriveAuthorizationResolutionTracker(
    private val newOperationId: () -> String = { UUID.randomUUID().toString() }
) {
    private var pending: PendingDriveAuthorizationResolution? = null

    @Synchronized
    fun begin(
        authorityOwner: DriveAuthorizationAuthorityOwner,
        generation: Long,
        identity: GoogleIdentity
    ): PendingDriveAuthorizationResolution {
        require(generation > 0L) { "Drive authorization generation must be positive" }
        val operationId = newOperationId().trim()
        require(operationId.isNotEmpty()) { "Drive authorization operation id is required" }
        return PendingDriveAuthorizationResolution(
            operationId = operationId,
            authorityOwner = authorityOwner,
            generation = generation,
            identity = identity
        ).also {
            pending = it
        }
    }

    /**
     * Returns the exact pending authority only when every fence still matches.
     * A different operation never consumes or alters a newer pending operation.
     * A terminal result for a replaced owner/identity is consumed and rejected.
     */
    @Synchronized
    fun consume(
        operationId: String?,
        authorityOwner: DriveAuthorizationAuthorityOwner,
        generation: Long,
        identity: GoogleIdentity?
    ): PendingDriveAuthorizationResolution? {
        val current = pending ?: return null
        if (operationId.isNullOrBlank() || operationId != current.operationId) return null
        pending = null
        if (current.authorityOwner !== authorityOwner ||
            current.generation != generation ||
            current.identity != identity
        ) return null
        return current
    }

    @Synchronized
    fun clearIfCurrent(candidate: PendingDriveAuthorizationResolution): Boolean {
        val current = pending ?: return false
        if (current != candidate) return false
        pending = null
        return true
    }

    @Synchronized
    fun hasPending(): Boolean = pending != null
}
