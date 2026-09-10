package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.myapplication.stage9.DiagnosticEvent
import com.example.myapplication.stage9.DriveAuthorizationApplyResult
import com.example.myapplication.stage9.DriveAuthorizationAuthorityOwner
import com.example.myapplication.stage9.DriveAuthorizationSession
import com.example.myapplication.stage9.GoogleIdentity
import com.example.myapplication.stage9.SafeDiagnostics
import com.google.api.client.http.HttpExecuteInterceptor
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.HttpUnsuccessfulResponseHandler
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import com.example.myapplication.stage4.DriveGateway
import com.example.myapplication.stage4.DrivePage
import com.example.myapplication.stage4.GoogleDriveGateway
import com.example.myapplication.stage9b.DriveImmutableAssetTransfer
import com.example.myapplication.stage4.collectDrivePages
import com.example.myapplication.stage5.escapeDriveQueryLiteral
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*

/** The UI-observable, token-free state of the current Drive authorization. */
data class DriveAuthorizationStatus(
    val identity: GoogleIdentity? = null,
    val isAuthorized: Boolean = false,
    val generation: Long = 0L,
    val backupFolder: DriveBackupFolder? = null
)

data class DriveBackupFolder(val id: String, val name: String)

class DriveSyncManager internal constructor(
    private val prefs: SharedPreferences,
    private val filesDir: () -> java.io.File,
    private val transport: HttpTransport,
    private val rootFailureDiagnostic: (Throwable) -> Unit = { failure ->
        SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = failure)
    },
    // Internal construction seam: tests retain the real transfer/authentication
    // path while supplying an explicit filesystem capability on desktop JVMs.
    private val assetTransferFactory: (Drive, String, java.nio.file.Path) -> DriveImmutableAssetTransfer =
        { service, accountId, appStorage ->
            DriveImmutableAssetTransfer(
                service, accountId,
                appStorage.resolve("drive-transfer"),
                appStorage.resolve("drive-staging"),
                trustedRootDirectory = appStorage
            )
        }
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("DriveSync", Context.MODE_PRIVATE),
        { context.filesDir },
        NetHttpTransport()
    )

    private val authorizationSession = DriveAuthorizationSession()
    internal val authorizationOwner = DriveAuthorizationAuthorityOwner()
    private val sessionLock = Any()
    private val rootCreationMutex = Mutex()
    private val rootOperations = mutableSetOf<Job>()
    private val tokenInvalidationMutex = Mutex()
    private val rejectedAccessTokens = linkedSetOf<String>()
    private var driveService: Drive? = null
    private var driveServiceGeneration: Long? = null
    private val mutableAuthorizationStatus = MutableStateFlow(DriveAuthorizationStatus())
    val authorizationStatus: StateFlow<DriveAuthorizationStatus> =
        mutableAuthorizationStatus.asStateFlow()
    
    companion object {
        private const val PREF_BACKUP_FOLDER_ID = "backup_folder_id"
        private const val PREF_BACKUP_FOLDER_NAME = "backup_folder_name"
        private const val PREF_BACKUP_FOLDER_ACCOUNT = "backup_folder_account"
        private const val PREF_BACKUP_FOLDER_SUBJECT = "backup_folder_subject"
        private const val PREF_RESTORE_GOOGLE_SESSION = "restore_google_session"
        private const val BACKUP_ROOT_APP_PROPERTY = "sotaware_backup_root"
        private const val TAG = "DriveSyncManager"
    }
    
    fun isSignedIn(): Boolean = authorizationStatus.value.isAuthorized

    /**
     * Starts a new token-free identity epoch. Callers fence the old Stage 4
     * binding first, then join its work and owned root operations before auth UI.
     */
    fun beginAuthenticationAttempt(): Long = synchronized(sessionLock) {
        driveService = null
        driveServiceGeneration = null
        // A new explicit/returning attempt supersedes the prior accepted grant.
        // Only a newly accepted grant may opt the app back into restoration.
        prefs.edit().putBoolean(PREF_RESTORE_GOOGLE_SESSION, false).apply()
        authorizationSession.beginAuthenticationAttempt().also {
            cancelRootOperationsLocked()
            publishAuthorizationStatusLocked()
        }
    }

    fun authenticateIfCurrent(expectedGeneration: Long, identity: GoogleIdentity): Boolean =
        synchronized(sessionLock) {
            authorizationSession.authenticateIfCurrent(expectedGeneration, identity).also {
                if (it) publishAuthorizationStatusLocked()
            }
        }

    fun shouldRestoreSession(): Boolean = prefs.getBoolean(PREF_RESTORE_GOOGLE_SESSION, false)

    /** Removes a stale startup-restore marker when no returning identity exists. */
    fun clearRestoreSessionMarker() {
        prefs.edit().putBoolean(PREF_RESTORE_GOOGLE_SESSION, false).apply()
    }

    /** A rejected cached token must be cleared before asking Google for a new one. */
    suspend fun invalidateRejectedAccessTokens(invalidate: suspend (String) -> Unit) =
        tokenInvalidationMutex.withLock {
            val rejected = synchronized(sessionLock) { rejectedAccessTokens.toList() }
            for (token in rejected) {
                invalidate(token)
                synchronized(sessionLock) { rejectedAccessTokens.remove(token) }
            }
        }

    /**
     * Installs a short-lived AuthorizationClient access token only if the
     * identity epoch and exact drive.file grant remain current.
     */
    fun installAuthorizedDriveSession(
        expectedGeneration: Long,
        accessToken: String?,
        grantedScopes: Collection<String>
    ): DriveAuthorizationApplyResult = synchronized(sessionLock) {
        when (
            val applied = authorizationSession.applyAuthorization(
                expectedGeneration = expectedGeneration,
                accessToken = accessToken,
                grantedScopes = grantedScopes
            )
        ) {
            is DriveAuthorizationApplyResult.Accepted -> {
                val accepted = applied.session
                driveService = buildDriveService(accepted.accessToken, accepted.generation)
                driveServiceGeneration = accepted.generation
                prefs.edit().putBoolean(PREF_RESTORE_GOOGLE_SESSION, true).apply()
                publishAuthorizationStatusLocked()
                applied
            }
            else -> applied
        }
    }

    fun clearSession() {
        synchronized(sessionLock) {
            authorizationSession.clear()
            cancelRootOperationsLocked()
            driveService = null
            driveServiceGeneration = null
            // Honor explicit sign-out even if the provider's clear-state call fails.
            prefs.edit().putBoolean(PREF_RESTORE_GOOGLE_SESSION, false).apply()
            publishAuthorizationStatusLocked()
        }
    }

    /** Does not let a canceled/stale resolution clear a newer account session. */
    fun clearSessionIfCurrent(expectedGeneration: Long): Boolean = synchronized(sessionLock) {
        if (!authorizationSession.clearIfCurrent(expectedGeneration)) return false
        cancelRootOperationsLocked()
        driveService = null
        driveServiceGeneration = null
        prefs.edit().putBoolean(PREF_RESTORE_GOOGLE_SESSION, false).apply()
        publishAuthorizationStatusLocked()
        true
    }

    fun getSignedInEmail(): String? = authorizationStatus.value
        .takeIf { it.isAuthorized }
        ?.identity
        ?.email
    
    data class DriveFolder(val id: String, val name: String, val isSharedDrive: Boolean = false)
    
    suspend fun listSharedDrives(): List<DriveFolder> = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext emptyList()
            
            val drives = collectDrivePages { pageToken ->
                service.drives().list()
                    .setPageSize(100)
                    .apply { if (pageToken != null) setPageToken(pageToken) }
                    .execute()
                    .let { DrivePage(it.drives.orEmpty(), it.nextPageToken) }
            }
            
            SafeDiagnostics.debug(DiagnosticEvent.SYNC_ACTIVITY)
            drives.map {
                SafeDiagnostics.debug(DiagnosticEvent.SYNC_ACTIVITY)
                DriveFolder(it.id, it.name, isSharedDrive = true) 
            } ?: emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SafeDiagnostics.error(DiagnosticEvent.SYNC_ACTIVITY, error = e)
            emptyList()
        }
    }
    
    suspend fun listFolders(parentId: String = "root", isSharedDrive: Boolean = false): List<DriveFolder> = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext emptyList()
            
            val query = "${escapeDriveQueryLiteral(parentId)} in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
            val request = service.files().list()
                .setQ(query)
                .setFields("nextPageToken, files(id, name)")
                .setOrderBy("name")
                .setPageSize(100)
            
            // For shared drives, need to include these parameters
            if (isSharedDrive) {
                request.setSupportsAllDrives(true)
                request.setIncludeItemsFromAllDrives(true)
                request.setCorpora("drive")
                request.setDriveId(parentId)
            } else {
                request.setSpaces("drive")
            }
            
            collectDrivePages { pageToken ->
                // The request object is reused for each page. Clear the
                // previous continuation token on the terminal request so a
                // final page cannot be fetched repeatedly.
                request.setPageToken(pageToken)
                request.execute().let { DrivePage(it.files.orEmpty(), it.nextPageToken) }
            }.map { DriveFolder(it.id, it.name) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = e)
            emptyList()
        }
    }
    
    suspend fun listFoldersInSharedDrive(driveId: String, parentId: String? = null): List<DriveFolder> = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext emptyList()
            
            val actualParentId = parentId ?: driveId
            val query = "${escapeDriveQueryLiteral(actualParentId)} in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
            
            collectDrivePages { pageToken ->
                service.files().list()
                    .setQ(query)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setCorpora("drive")
                    .setDriveId(driveId)
                    .setFields("files(id, name),nextPageToken")
                    .setOrderBy("name")
                    .setPageSize(100)
                    .apply { if (pageToken != null) setPageToken(pageToken) }
                    .execute()
                    .let { DrivePage(it.files.orEmpty(), it.nextPageToken) }
            }.map { DriveFolder(it.id, it.name) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SafeDiagnostics.error(DiagnosticEvent.SYNC_ACTIVITY, error = e)
            emptyList()
        }
    }
    
    suspend fun createFolderInSharedDrive(name: String, driveId: String, parentId: String): DriveFolder? = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext null
            
            val folderMetadata = File()
                .setName(name)
                .setMimeType("application/vnd.google-apps.folder")
                .setParents(listOf(parentId))
            
            val folder = service.files().create(folderMetadata)
                .setSupportsAllDrives(true)
                .setFields("id, name")
                .execute()
            
            DriveFolder(folder.id, folder.name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SafeDiagnostics.error(DiagnosticEvent.SYNC_ACTIVITY, error = e)
            null
        }
    }
    
    suspend fun createFolder(name: String, parentId: String = "root"): DriveFolder? = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext null
            
            val folderMetadata = File()
                .setName(name)
                .setMimeType("application/vnd.google-apps.folder")
                .setParents(listOf(parentId))
            
            val folder = service.files().create(folderMetadata)
                .setFields("id, name")
                .execute()
            
            DriveFolder(folder.id, folder.name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SafeDiagnostics.error(DiagnosticEvent.OPERATION_FAILED, error = e)
            null
        }
    }

    fun getBackupFolderName(): String? = authorizationStatus.value.backupFolder?.name

    /**
     * A root belongs to one signed-in account. Legacy unscoped preferences are
     * intentionally not reused because their ownership cannot be proved.
     */
    fun setBackupFolder(expectedGeneration: Long, folderId: String, folderName: String): Boolean =
        synchronized(sessionLock) {
            require(folderId.isNotBlank()) { "Drive backup folder ID is required" }
            require(folderName.isNotBlank()) { "Drive backup folder name is required" }
            val active = authorizationSession.activeSession() ?: return false
            if (!authorizationSession.isAuthorizedGeneration(expectedGeneration)) return false
            prefs.edit()
                .putString(PREF_BACKUP_FOLDER_ID, folderId)
                .putString(PREF_BACKUP_FOLDER_NAME, folderName)
                .putString(PREF_BACKUP_FOLDER_ACCOUNT, active.identity.email)
                .putString(PREF_BACKUP_FOLDER_SUBJECT, active.identity.subject)
                .apply()
            publishAuthorizationStatusLocked()
            true
        }

    fun clearBackupFolder(expectedGeneration: Long) = synchronized(sessionLock) {
        if (!authorizationSession.isAuthorizedGeneration(expectedGeneration)) return
        val identity = authorizationSession.activeSession()?.identity ?: return
        if (backupFolderForIdentityLocked(identity) == null) return
        cancelRootOperationsLocked()
        prefs.edit()
            .remove(PREF_BACKUP_FOLDER_ID)
            .remove(PREF_BACKUP_FOLDER_NAME)
            .remove(PREF_BACKUP_FOLDER_ACCOUNT)
            .remove(PREF_BACKUP_FOLDER_SUBJECT)
            .apply()
        publishAuthorizationStatusLocked()
    }

    private fun backupFolderForIdentityLocked(identity: GoogleIdentity): DriveBackupFolder? {
        if (prefs.getString(PREF_BACKUP_FOLDER_ACCOUNT, null) != identity.email ||
            prefs.getString(PREF_BACKUP_FOLDER_SUBJECT, null) != identity.subject
        ) return null
        val id = prefs.getString(PREF_BACKUP_FOLDER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs.getString(PREF_BACKUP_FOLDER_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        return DriveBackupFolder(id, name)
    }

    private fun getBackupFolderId(): String? = authorizationStatus.value.backupFolder?.id

    /** Read the live authority together, including before Compose recomposes after a 401. */
    fun currentSyncAccountRoot(): Pair<String, String>? = synchronized(sessionLock) {
        val active = authorizationSession.activeSession() ?: return null
        val root = backupFolderForIdentityLocked(active.identity) ?: return null
        active.identity.email to root.id
    }

    /** Stable root identity exposed to the Stage 4 coordinator; no display name is used. */
    fun getBackupFolderIdForSync(): String? = getBackupFolderId()

    /**
     * Creates the typed gateway for the current authenticated account. The
     * caller owns the coordinator lifecycle; this adapter does not create a
     * timer or retain a competing synchronization scope.
     */
    fun stage4Gateway(): DriveGateway? {
        val (service, accountId) = synchronized(sessionLock) {
            val active = authorizationSession.activeSession() ?: return null
            val currentService = driveService ?: return null
            if (driveServiceGeneration != active.generation) return null
            currentService to active.identity.email
        }
        val appStorage = filesDir().toPath()
        return GoogleDriveGateway(
            service = service,
            accountId = accountId,
            assetTransfer = assetTransferFactory(service, accountId, appStorage)
        )
    }

    private fun buildDriveService(accessToken: String, generation: Long): Drive = Drive.Builder(
        transport,
        GsonFactory.getDefaultInstance(),
        AccessTokenRequestInitializer(
            accessToken,
            isCurrent = { authorizationSession.isAuthorizedGeneration(generation) },
            onUnauthorized = { clearAuthorizationAfterUnauthorized(generation, accessToken) }
        )
    )
        .setApplicationName("SOTAware Construct")
        .build()

    private fun clearAuthorizationAfterUnauthorized(expectedGeneration: Long, rejectedToken: String) {
        synchronized(sessionLock) {
            // Clear only this token from Google's cache on the next auth attempt.
            // Even a late 401 can identify a bad token without revoking a new one.
            rejectedAccessTokens += rejectedToken
            if (rejectedAccessTokens.size > 16) rejectedAccessTokens.remove(rejectedAccessTokens.first())
            if (authorizationSession.clearAuthorizationIfCurrent(expectedGeneration)) {
                cancelRootOperationsLocked()
                driveService = null
                driveServiceGeneration = null
                prefs.edit().putBoolean(PREF_RESTORE_GOOGLE_SESSION, false).apply()
                publishAuthorizationStatusLocked()
            }
        }
    }

    private fun publishAuthorizationStatusLocked() {
        val active = authorizationSession.activeSession()
        mutableAuthorizationStatus.value = DriveAuthorizationStatus(
            identity = authorizationSession.authenticatedIdentity(),
            isAuthorized = active != null && driveService != null &&
                driveServiceGeneration == active.generation,
            generation = authorizationSession.currentGeneration(),
            backupFolder = active?.identity?.let(::backupFolderForIdentityLocked)
        )
    }
    
    private fun cancelRootOperationsLocked() {
        rootOperations.toList().forEach { it.cancel() }
    }

    /** A blocking HTTP request may finish after cancellation; drain it before new auth. */
    suspend fun cancelRootOperationsAndJoin() {
        val previous = synchronized(sessionLock) { rootOperations.toList() }
        previous.forEach { it.cancel() }
        previous.joinAll()
    }

    suspend fun createRootBackupFolder(expectedGeneration: Long): Pair<String, String>? = coroutineScope {
        // Own a child of the caller so revocation cancels this operation, not
        // unrelated UI work, and lifecycle cancellation still drains the child.
        val operation = currentCoroutineContext().job
        synchronized(sessionLock) {
            if (!authorizationSession.isAuthorizedGeneration(expectedGeneration)) return@coroutineScope null
            rootOperations += operation
        }
        try {
            rootCreationMutex.withLock {
                createRootBackupFolderForCurrentSession(expectedGeneration)
            }
        } finally {
            synchronized(sessionLock) { rootOperations.remove(operation) }
        }
    }

    private suspend fun createRootBackupFolderForCurrentSession(expectedGeneration: Long): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val service = synchronized(sessionLock) {
                    if (!authorizationSession.isAuthorizedGeneration(expectedGeneration)) return@withContext null
                    driveService
                } ?: return@withContext null
                val folderName = "SOTAware Construct Backups"

                // `root` is an input alias. Drive returns the account-specific
                // opaque ID in File.parents, so resolve it before trusting any
                // list/create response and keep the result fenced to this
                // authorized generation.
                val rootId = service.files().get("root")
                    .setFields("id")
                    .execute()
                    .id
                    ?.takeIf { it.isNotBlank() && it == it.trim() }
                    ?: return@withContext null
                if (!authorizationSession.isAuthorizedGeneration(expectedGeneration)) {
                    return@withContext null
                }

                // Reuse only a root created by this app, not an unrelated same-name folder.
                val query = "appProperties has { key='$BACKUP_ROOT_APP_PROPERTY' and value='1' } and ${escapeDriveQueryLiteral(rootId)} in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
                val folders = collectDrivePages { pageToken ->
                    service.files().list()
                        .setQ(query)
                        .setSpaces("drive")
                        .setPageSize(100)
                        .setFields(
                            "files(id, name, webViewLink, mimeType, parents, appProperties, trashed),nextPageToken"
                        )
                        .apply { if (pageToken != null) setPageToken(pageToken) }
                        .execute()
                        .let { DrivePage(it.files.orEmpty(), it.nextPageToken) }
                }
                if (folders.isNotEmpty()) {
                    // The query is a useful admission filter, but the remote
                    // response is still untrusted. Do not persist a root whose
                    // identity, parent, marker, or type is incomplete.
                    val folder = folders.firstOrNull { isValidBackupRoot(it, rootId) }
                        ?: return@withContext null
                    if (!authorizationSession.isAuthorizedGeneration(expectedGeneration)) return@withContext null
                    return@withContext Pair(folder.id, folder.name)
                }

                // Create new folder under the resolved Drive root ID.
                if (!authorizationSession.isAuthorizedGeneration(expectedGeneration)) return@withContext null
                val folderMetadata = File()
                    .setName(folderName)
                    .setMimeType("application/vnd.google-apps.folder")
                    .setParents(listOf(rootId))
                    .setAppProperties(mapOf(BACKUP_ROOT_APP_PROPERTY to "1"))

                val folder = service.files().create(folderMetadata)
                    .setFields("id, name, webViewLink, mimeType, parents, appProperties, trashed")
                    .execute()

                if (authorizationSession.isAuthorizedGeneration(expectedGeneration) &&
                    isValidBackupRoot(folder, rootId)
                ) {
                    Pair(folder.id, folder.name)
                } else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                rootFailureDiagnostic(e)
                null
            }
        }

    private fun isValidBackupRoot(folder: File, expectedParentId: String): Boolean =
        !folder.id.isNullOrBlank() &&
            !folder.name.isNullOrBlank() &&
            folder.mimeType == "application/vnd.google-apps.folder" &&
            folder.trashed != true &&
            folder.parents?.contains(expectedParentId) == true &&
            folder.appProperties?.get(BACKUP_ROOT_APP_PROPERTY) == "1"
    

}

/**
 * Adds the in-memory AuthorizationClient token to each Drive request and
 * invalidates only its matching session when Drive rejects it as unauthorized.
 */
private class AccessTokenRequestInitializer(
    private val accessToken: String,
    private val isCurrent: () -> Boolean,
    private val onUnauthorized: () -> Unit
) : HttpRequestInitializer {
    override fun initialize(request: HttpRequest) {
        // Google HTTP logging is independent of the Android diagnostics adapter.
        request.isLoggingEnabled = false
        request.isCurlLoggingEnabled = false
        request.interceptor = HttpExecuteInterceptor { outgoing ->
            if (!isCurrent()) throw IOException("Drive authorization is no longer current")
            outgoing.headers.authorization = "Bearer $accessToken"
        }
        request.unsuccessfulResponseHandler = HttpUnsuccessfulResponseHandler { _, response, _ ->
            if (response.statusCode == 401) onUnauthorized()
            false
        }
    }
}
