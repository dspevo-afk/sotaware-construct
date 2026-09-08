package com.example.myapplication.stage9

/** The only Drive scope this app is permitted to request. */
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Identity received from Credential Manager. The email remains the Stage 4
 * account key for compatibility with existing local sync metadata; [subject]
 * is retained only to detect a different Google account with the same display
 * information. No ID token is retained here.
 */
data class GoogleIdentity(
    val subject: String,
    val email: String
) {
    init {
        require(subject.isNotBlank()) { "Google subject is required" }
        require(email.isNotBlank()) { "Google email is required" }
    }
}

data class AuthorizedDriveSession(
    val identity: GoogleIdentity,
    val accessToken: String,
    val generation: Long
) {
    override fun toString(): String = "AuthorizedDriveSession(generation=$generation)"
}

sealed interface DriveAuthorizationApplyResult {
    data class Accepted(val session: AuthorizedDriveSession) : DriveAuthorizationApplyResult
    data object Stale : DriveAuthorizationApplyResult
    data object MissingDriveFileScope : DriveAuthorizationApplyResult
    data object ContainsUnexpectedDriveScope : DriveAuthorizationApplyResult
    data object MissingAccessToken : DriveAuthorizationApplyResult
}

/**
 * Small synchronized state owner that prevents a late authorization resolution
 * from installing a token for an account that has already changed or signed
 * out. Tokens stay in memory and are never persisted.
 */
class DriveAuthorizationSession {
    private var generation = 0L
    private var identity: GoogleIdentity? = null
    private var active: AuthorizedDriveSession? = null

    /** Reserve the epoch before Credential Manager can suspend or display UI. */
    @Synchronized
    fun beginAuthenticationAttempt(): Long = clear()

    @Synchronized
    fun authenticateIfCurrent(expectedGeneration: Long, newIdentity: GoogleIdentity): Boolean {
        if (expectedGeneration != generation || identity != null) return false
        identity = newIdentity
        return true
    }

    @Synchronized
    fun beginAuthentication(newIdentity: GoogleIdentity): Long {
        val attempt = beginAuthenticationAttempt()
        check(authenticateIfCurrent(attempt, newIdentity))
        return attempt
    }

    @Synchronized
    fun applyAuthorization(
        expectedGeneration: Long,
        accessToken: String?,
        grantedScopes: Collection<String>
    ): DriveAuthorizationApplyResult {
        val currentIdentity = identity ?: return DriveAuthorizationApplyResult.Stale
        if (expectedGeneration != generation || active != null) return DriveAuthorizationApplyResult.Stale
        if (accessToken.isNullOrBlank()) return DriveAuthorizationApplyResult.MissingAccessToken
        if (DRIVE_FILE_SCOPE !in grantedScopes) {
            return DriveAuthorizationApplyResult.MissingDriveFileScope
        }
        if (grantedScopes.any(::isUnexpectedDriveScope)) {
            return DriveAuthorizationApplyResult.ContainsUnexpectedDriveScope
        }
        return AuthorizedDriveSession(
            identity = currentIdentity,
            accessToken = accessToken,
            generation = generation
        ).also { accepted ->
            active = accepted
        }.let(DriveAuthorizationApplyResult::Accepted)
    }

    @Synchronized
    fun clear(): Long {
        generation += 1
        identity = null
        active = null
        return generation
    }

    /** Clears an unfinished or active session only when it is still current. */
    @Synchronized
    fun clearIfCurrent(expectedGeneration: Long): Boolean {
        if (expectedGeneration != generation) return false
        generation += 1
        identity = null
        active = null
        return true
    }

    @Synchronized
    fun clearAuthorizationIfCurrent(expectedGeneration: Long): Boolean {
        if (expectedGeneration != generation || active == null) return false
        // Invalidate late grants as well as requests holding the rejected token.
        generation += 1
        active = null
        return true
    }

    @Synchronized
    fun currentGeneration(): Long = generation

    @Synchronized
    fun isAuthorizedGeneration(expectedGeneration: Long): Boolean =
        active?.generation == expectedGeneration && generation == expectedGeneration

    @Synchronized
    fun activeSession(): AuthorizedDriveSession? = active

    @Synchronized
    fun authenticatedIdentity(): GoogleIdentity? = identity

    private fun isUnexpectedDriveScope(scope: String): Boolean =
        scope.startsWith("https://www.googleapis.com/auth/drive") && scope != DRIVE_FILE_SCOPE
}
