package com.example.myapplication.projects

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import androidx.compose.runtime.saveable.Saver
import androidx.lifecycle.ViewModel
import com.example.myapplication.stage9.DriveAuthorizationRequestResult
import com.example.myapplication.stage9.DriveAuthorizationResolutionResult
import com.example.myapplication.stage9.GoogleDriveAuthClient
import com.example.myapplication.stage9.GoogleIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Project-only authorization port. Its request path must ask for read-only Drive access. */
internal sealed interface ProjectDriveAuthorizationRequest<out Resolution> {
    data class Granted(
        val authorization: DriveAuthorizationRequestResult.Granted
    ) : ProjectDriveAuthorizationRequest<Nothing>

    data class ResolutionRequired<Resolution>(val resolution: Resolution) :
        ProjectDriveAuthorizationRequest<Resolution>
}

internal interface ProjectDriveAuthorizationPort<Resolution> {
    val isConfigured: Boolean

    suspend fun signIn(): GoogleIdentity

    suspend fun requestReadAccess(identity: GoogleIdentity): ProjectDriveAuthorizationRequest<Resolution>

    fun completeReadAccess(data: Intent?): DriveAuthorizationRequestResult.Granted
}

/** Bridges the shared Google identity provider to the separate project read-only scope. */
internal class GoogleCredentialProjectDriveAuthorization(
    private val activity: Activity,
    private val auth: GoogleDriveAuthClient
) : ProjectDriveAuthorizationPort<IntentSender> {
    override val isConfigured: Boolean
        get() = auth.isConfigured

    override suspend fun signIn(): GoogleIdentity = auth.signIn(activity)

    override suspend fun requestReadAccess(
        identity: GoogleIdentity
    ): ProjectDriveAuthorizationRequest<IntentSender> = when (
        val authorization = requestProjectDriveAccess(activity, identity)
    ) {
        is DriveAuthorizationRequestResult.Granted ->
            ProjectDriveAuthorizationRequest.Granted(authorization)
        is DriveAuthorizationRequestResult.ResolutionRequired ->
            ProjectDriveAuthorizationRequest.ResolutionRequired(authorization.intentSender)
    }

    override fun completeReadAccess(data: Intent?): DriveAuthorizationRequestResult.Granted =
        auth.completeDriveAuthorization(activity, data)
}

/**
 * Safe project-consent correlation data. Provider tokens and result payloads
 * deliberately never enter this state or its saved representation.
 */
internal data class PendingProjectDriveConsent(
    val operationId: String,
    val generation: Long,
    /** Subject for the project Drive grant, which can differ from backup auth. */
    val accountSubject: String,
    /** Activity-scoped nonce: stable over rotation and replaced after process death. */
    val ownerNonce: String,
    /** Backup identity when consent began; null means none was active then. */
    val startingBackupIdentitySubject: String?
) {
    init {
        require(operationId.isNotBlank())
        require(generation > 0L)
        require(accountSubject.isNotBlank())
        require(ownerNonce.isNotBlank())
        require(startingBackupIdentitySubject == null || startingBackupIdentitySubject.isNotBlank())
    }
}

/** Activity-scoped nonce: retained on rotation, regenerated after process death. */
internal class ProjectDriveConsentOwner : ViewModel() {
    val nonce: String = UUID.randomUUID().toString()
}

internal data class ProjectDriveAuthorizationState(
    val accountGeneration: Long = 0L,
    val pendingConsent: PendingProjectDriveConsent? = null,
    /** In-flight identity/token work is not restorable. */
    val connecting: Boolean = false
)

internal sealed interface ProjectDriveConnectOutcome<out Resolution> {
    data object Ignored : ProjectDriveConnectOutcome<Nothing>
    data class Granted(
        val accountSubject: String,
        val authorization: DriveAuthorizationRequestResult.Granted
    ) : ProjectDriveConnectOutcome<Nothing>
    data class ResolutionRequired<Resolution>(
        val pending: PendingProjectDriveConsent,
        val resolution: Resolution
    ) : ProjectDriveConnectOutcome<Resolution>
    data object Failed : ProjectDriveConnectOutcome<Nothing>
}

internal sealed interface ProjectDriveResolutionOutcome {
    data object Ignored : ProjectDriveResolutionOutcome
    data object Expired : ProjectDriveResolutionOutcome
    data object Cancelled : ProjectDriveResolutionOutcome
    data class Granted(
        val accountSubject: String,
        val authorization: DriveAuthorizationRequestResult.Granted
    ) : ProjectDriveResolutionOutcome
    data object Failed : ProjectDriveResolutionOutcome
}

/**
 * Owns project-consent attempts and result fences. The Compose caller only
 * starts the platform launcher and renders this owner's state.
 */
internal class ProjectDriveAuthorizationOwner<Resolution>(
    private val auth: ProjectDriveAuthorizationPort<Resolution>,
    private val activityOwnerNonce: String,
    initialState: ProjectDriveAuthorizationState = ProjectDriveAuthorizationState(),
    private val newOperationId: () -> String = { UUID.randomUUID().toString() }
) {
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(initialState.copy(connecting = false))
    val state: StateFlow<ProjectDriveAuthorizationState> = mutableState.asStateFlow()

    init {
        require(activityOwnerNonce.isNotBlank())
    }

    val isConfigured: Boolean
        get() = auth.isConfigured

    /**
     * Starts one project account attempt. The backup identity is reused unless
     * the user explicitly asks to switch project accounts.
     */
    suspend fun connect(
        backupIdentity: GoogleIdentity?,
        changeAccount: Boolean = false,
        busy: Boolean = false
    ): ProjectDriveConnectOutcome<Resolution> {
        val startingBackupIdentitySubject = backupIdentity?.subject
        val generation = synchronized(stateLock) {
            val current = mutableState.value
            if (current.connecting || current.pendingConsent != null || busy) {
                return ProjectDriveConnectOutcome.Ignored
            }
            nextGeneration(current.accountGeneration).also { next ->
                mutableState.value = ProjectDriveAuthorizationState(
                    accountGeneration = next,
                    pendingConsent = null,
                    connecting = true
                )
            }
        }

        var waitingForConsent = false
        try {
            val identity = backupIdentity?.takeUnless { changeAccount } ?: auth.signIn()
            if (!isCurrent(generation)) return ProjectDriveConnectOutcome.Ignored

            return when (val authorization = auth.requestReadAccess(identity)) {
                is ProjectDriveAuthorizationRequest.Granted -> {
                    if (isCurrent(generation)) {
                        ProjectDriveConnectOutcome.Granted(identity.subject, authorization.authorization)
                    } else {
                        ProjectDriveConnectOutcome.Ignored
                    }
                }
                is ProjectDriveAuthorizationRequest.ResolutionRequired -> {
                    val pending = PendingProjectDriveConsent(
                        operationId = newOperationId().trim().also {
                            require(it.isNotEmpty()) { "Project consent operation id is required" }
                        },
                        generation = generation,
                        accountSubject = identity.subject,
                        ownerNonce = activityOwnerNonce,
                        startingBackupIdentitySubject = startingBackupIdentitySubject
                    )
                    if (!installPendingIfCurrent(pending)) {
                        ProjectDriveConnectOutcome.Ignored
                    } else {
                        waitingForConsent = true
                        ProjectDriveConnectOutcome.ResolutionRequired(pending, authorization.resolution)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return if (finishAttemptIfCurrent(generation)) ProjectDriveConnectOutcome.Failed
            else ProjectDriveConnectOutcome.Ignored
        } finally {
            if (!waitingForConsent) finishAttemptIfCurrent(generation)
        }
    }

    /** Called only after the UI successfully launches the consent trampoline. */
    fun resolutionLaunchFailed(pending: PendingProjectDriveConsent) {
        synchronized(stateLock) {
            val current = mutableState.value
            if (current.accountGeneration == pending.generation && current.pendingConsent == pending) {
                mutableState.value = current.copy(pendingConsent = null, connecting = false)
            }
        }
    }

    /**
     * Consumes a matching Activity result, validates its account/session fences,
     * then completes the scoped project authorization through the injected port.
     */
    fun consumeResolutionResult(
        result: DriveAuthorizationResolutionResult,
        currentBackupIdentitySubject: String?
    ): ProjectDriveResolutionOutcome {
        val pending = synchronized(stateLock) {
            val current = mutableState.value
            val candidate = current.pendingConsent
                ?: return ProjectDriveResolutionOutcome.Ignored
            if (!projectDriveConsentOperationMatches(
                    candidate,
                    current.accountGeneration,
                    result.operationId
                )) {
                return ProjectDriveResolutionOutcome.Ignored
            }
            if (!projectDriveConsentResultMatches(
                    candidate,
                    current.accountGeneration,
                    result.operationId,
                    activityOwnerNonce,
                    currentBackupIdentitySubject
                )) {
                mutableState.value = current.copy(pendingConsent = null, connecting = false)
                return ProjectDriveResolutionOutcome.Expired
            }
            mutableState.value = current.copy(pendingConsent = null, connecting = false)
            candidate
        }

        if (result.resultCode != Activity.RESULT_OK) return ProjectDriveResolutionOutcome.Cancelled
        return try {
            ProjectDriveResolutionOutcome.Granted(
                accountSubject = pending.accountSubject,
                authorization = auth.completeReadAccess(result.providerData)
            )
        } catch (_: Exception) {
            ProjectDriveResolutionOutcome.Failed
        }
    }

    /** Rejects a restored/live pending result when its backup identity or owner changed. */
    fun invalidatePendingConsentIfStale(currentBackupIdentitySubject: String?): Boolean =
        synchronized(stateLock) {
            val current = mutableState.value
            val pending = current.pendingConsent ?: return false
            if (projectDriveConsentResultMatches(
                    pending,
                    current.accountGeneration,
                    pending.operationId,
                    activityOwnerNonce,
                    currentBackupIdentitySubject
                )) {
                return false
            }
            mutableState.value = current.copy(pendingConsent = null, connecting = false)
            true
        }

    /** Explicit retry abandons the old external consent result before a new attempt. */
    fun prepareRetry() {
        synchronized(stateLock) {
            val current = mutableState.value
            mutableState.value = current.copy(pendingConsent = null, connecting = false)
        }
    }

    /** Dismissal/back invalidates both pending consent and suspended auth work. */
    fun invalidate() {
        synchronized(stateLock) {
            val current = mutableState.value
            mutableState.value = ProjectDriveAuthorizationState(
                accountGeneration = nextGeneration(current.accountGeneration)
            )
        }
    }

    internal fun saveableState(): ProjectDriveAuthorizationState = synchronized(stateLock) {
        mutableState.value.copy(connecting = false)
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(stateLock) {
        mutableState.value.accountGeneration == generation
    }

    private fun installPendingIfCurrent(pending: PendingProjectDriveConsent): Boolean =
        synchronized(stateLock) {
            val current = mutableState.value
            if (current.accountGeneration != pending.generation || !current.connecting) return false
            mutableState.value = current.copy(pendingConsent = pending)
            true
        }

    private fun finishAttemptIfCurrent(generation: Long): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        if (current.accountGeneration != generation || current.pendingConsent != null) return false
        mutableState.value = current.copy(connecting = false)
        true
    }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L
}

/** Save only the non-secret operation/account identity needed to reject stale results. */
internal fun encodePendingProjectDriveConsent(pending: PendingProjectDriveConsent?): ArrayList<String> =
    pending?.let {
        arrayListOf(
            it.operationId,
            it.generation.toString(),
            it.accountSubject,
            it.ownerNonce,
            it.startingBackupIdentitySubject.orEmpty()
        )
    } ?: arrayListOf()

internal fun decodePendingProjectDriveConsent(saved: Any): PendingProjectDriveConsent? {
    val fields = saved as? ArrayList<*> ?: return null
    if (fields.isEmpty() || fields.size != 5 || fields.any { it !is String }) return null
    val operationId = fields[0] as? String ?: return null
    val generation = (fields[1] as? String)?.toLongOrNull() ?: return null
    val accountSubject = fields[2] as? String ?: return null
    val ownerNonce = fields[3] as? String ?: return null
    val startingBackupIdentitySubject = (fields[4] as? String)?.takeIf { it.isNotBlank() }
    if (operationId.isBlank() || generation <= 0L || accountSubject.isBlank() || ownerNonce.isBlank()) return null
    return PendingProjectDriveConsent(
        operationId,
        generation,
        accountSubject,
        ownerNonce,
        startingBackupIdentitySubject
    )
}

/** Compatibility saver for the focused Compose recreation fixture. */
internal val pendingProjectDriveConsentSaver: Saver<PendingProjectDriveConsent?, Any> = Saver(
    save = { encodePendingProjectDriveConsent(it) },
    restore = { decodePendingProjectDriveConsent(it) }
)

internal fun projectDriveConsentOperationMatches(
    pending: PendingProjectDriveConsent?,
    currentGeneration: Long,
    resultOperationId: String?
): Boolean = pending != null && pending.generation == currentGeneration &&
    pending.operationId == resultOperationId && !resultOperationId.isNullOrBlank()

/**
 * A resolved result is usable only for the live Activity owner and only while
 * any currently known backup identity is the one present when consent began.
 * A deliberately selected project account may differ from that backup account.
 */
internal fun projectDriveConsentResultMatches(
    pending: PendingProjectDriveConsent?,
    currentGeneration: Long,
    resultOperationId: String?,
    currentOwnerNonce: String,
    currentBackupIdentitySubject: String?
): Boolean {
    if (!projectDriveConsentOperationMatches(pending, currentGeneration, resultOperationId)) return false
    val current = requireNotNull(pending)
    return current.ownerNonce == currentOwnerNonce &&
        (currentBackupIdentitySubject == null ||
            current.startingBackupIdentitySubject == currentBackupIdentitySubject)
}

internal fun encodeProjectDriveAuthorizationState(state: ProjectDriveAuthorizationState): ArrayList<String> =
    arrayListOf(state.accountGeneration.toString()).apply {
        addAll(encodePendingProjectDriveConsent(state.pendingConsent))
    }

internal fun decodeProjectDriveAuthorizationState(saved: Any): ProjectDriveAuthorizationState {
    val fields = saved as? ArrayList<*> ?: return ProjectDriveAuthorizationState()
    if (fields.isEmpty() || fields.any { it !is String }) return ProjectDriveAuthorizationState()
    val generation = (fields.first() as? String)?.toLongOrNull()
        ?.takeIf { it >= 0L }
        ?: return ProjectDriveAuthorizationState()
    if (fields.size == 1) return ProjectDriveAuthorizationState(accountGeneration = generation)
    if (fields.size != 6) return ProjectDriveAuthorizationState(accountGeneration = generation)
    val pending = decodePendingProjectDriveConsent(ArrayList(fields.drop(1)))
        ?.takeIf { it.generation == generation }
    return ProjectDriveAuthorizationState(accountGeneration = generation, pendingConsent = pending)
}

internal fun <Resolution> projectDriveAuthorizationOwnerSaver(
    auth: ProjectDriveAuthorizationPort<Resolution>,
    activityOwnerNonce: String
): Saver<ProjectDriveAuthorizationOwner<Resolution>, Any> = Saver(
    save = { encodeProjectDriveAuthorizationState(it.saveableState()) },
    restore = {
        ProjectDriveAuthorizationOwner(
            auth = auth,
            activityOwnerNonce = activityOwnerNonce,
            initialState = decodeProjectDriveAuthorizationState(it)
        )
    }
)
