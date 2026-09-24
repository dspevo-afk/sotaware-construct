package com.example.myapplication.projects

import android.accounts.Account
import android.app.Activity
import com.example.myapplication.stage9.DriveAuthorizationRequestResult
import com.example.myapplication.stage9.GoogleIdentity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.http.HttpExecuteInterceptor
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.HttpUnsuccessfulResponseHandler
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal const val DRIVE_PROJECT_READ_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

/** Incremental, explicit consent for importing existing folders. Backup authorization is separate. */
internal suspend fun requestProjectDriveAccess(activity: Activity, identity: GoogleIdentity): DriveAuthorizationRequestResult {
    val result = Identity.getAuthorizationClient(activity).authorize(
        projectDriveAuthorizationRequest(identity)
    ).await()
    return if (result.hasResolution()) {
        DriveAuthorizationRequestResult.ResolutionRequired(requireNotNull(result.pendingIntent).intentSender)
    } else DriveAuthorizationRequestResult.Granted(result.accessToken, result.grantedScopes.orEmpty().toSet())
}

internal fun projectDriveAuthorizationRequest(identity: GoogleIdentity): AuthorizationRequest =
    AuthorizationRequest.Builder()
        .setAccount(Account(identity.email, "com.google"))
        .setOptOutIncludingGrantedScopes(true)
        .setRequestedScopes(listOf(Scope(DRIVE_PROJECT_READ_SCOPE)))
        .build()

/** An in-memory read session with bounded, paginated queries and no write surface. */
internal class GoogleDriveProjects(
    override val accountId: String,
    grant: DriveAuthorizationRequestResult.Granted,
    transport: HttpTransport = NetHttpTransport()
) : DriveProjectGateway, AutoCloseable {
    private val active = AtomicBoolean(true)
    private val service: Drive

    init {
        require(accountId.isNotBlank())
        require(DRIVE_PROJECT_READ_SCOPE in grant.grantedScopes && !grant.accessToken.isNullOrBlank()) {
            "Google Drive read access was not granted"
        }
        val token = grant.accessToken
        service = Drive.Builder(transport, GsonFactory.getDefaultInstance(), HttpRequestInitializer { request ->
            request.isLoggingEnabled = false
            request.isCurlLoggingEnabled = false
            request.followRedirects = false
            request.connectTimeout = 30_000
            request.readTimeout = 30_000
            request.interceptor = HttpExecuteInterceptor { outgoing ->
                requireCurrent()
                outgoing.headers.authorization = "Bearer $token"
            }
            request.unsuccessfulResponseHandler = HttpUnsuccessfulResponseHandler { _, response, _ ->
                if (response.statusCode == 401) close()
                false
            }
        }).setApplicationName("SOTAware Construct").build()
    }

    override fun close() { active.set(false) }
    override fun requireCurrent() { if (!active.get()) throw IOException("Reconnect Google Drive to continue") }

    override suspend fun children(folderId: String): List<DriveProjectEntry> {
        requireDriveId(folderId)
        return list("'$folderId' in parents and trashed = false")
    }

    suspend fun searchFolders(name: String): List<DriveProjectEntry> {
        val text = name.trim()
        require(text.length in 2..256)
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
        return list("trashed = false and mimeType = '$DRIVE_FOLDER_MIME' and name contains '$escaped'")
    }

    private suspend fun list(query: String): List<DriveProjectEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<DriveProjectEntry>()
        val pages = hashSetOf<String>()
        val ids = hashSetOf<String>()
        var page: String? = null
        do {
            currentCoroutineContext().ensureActive()
            requireCurrent()
            val response = service.files().list()
                .setQ(query)
                .setPageSize(100).setPageToken(page)
                .setSupportsAllDrives(true).setIncludeItemsFromAllDrives(true)
                .setFields("nextPageToken,incompleteSearch,files($PROJECT_DRIVE_FIELDS)")
                .execute()
            currentCoroutineContext().ensureActive()
            requireCurrent()
            require(response.incompleteSearch != true) { "Google Drive listing is incomplete" }
            for (file in response.files.orEmpty()) {
                val entry = file.projectEntry()
                require(ids.add(entry.id) && entries.size < PROJECT_ENTRY_LIMIT) { "Google Drive folder is too large or incomplete" }
                entries += entry
            }
            page = response.nextPageToken?.takeIf { it.isNotBlank() }
            require(page == null || (pages.add(page) && pages.size <= 100)) { "Google Drive pagination did not complete" }
        } while (page != null)
        entries.sortedWith(compareByDescending<DriveProjectEntry> { it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })
    }

    override suspend fun metadata(fileId: String): DriveProjectEntry = withContext(Dispatchers.IO) {
        requireDriveId(fileId)
        requireCurrent()
        val file = service.files().get(fileId).setSupportsAllDrives(true).setFields(PROJECT_DRIVE_FIELDS).execute()
        currentCoroutineContext().ensureActive()
        requireCurrent()
        file.projectEntry()
    }

    override suspend fun readPdf(file: DriveProjectEntry, consume: (InputStream) -> Unit): Unit = withContext(Dispatchers.IO) {
        requireDriveId(file.id)
        require(file.isPdf)
        requireCurrent()
        service.files().get(file.id).setSupportsAllDrives(true).executeMediaAsInputStream().use(consume)
        currentCoroutineContext().ensureActive()
        requireCurrent()
    }
}

private const val PROJECT_DRIVE_FIELDS = "id,name,mimeType,size,version,md5Checksum,trashed,capabilities(canDownload)"
private fun requireDriveId(id: String) { require(id.length in 1..256 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) }
private fun com.google.api.services.drive.model.File.projectEntry(): DriveProjectEntry {
    require(trashed != true)
    requireDriveId(requireNotNull(id))
    val entry = DriveProjectEntry(id, requireNotNull(name), requireNotNull(mimeType), getSize() ?: 0L, version ?: 0L, md5Checksum.orEmpty())
    if (entry.isPdf) require(capabilities?.canDownload != false) { "This PDF cannot be downloaded" }
    return entry
}
