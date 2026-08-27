package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import com.example.myapplication.stage4.DriveGateway
import com.example.myapplication.stage4.DrivePage
import com.example.myapplication.stage4.GoogleDriveGateway
import com.example.myapplication.stage4.collectDrivePages
import com.example.myapplication.stage5.BoundedOutputStream
import com.example.myapplication.stage5.LegacyPageDataCodec
import com.example.myapplication.stage5.Stage5Limits
import com.example.myapplication.stage5.PhotoPathResolver
import com.example.myapplication.stage5.escapeDriveQueryLiteral
import com.example.myapplication.stage5.readBoundedBytes
import com.example.myapplication.stage5.validatePhotoBytes
import com.example.myapplication.stage5.validatePhotoFileName
import kotlinx.coroutines.*
import java.io.*
import java.nio.file.Files
import java.util.*

class DriveSyncManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("DriveSync", Context.MODE_PRIVATE)
    private var driveService: Drive? = null
    private var syncJob: Job? = null
    
    companion object {
        private const val PREF_BACKUP_FOLDER_ID = "backup_folder_id"
        private const val PREF_BACKUP_FOLDER_NAME = "backup_folder_name"
        private const val PREF_LAST_SYNC = "last_sync"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val TAG = "DriveSyncManager"
    }
    
    fun getSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                com.google.android.gms.common.api.Scope(DriveScopes.DRIVE_FILE),
                com.google.android.gms.common.api.Scope(DriveScopes.DRIVE_APPDATA),
                com.google.android.gms.common.api.Scope(DriveScopes.DRIVE)
            )
            .build()
    }
    
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && driveService != null
    }
    
    fun tryRestoreSession(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null && driveService == null) {
            initializeDriveService(account)
            return true
        }
        return account != null && driveService != null
    }
    
    fun clearSession() {
        driveService = null
        clearBackupFolder()
    }
    
    fun getSignedInEmail(): String? {
        return GoogleSignIn.getLastSignedInAccount(context)?.email
    }
    
    fun getLastSyncTime(): Long {
        return prefs.getLong(PREF_LAST_SYNC, 0)
    }
    
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
            
            Log.d(TAG, "Found ${drives.size} shared drives")
            drives.map {
                Log.d(TAG, "Shared drive: ${it.name} (${it.id})")
                DriveFolder(it.id, it.name, isSharedDrive = true) 
            } ?: emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error listing shared drives: ${e.message}", e)
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
            Log.e(TAG, "Error listing folders", e)
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
            Log.e(TAG, "Error listing folders in shared drive", e)
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
            Log.e(TAG, "Error creating folder in shared drive", e)
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
            Log.e(TAG, "Error creating folder", e)
            null
        }
    }

    fun initializeDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account
        
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("SOTAware Construct")
            .build()
    }
    
    fun getBackupFolderName(): String? {
        return prefs.getString(PREF_BACKUP_FOLDER_NAME, null)
    }
    
    fun setBackupFolder(folderId: String, folderName: String) {
        prefs.edit()
            .putString(PREF_BACKUP_FOLDER_ID, folderId)
            .putString(PREF_BACKUP_FOLDER_NAME, folderName)
            .apply()
    }
    
    fun clearBackupFolder() {
        prefs.edit()
            .remove(PREF_BACKUP_FOLDER_ID)
            .remove(PREF_BACKUP_FOLDER_NAME)
            .apply()
    }
    
    private fun getBackupFolderId(): String? {
        return prefs.getString(PREF_BACKUP_FOLDER_ID, null)
    }

    /** Stable root identity exposed to the Stage 4 coordinator; no display name is used. */
    fun getBackupFolderIdForSync(): String? = getBackupFolderId()

    /**
     * Creates the typed gateway for the current authenticated account. The
     * caller owns the coordinator lifecycle; this adapter does not create a
     * timer or retain a competing synchronization scope.
     */
    fun stage4Gateway(): DriveGateway? {
        val service = driveService ?: return null
        val accountId = getSignedInEmail() ?: return null
        return GoogleDriveGateway(service, accountId)
    }
    
    suspend fun createRootBackupFolder(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext null
            val folderName = "SOTAware Construct Backups"
            
            // Check if folder already exists in Drive root
            val query = "name=${escapeDriveQueryLiteral(folderName)} and ${escapeDriveQueryLiteral("root")} in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
            val folders = collectDrivePages { pageToken ->
                service.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setPageSize(100)
                    .setFields("files(id, name, webViewLink),nextPageToken")
                    .apply { if (pageToken != null) setPageToken(pageToken) }
                    .execute()
                    .let { DrivePage(it.files.orEmpty(), it.nextPageToken) }
            }
            if (folders.isNotEmpty()) {
                val folder = folders[0]
                return@withContext Pair(folder.id, folder.name)
            }
            
            // Create new folder in Drive root
            val folderMetadata = File()
                .setName(folderName)
                .setMimeType("application/vnd.google-apps.folder")
                .setParents(listOf("root"))
                
            val folder = service.files().create(folderMetadata)
                .setFields("id, name, webViewLink")
                .execute()
                
            Pair(folder.id, folder.name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error creating root backup folder", e)
            null
        }
    }
    
    /**
     * Source-compatible legacy helper. Folder lookup/creation by display name
     * is intentionally disabled; the Stage 4 gateway uses stable IDs and
     * DocumentId app properties, and performs creation only on an upload path.
     */
    @Deprecated("Use stage4.DriveGateway with a SyncScope")
    suspend fun createPdfFolder(pdfName: String): String? {
        Log.w(TAG, "Ignoring legacy display-name folder lookup for '$pdfName'")
        return null
    }
    
    /**
     * Source-compatible legacy method. A display-name-only caller cannot
     * satisfy Stage 4 identity and generation invariants, so it fails closed
     * instead of silently rebinding an untagged Drive folder.
     */
    @Deprecated("Use stage4.SyncCoordinator.enqueueUpload")
    suspend fun uploadAnnotations(
        pdfName: String,
        pageData: Map<Int, PageData>
    ): Boolean {
        Log.w(TAG, "Ignoring legacy display-name upload for '$pdfName'; use Stage 4 SyncCoordinator")
        return false
    }

    private suspend fun legacyUploadAnnotationsByDisplayName(
        pdfName: String,
        pageData: Map<Int, PageData>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: run {
                Log.e(TAG, "uploadAnnotations: driveService is null")
                return@withContext false
            }
            
            Log.d(TAG, "uploadAnnotations: Starting upload for '$pdfName' with ${pageData.size} pages")
            
            val pdfFolderId = createPdfFolder(pdfName) ?: run {
                Log.e(TAG, "uploadAnnotations: Failed to create/get PDF folder")
                return@withContext false
            }
            
            // Collect all unique image file names from photo pins
            val allImageFiles = mutableSetOf<String>()
            pageData.values.forEach { data ->
                data.photoPins.forEach { pin ->
                    allImageFiles.addAll(pin.imageFileNames)
                }
            }
            
            Log.d(TAG, "uploadAnnotations: Found ${allImageFiles.size} photo files to upload")
            
            // Upload photo files if any exist
            if (allImageFiles.isNotEmpty() && !uploadPhotoFiles(pdfFolderId, allImageFiles)) return@withContext false
            
            // Serialize page data
            val dataJson = serializePageData(pageData)
            Log.d(TAG, "uploadAnnotations: Serialized data size: ${dataJson.length} chars")
            
            val tempFile = kotlin.io.path.createTempFile("annotations", ".json").toFile()
            tempFile.writeText(dataJson)
            
            // Use date-based filename for daily backups
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val todayDate = dateFormat.format(Date())
            val fileName = "annotations_$todayDate.json"
            
            // Check if today's file exists
            val query = "name=${escapeDriveQueryLiteral(fileName)} and ${escapeDriveQueryLiteral(pdfFolderId)} in parents and trashed=false"
            val result = service.files().list()
                .setQ(query)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(id, modifiedTime)")
                .execute()
            
            Log.d(TAG, "uploadAnnotations: Found ${result.files.size} existing $fileName files")
            
            val mediaContent = com.google.api.client.http.FileContent("application/json", tempFile)
            
            if (result.files.isNotEmpty()) {
                // Update existing file - don't set parents on update
                Log.d(TAG, "uploadAnnotations: Updating existing file ${result.files[0].id}")
                service.files().update(result.files[0].id, null, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()
                Log.d(TAG, "uploadAnnotations: Update successful")
            } else {
                // Create new file
                Log.d(TAG, "uploadAnnotations: Creating new file")
                val fileMetadata = File()
                    .setName(fileName)
                    .setParents(listOf(pdfFolderId))
                    
                val created = service.files().create(fileMetadata, mediaContent)
                    .setSupportsAllDrives(true)
                    .setFields("id")
                    .execute()
                Log.d(TAG, "uploadAnnotations: Created file ${created.id}")
            }
            
            tempFile.delete()
            prefs.edit().putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply()
            Log.d(TAG, "uploadAnnotations: Upload complete!")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading annotations", e)
            false
        }
    }
    
    private suspend fun uploadPhotoFiles(pdfFolderId: String, imageFileNames: Set<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext false
            
            // Create or get photos subfolder
            val photosFolderId = createPhotosFolder(pdfFolderId) ?: return@withContext false
            
            Log.d(TAG, "uploadPhotoFiles: Uploading ${imageFileNames.size} photos to folder $photosFolderId")
            
            imageFileNames.forEach { fileName ->
                try {
                    validatePhotoFileName(fileName)
                    val localFile = legacyPhotoFile(fileName)
                    if (!localFile.exists()) {
                        throw IOException("local photo file not found: $fileName")
                    }
                    
                    // Check if file already exists in Drive
                    val query = "name=${escapeDriveQueryLiteral(fileName)} and ${escapeDriveQueryLiteral(photosFolderId)} in parents and trashed=false"
                    val result = service.files().list()
                        .setQ(query)
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .setFields("files(id)")
                        .execute()
                    
                    val mediaContent = com.google.api.client.http.FileContent("image/jpeg", localFile)
                    
                    if (result.files.isNotEmpty()) {
                        // Update existing file
                        service.files().update(result.files[0].id, null, mediaContent)
                            .setSupportsAllDrives(true)
                            .execute()
                        Log.d(TAG, "uploadPhotoFiles: Updated $fileName")
                    } else {
                        // Create new file
                        val fileMetadata = File()
                            .setName(fileName)
                            .setParents(listOf(photosFolderId))
                        
                        service.files().create(fileMetadata, mediaContent)
                            .setSupportsAllDrives(true)
                            .setFields("id")
                            .execute()
                        Log.d(TAG, "uploadPhotoFiles: Created $fileName")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    throw IOException("uploadPhotoFiles failed for $fileName", e)
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "uploadPhotoFiles: Error", e)
            false
        }
    }
    
    private suspend fun createPhotosFolder(pdfFolderId: String): String? = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext null
            
            // Check if photos folder already exists
            val query = "name=${escapeDriveQueryLiteral("photos")} and ${escapeDriveQueryLiteral(pdfFolderId)} in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
            val result = service.files().list()
                .setQ(query)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(id)")
                .execute()
            
            if (result.files.isNotEmpty()) {
                return@withContext result.files[0].id
            }
            
            // Create new photos folder
            val folderMetadata = File()
                .setName("photos")
                .setMimeType("application/vnd.google-apps.folder")
                .setParents(listOf(pdfFolderId))
            
            val folder = service.files().create(folderMetadata)
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
            
            folder.id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "createPhotosFolder: Error", e)
            null
        }
    }
    
    /** Source-compatible legacy method; missing DocumentId scope is rejected. */
    @Deprecated("Use stage4.SyncCoordinator.enqueueRemoteAcceptance")
    suspend fun downloadAnnotations(pdfName: String): Map<Int, PageData>? {
        Log.w(TAG, "Ignoring legacy display-name download for '$pdfName'; use Stage 4 SyncCoordinator")
        return null
    }

    private suspend fun legacyDownloadAnnotationsByDisplayName(pdfName: String): Map<Int, PageData>? = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: run {
                Log.e(TAG, "downloadAnnotations: driveService is null")
                return@withContext null
            }
            
            Log.d(TAG, "downloadAnnotations: Starting download for '$pdfName'")
            
            val pdfFolderId = createPdfFolder(pdfName) ?: run {
                Log.e(TAG, "downloadAnnotations: Failed to get PDF folder")
                return@withContext null
            }
            
            Log.d(TAG, "downloadAnnotations: PDF folder ID: $pdfFolderId")
            
            // Find all annotations files (with date suffixes)
            val query = "${escapeDriveQueryLiteral(pdfFolderId)} in parents and trashed=false and (name contains 'annotations')"
            Log.d(TAG, "downloadAnnotations: Searching with query: $query")
            
            val result = service.files().list()
                .setQ(query)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(id, name, modifiedTime)")
                .setOrderBy("modifiedTime desc")
                .execute()
            
            Log.d(TAG, "downloadAnnotations: Found ${result.files.size} annotations files")
            
            if (result.files.isEmpty()) {
                Log.e(TAG, "downloadAnnotations: No annotations files found")
                return@withContext null
            }
            
            // Use the most recently modified file
            Log.d(TAG, "downloadAnnotations: Using most recent file: ${result.files[0].name}")
            
            val fileId = result.files[0].id
            Log.d(TAG, "downloadAnnotations: Downloading file $fileId")
            
            val outputStream = ByteArrayOutputStream()
            val boundedOutputStream = BoundedOutputStream(outputStream, Stage5Limits.MAX_JSON_BYTES, "legacy Drive annotations")
            service.files().get(fileId)
                .setSupportsAllDrives(true)
                .executeMediaAndDownloadTo(boundedOutputStream)
            
            val dataJson = outputStream.toString("UTF-8")
            Log.d(TAG, "downloadAnnotations: Downloaded ${dataJson.length} bytes")
            
            val pageData = deserializePageData(dataJson)
            Log.d(TAG, "downloadAnnotations: Deserialized ${pageData.size} pages")
            
            // Download photo files
            val allImageFiles = mutableSetOf<String>()
            pageData.values.forEach { data ->
                data.photoPins.forEach { pin ->
                    allImageFiles.addAll(pin.imageFileNames)
                }
            }
            
            if (allImageFiles.isNotEmpty() && !downloadPhotoFiles(pdfFolderId, allImageFiles)) {
                return@withContext null
            }
            
            pageData
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading annotations: ${e.message}", e)
            null
        }
    }
    
    private suspend fun downloadPhotoFiles(pdfFolderId: String, imageFileNames: Set<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext false
            
            // Get photos folder ID
            val query = "name=${escapeDriveQueryLiteral("photos")} and ${escapeDriveQueryLiteral(pdfFolderId)} in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
            val result = service.files().list()
                .setQ(query)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(id)")
                .execute()
            
            if (result.files.isEmpty()) {
                Log.w(TAG, "downloadPhotoFiles: No photos folder found")
                return@withContext false
            }
            
            val photosFolderId = result.files[0].id
            Log.d(TAG, "downloadPhotoFiles: Downloading from folder $photosFolderId")
            
            imageFileNames.forEach { fileName ->
                var temporary: java.io.File? = null
                var resolver: PhotoPathResolver? = null
                try {
                    validatePhotoFileName(fileName)
                    // Find file in photos folder
                    val fileQuery = "name=${escapeDriveQueryLiteral(fileName)} and ${escapeDriveQueryLiteral(photosFolderId)} in parents and trashed=false"
                    val fileResult = service.files().list()
                        .setQ(fileQuery)
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .setFields("files(id)")
                        .execute()
                    
                    if (fileResult.files.isEmpty()) {
                        throw IOException("photo file not found in Drive: $fileName")
                    }
                    
                    val fileId = fileResult.files[0].id
                    resolver = PhotoPathResolver(context.filesDir)
                    val localFile = resolver!!.resolve(fileName)
                    temporary = resolver!!.newInternalFile("stage5-legacy", ".tmp")
                    
                    // Download file
                    FileOutputStream(temporary!!).use { outputStream ->
                        val bounded = BoundedOutputStream(outputStream, Stage5Limits.MAX_PHOTO_BYTES, "legacy Drive photo")
                        service.files().get(fileId)
                            .setSupportsAllDrives(true)
                            .executeMediaAndDownloadTo(bounded)
                        bounded.flush()
                        outputStream.fd.sync()
                    }
                    val bytes = temporary!!.inputStream().use {
                        readBoundedBytes(it, Stage5Limits.MAX_PHOTO_BYTES, "legacy Drive photo")
                    }
                    validatePhotoBytes(bytes)
                    Files.move(
                        temporary!!.toPath(),
                        localFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    
                    Log.d(TAG, "downloadPhotoFiles: Downloaded $fileName")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    throw IOException("downloadPhotoFiles failed for $fileName", e)
                } finally {
                    val staged = temporary
                    val pathResolver = resolver
                    if (staged != null && pathResolver != null) {
                        runCatching {
                            pathResolver.ensureContained(staged.toPath(), "legacy photo cleanup")
                            Files.deleteIfExists(staged.toPath())
                        }.onFailure { cleanupError ->
                            Log.e(TAG, "downloadPhotoFiles: temporary cleanup failed for $fileName", cleanupError)
                        }
                    }
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "downloadPhotoFiles: Error", e)
            false
        }
    }
    
    /** Source-compatible legacy probe; reads must be scoped by the Stage 4 gateway. */
    @Deprecated("Use stage4.SyncCoordinator.enqueueRemoteCheck")
    suspend fun getRemoteModifiedTime(pdfName: String): Long? {
        Log.w(TAG, "Ignoring legacy display-name remote probe for '$pdfName'; use Stage 4 SyncCoordinator")
        return null
    }

    private suspend fun legacyGetRemoteModifiedTimeByDisplayName(pdfName: String): Long? = withContext(Dispatchers.IO) {
        try {
            val service = driveService ?: return@withContext null
            val pdfFolderId = createPdfFolder(pdfName) ?: return@withContext null
            
            // Look for any annotations file and get the most recent one
            val query = "${escapeDriveQueryLiteral(pdfFolderId)} in parents and trashed=false and (name contains 'annotations')"
            val result = service.files().list()
                .setQ(query)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(modifiedTime)")
                .setOrderBy("modifiedTime desc")
                .execute()
            
            if (result.files.isEmpty()) {
                return@withContext null
            }
            
            result.files[0].modifiedTime?.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error getting remote modified time", e)
            null
        }
    }
    
    /**
     * Retained as a source-compatible legacy entry point only. Active sync
     * must be started by the Stage 4 SyncCoordinator, which has DocumentId,
     * account/root scope, generation, cursor, and lifecycle ownership. The
     * former independent timer is intentionally not restarted here.
     */
    @Deprecated("Use stage4.SyncCoordinator.startPeriodic")
    fun startAutoSync(
        getCurrentPdfName: () -> String?,
        getPageData: suspend () -> Map<Int, PageData>?,
        onUpdateAvailable: (String) -> Unit
    ) {
        @Suppress("UNUSED_VARIABLE")
        val legacyArguments = Triple(getCurrentPdfName, getPageData, onUpdateAvailable)
        stopAutoSync()
        Log.w(TAG, "Ignoring legacy auto-sync request; use the Stage 4 SyncCoordinator")
    }

    private fun legacyPhotoFile(fileName: String): java.io.File {
        validatePhotoFileName(fileName)
        return PhotoPathResolver(context.filesDir).resolve(fileName)
    }
    
    fun stopAutoSync() {
        syncJob?.cancel()
        syncJob = null
    }

    suspend fun stopAutoSyncAndJoin() {
        val job = syncJob
        syncJob = null
        job?.cancelAndJoin()
    }
    
    fun serializePageData(pageData: Map<Int, PageData>): String = LegacyPageDataCodec.encode(pageData)

    fun deserializePageData(json: String): Map<Int, PageData> = LegacyPageDataCodec.decode(json)
}

data class PageData(
    val paths: List<DrawnPath>,
    val measurements: List<Measurement>,
    val notes: List<Note>,
    val photoPins: List<PhotoPin>,
    val scale: PageScale?,
    val shapes: List<Shape> = emptyList()
)
