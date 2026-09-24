package com.example.myapplication.stage9

internal fun DriveAuthorizationRequestResult.Granted.hasUnexpectedDriveScopes(): Boolean =
    grantedScopes.any { it.startsWith("https://www.googleapis.com/auth/drive") && it != DRIVE_FILE_SCOPE }

/**
 * A prior project-import token may be returned from the provider's token cache.
 * Refresh that exact token once, then keep the session's scope validation intact.
 * Never revoke account grants or retry consent indefinitely.
 */
internal suspend fun refreshUnexpectedBackupGrant(
    result: DriveAuthorizationRequestResult,
    clearToken: suspend (String) -> Unit,
    requestFresh: suspend () -> DriveAuthorizationRequestResult
): DriveAuthorizationRequestResult {
    val grant = result as? DriveAuthorizationRequestResult.Granted ?: return result
    if (!grant.hasUnexpectedDriveScopes() || grant.accessToken.isNullOrBlank()) return grant
    clearToken(grant.accessToken)
    return requestFresh()
}
