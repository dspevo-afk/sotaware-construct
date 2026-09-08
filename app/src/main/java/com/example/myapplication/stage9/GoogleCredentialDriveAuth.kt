package com.example.myapplication.stage9

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.tasks.await

private const val GOOGLE_ACCOUNT_TYPE = "com.google"

/** A configuration or provider failure that must leave Drive unavailable. */
class GoogleCredentialDriveAuthException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/** The two possible results from an AuthorizationClient authorization call. */
sealed interface DriveAuthorizationRequestResult {
    data class Granted(
        val accessToken: String?,
        val grantedScopes: Set<String>
    ) : DriveAuthorizationRequestResult {
        override fun toString(): String = "Granted(grantedScopes=$grantedScopes)"
    }

    data class ResolutionRequired(val intentSender: IntentSender) : DriveAuthorizationRequestResult
}

/** Injectable boundary for the Android credential and authorization providers. */
interface GoogleDriveAuthClient {
    val isConfigured: Boolean

    suspend fun signIn(activity: Activity): GoogleIdentity

    suspend fun restoreAuthorizedIdentity(activity: Activity): GoogleIdentity?

    suspend fun requestDriveAuthorization(
        activity: Activity,
        identity: GoogleIdentity
    ): DriveAuthorizationRequestResult

    fun completeDriveAuthorization(
        activity: Activity,
        data: Intent?
    ): DriveAuthorizationRequestResult.Granted

    suspend fun clearCredentialState()

    suspend fun clearAccessToken(activity: Activity, accessToken: String)
}

/**
 * Android-only boundary for current Google authentication and Drive consent.
 *
 * Credential Manager establishes a Google identity. AuthorizationClient is a
 * deliberately separate operation which requests only [DRIVE_FILE_SCOPE].
 * This class never stores an ID token or Drive access token.
 */
class GoogleCredentialDriveAuth(
    context: Context,
    private val webClientId: String
) : GoogleDriveAuthClient {
    private val credentialManager = CredentialManager.create(context.applicationContext)

    override val isConfigured: Boolean
        get() = webClientId.isNotBlank()

    /**
     * The explicit button uses the full Google account chooser, including
     * accounts that have never signed in to this app.
     */
    override suspend fun signIn(activity: Activity): GoogleIdentity {
        val clientId = webClientId.trim()
        if (clientId.isEmpty()) {
            throw GoogleCredentialDriveAuthException(
                "Google sign-in is not configured: GOOGLE_WEB_CLIENT_ID is empty"
            )
        }

        val credential = credentialManager.getCredential(
            context = activity,
            request = explicitGoogleSignInRequest(clientId)
        ).credential

        return identityFromCredential(credential)
    }

    /**
     * Restores only a previously authorized Credential Manager identity. It
     * never requests a new identity grant or opens Drive consent. Credential
     * Manager may show its returning-account selector when auto-select is not
     * available; this is not a silent-session API.
     */
    override suspend fun restoreAuthorizedIdentity(activity: Activity): GoogleIdentity? {
        val clientId = webClientId.trim()
        if (clientId.isEmpty()) return null
        val credential = try {
            credentialManager.getCredential(
                context = activity,
                request = returningGoogleSignInRequest(clientId)
            ).credential
        } catch (_: NoCredentialException) {
            return null
        }
        return identityFromCredential(credential)
    }

    private fun identityFromCredential(credential: androidx.credentials.Credential): GoogleIdentity {
        val googleCredential = credential as? CustomCredential
            ?: throw GoogleCredentialDriveAuthException("Google sign-in returned an unsupported credential")
        if (googleCredential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw GoogleCredentialDriveAuthException("Google sign-in returned an unsupported credential type")
        }

        val parsed = try {
            GoogleIdTokenCredential.createFrom(googleCredential.data)
        } catch (failure: Exception) {
            throw GoogleCredentialDriveAuthException("Google sign-in returned an invalid identity", failure)
        }
        val subject = parsed.uniqueId.trim()
        val email = parsed.email?.trim().orEmpty()
        if (subject.isEmpty() || email.isEmpty()) {
            throw GoogleCredentialDriveAuthException("Google sign-in did not provide a stable account identity")
        }
        // Do not retain parsed.idToken: identity is all this app needs here.
        return GoogleIdentity(subject = subject, email = email)
    }

    override suspend fun requestDriveAuthorization(
        activity: Activity,
        identity: GoogleIdentity
    ): DriveAuthorizationRequestResult = authorizationResult(
        Identity.getAuthorizationClient(activity).authorize(
            driveAuthorizationRequest(identity)
        ).await()
    )

    /** Parses a completed AuthorizationClient resolution activity result. */
    override fun completeDriveAuthorization(
        activity: Activity,
        data: Intent?
    ): DriveAuthorizationRequestResult.Granted {
        val result = data?.let {
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(it)
        } ?: throw GoogleCredentialDriveAuthException("Drive authorization returned no result")
        return when (val authorization = authorizationResult(result)) {
            is DriveAuthorizationRequestResult.Granted -> authorization
            is DriveAuthorizationRequestResult.ResolutionRequired -> {
                throw GoogleCredentialDriveAuthException("Drive authorization still requires resolution")
            }
        }
    }

    /** Clears Credential Manager's local sign-in state; this does not revoke Drive access. */
    override suspend fun clearCredentialState() {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }

    /** Invalidate the exact access token rejected by Drive, never all account grants. */
    override suspend fun clearAccessToken(activity: Activity, accessToken: String) {
        Identity.getAuthorizationClient(activity).clearToken(
            ClearTokenRequest.builder().setToken(accessToken).build()
        ).await()
    }

    private fun authorizationResult(result: AuthorizationResult): DriveAuthorizationRequestResult {
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
                ?: throw GoogleCredentialDriveAuthException("Drive authorization resolution was missing")
            return DriveAuthorizationRequestResult.ResolutionRequired(pendingIntent.intentSender)
        }
        return DriveAuthorizationRequestResult.Granted(
            accessToken = result.accessToken,
            grantedScopes = result.grantedScopes.orEmpty().toSet()
        )
    }
}

internal fun explicitGoogleSignInRequest(serverClientId: String): GetCredentialRequest =
    GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
        .build()

internal fun driveAuthorizationRequest(identity: GoogleIdentity): AuthorizationRequest =
    AuthorizationRequest.Builder()
        .setAccount(Account(identity.email, GOOGLE_ACCOUNT_TYPE))
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        .build()

internal fun returningGoogleSignInRequest(serverClientId: String): GetCredentialRequest =
    GetCredentialRequest.Builder()
        .addCredentialOption(
            GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(true)
                .setAutoSelectEnabled(true)
                .build()
        )
        .setPreferImmediatelyAvailableCredentials(true)
        .build()
