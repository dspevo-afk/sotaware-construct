package com.example.myapplication.projects

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.example.myapplication.R
import com.example.myapplication.stage9.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveProjectPicker(
    state: ProjectBrowserState,
    auth: GoogleDriveAuthClient,
    signedInIdentity: GoogleIdentity?,
    onDismiss: () -> Unit,
    onDownloaded: (ProjectRecord) -> Unit
) {
    val activity = LocalContext.current.projectActivity()
    val viewModelOwner = activity as? ViewModelStoreOwner
        ?: error("The project picker Activity must own a ViewModelStore")
    val consentOwner = remember(viewModelOwner) {
        ViewModelProvider(viewModelOwner)[ProjectDriveConsentOwner::class.java]
    }
    val projectAuth = remember(activity, auth) {
        GoogleCredentialProjectDriveAuthorization(activity, auth)
    }
    val authorizationOwner = rememberSaveable(
        saver = projectDriveAuthorizationOwnerSaver(projectAuth, consentOwner.nonce)
    ) {
        ProjectDriveAuthorizationOwner(projectAuth, consentOwner.nonce)
    }
    val authorizationState by authorizationOwner.state.collectAsState()
    val pendingConsent = authorizationState.pendingConsent
    val connecting = authorizationState.connecting
    val scope = rememberCoroutineScope()
    var gateway by remember { mutableStateOf<GoogleDriveProjects?>(null) }
    var choosingSource by remember { mutableStateOf(true) }
    var path by remember { mutableStateOf(listOf(ProjectBreadcrumb("root", "My Drive"))) }
    var listing by remember { mutableStateOf<List<DriveProjectEntry>?>(null) }
    var listedLocation by remember { mutableStateOf<String?>(null) }
    var listedGateway by remember { mutableStateOf<GoogleDriveProjects?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var savingSource by remember { mutableStateOf(false) }
    var folderFilter by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<DriveProjectEntry>?>(null) }
    var searchResultQuery by remember { mutableStateOf("") }
    var searchGateway by remember { mutableStateOf<GoogleDriveProjects?>(null) }
    var searchFailed by remember { mutableStateOf(false) }
    val location = path.last()
    val busy = downloadJob != null || savingSource
    val searchingDrive = choosingSource && folderFilter.trim().length >= 2

    fun accept(accountSubject: String, grant: DriveAuthorizationRequestResult.Granted) {
        val connected = GoogleDriveProjects(accountSubject, grant)
        gateway?.close()
        gateway = connected
        val saved = state.library?.driveRoots?.firstOrNull { it.account == accountSubject }
        choosingSource = saved == null
        path = listOf(saved?.let { ProjectBreadcrumb(it.folderId, it.name) } ?: ProjectBreadcrumb("root", "My Drive"))
        error = null
    }
    val consent = rememberLauncherForActivityResult(DriveAuthorizationResolutionContract()) { result ->
        when (val outcome = authorizationOwner.consumeResolutionResult(result, signedInIdentity?.subject)) {
            ProjectDriveResolutionOutcome.Ignored -> Unit
            ProjectDriveResolutionOutcome.Expired -> {
                error = "Google Drive consent expired or the signed-in account changed. Reconnect and try again."
            }
            ProjectDriveResolutionOutcome.Cancelled -> error = "Google Drive connection was cancelled."
            is ProjectDriveResolutionOutcome.Granted -> try {
                accept(outcome.accountSubject, outcome.authorization)
            } catch (_: Exception) {
                error = "Google Drive read access was not granted. Try connecting again."
            }
            ProjectDriveResolutionOutcome.Failed -> {
                error = "Google Drive read access was not granted. Try connecting again."
            }
        }
    }
    fun connect(changeAccount: Boolean = false) {
        if (busy) return
        gateway?.close(); gateway = null
        error = null
        scope.launch {
            when (val outcome = authorizationOwner.connect(signedInIdentity, changeAccount, busy)) {
                ProjectDriveConnectOutcome.Ignored -> Unit
                is ProjectDriveConnectOutcome.Granted -> try {
                    accept(outcome.accountSubject, outcome.authorization)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = "Could not connect to Google Drive. Check your connection and Google authorization, then try again."
                }
                is ProjectDriveConnectOutcome.ResolutionRequired -> try {
                    consent.launch(
                        DriveAuthorizationResolutionRequest(
                            outcome.pending.operationId,
                            outcome.resolution
                        )
                    )
                } catch (cancelled: CancellationException) {
                    authorizationOwner.resolutionLaunchFailed(outcome.pending)
                    throw cancelled
                } catch (_: Exception) {
                    authorizationOwner.resolutionLaunchFailed(outcome.pending)
                    error = "Could not connect to Google Drive. Check your connection and Google authorization, then try again."
                }
                ProjectDriveConnectOutcome.Failed -> {
                    error = "Could not connect to Google Drive. Check your connection and Google authorization, then try again."
                }
            }
        }
    }
    fun retryConnection() {
        authorizationOwner.prepareRetry()
        connect()
    }
    fun invalidatePendingConsent() {
        authorizationOwner.invalidate()
    }
    LaunchedEffect(pendingConsent, authorizationState.accountGeneration, consentOwner.nonce, signedInIdentity?.subject) {
        if (authorizationOwner.invalidatePendingConsentIfStale(signedInIdentity?.subject)) {
            error = "Google Drive consent expired or the signed-in account changed. Reconnect and try again."
        }
    }
    DisposableEffect(gateway) {
        val owned = gateway
        onDispose { owned?.close() }
    }
    LaunchedEffect(gateway, location.id, refresh) {
        folderFilter = ""
        listing = null; listedLocation = null; listedGateway = null; error = null
        val current = gateway ?: return@LaunchedEffect
        try {
            listing = current.children(location.id)
            listedLocation = location.id
            listedGateway = current
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "This Drive folder is unavailable. Reconnect or choose another source folder." }
    }
    LaunchedEffect(gateway, choosingSource, folderFilter, location.id) {
        searchResults = null; searchResultQuery = ""; searchGateway = null; searchFailed = false
        val current = gateway ?: return@LaunchedEffect
        if (!searchingDrive) return@LaunchedEffect
        val query = folderFilter.trim()
        delay(300)
        try {
            searchResults = current.searchFolders(query)
            searchResultQuery = query
            searchGateway = current
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { searchFailed = true }
    }

    fun download(entry: DriveProjectEntry) {
        val current = gateway ?: return
        if (busy) return
        error = null
        downloadProgress = DownloadProgress(entry.name, 0, 0)
        downloadJob = scope.launch {
            try {
                val project = state.downloads.download(current, entry, validatePdf = { file ->
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer -> require(renderer.pageCount > 0) { "PDF has no pages" } }
                    }
                }, progress = { progress -> withContext(Dispatchers.Main) { downloadProgress = progress } })
                state.reload()
                onDownloaded(project)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Project download did not complete. Check the connection, available storage and access to every PDF, then retry. Your existing projects are unchanged." }
            finally { downloadJob = null; downloadProgress = null }
        }
    }
    fun back() {
        if (busy) { downloadJob?.cancel(); return }
        if (path.size > 1) path = path.dropLast(1) else {
            invalidatePendingConsent()
            onDismiss()
        }
    }
    Dialog(onDismissRequest = { if (busy) downloadJob?.cancel() else { invalidatePendingConsent(); onDismiss() } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        BackHandler { back() }
        Scaffold(topBar = { TopAppBar(
            title = { Text(stringResource(R.string.google_drive), maxLines = 1) },
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_back)) } },
            actions = { if (gateway != null && !busy) IconButton(onClick = { connect(true) }, enabled = !connecting) {
                Icon(Icons.Default.SwitchAccount, stringResource(R.string.switch_google_account))
            } }
        ) }) { padding ->
            key(gateway, location.id, choosingSource, searchingDrive) {
            val locationListState = rememberLazyListState()
            LazyColumn(Modifier.padding(padding).fillMaxSize(), state = locationListState,
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (gateway == null) {
                    item(key = "browser-slot-1", contentType = "browser-slot-1") { Text(stringResource(R.string.drive_projects_help), style = MaterialTheme.typography.bodyLarge) }
                    item(key = "browser-slot-2", contentType = "browser-slot-2") { Button(onClick = { connect() }, enabled = !connecting && pendingConsent == null) { Text(stringResource(R.string.connect_project_drive)) } }
                    if (connecting || pendingConsent != null) item(key = "browser-slot-3", contentType = "browser-slot-3") {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = ::retryConnection) { Text("Retry connection") }
                    }
                    if (!authorizationOwner.isConfigured) item(key = "browser-slot-4", contentType = "browser-slot-4") { Text(stringResource(R.string.project_drive_not_configured)) }
                } else {
                    item(key = "browser-slot-5", contentType = "browser-slot-5") {
                        Text(location.name, style = MaterialTheme.typography.headlineSmall)
                        Text(path.joinToString(" / ") { it.name }, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (busy) item(key = "browser-slot-6", contentType = "browser-slot-6") {
                        val progress = downloadProgress
                        Text(if (savingSource) "Saving source folder…" else if (progress?.total == 0) "Checking project drawings…" else "Downloading ${progress?.completed ?: 0} of ${progress?.total ?: 0}")
                        Text(progress?.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        if (!savingSource) TextButton(onClick = { downloadJob?.cancel() }) { Text(stringResource(android.R.string.cancel)) }
                    } else {
                        if (choosingSource) {
                            item(key = "browser-slot-7", contentType = "browser-slot-7") { Text(stringResource(R.string.choose_drive_source_help)) }
                            item(key = "browser-slot-8", contentType = "browser-slot-8") { Button(onClick = {
                                val current = gateway ?: return@Button
                                savingSource = true
                                scope.launch {
                                    try {
                                        val folder = current.metadata(location.id)
                                        require(folder.isFolder)
                                        withContext(Dispatchers.IO) { state.store.selectDriveRoot(DriveProjectRoot(current.accountId, folder.id, folder.name)) }
                                        state.reload()
                                        if (gateway === current) {
                                            path = listOf(ProjectBreadcrumb(folder.id, folder.name))
                                            choosingSource = false
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "This source folder could not be saved. Try again." }
                                    finally { savingSource = false }
                                }
                            }, enabled = listing != null && listedLocation == location.id && listedGateway === gateway) { Text(stringResource(R.string.use_drive_folder)) } }
                        } else {
                            item(key = "browser-slot-9", contentType = "browser-slot-9") { Text(stringResource(R.string.download_drive_projects_help)) }
                            item(key = "browser-slot-10", contentType = "browser-slot-10") { TextButton(onClick = { choosingSource = true; path = listOf(ProjectBreadcrumb("root", "My Drive")) }) {
                                Text(stringResource(R.string.change_drive_source))
                            } }
                            if (path.size > 1) item(key = "browser-slot-11", contentType = "browser-slot-11") { Button(onClick = {
                                download(DriveProjectEntry(location.id, location.name, DRIVE_FOLDER_MIME))
                            }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.download_this_project)) } }
                        }
                        if (listing == null && error == null) item(key = "browser-slot-12", contentType = "browser-slot-12") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        item(key = "browser-slot-13", contentType = "browser-slot-13") { OutlinedTextField(value = folderFilter, onValueChange = { folderFilter = it.take(256) },
                            label = { Text(stringResource(if (choosingSource) R.string.find_folder_in_drive else R.string.filter_drive_folders)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Search, null) }) }
                        val available = if (searchingDrive) searchResults.takeIf { searchResultQuery == folderFilter.trim() && searchGateway === gateway }
                            else listing?.takeIf { listedLocation == location.id && listedGateway === gateway }
                        val folders = available?.filter { it.isFolder && (searchingDrive || it.name.contains(folderFilter.trim(), ignoreCase = true)) }.orEmpty()
                        if (searchingDrive && available == null && !searchFailed) item(key = "browser-slot-14", contentType = "browser-slot-14") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (searchFailed) item(key = "browser-slot-15", contentType = "browser-slot-15") { Text(stringResource(R.string.project_drive_search_failed)) }
                        if (available != null && folders.isEmpty()) item(key = "browser-slot-16", contentType = "browser-slot-16") { Text(stringResource(
                            if (folderFilter.isEmpty()) R.string.no_drive_subfolders else R.string.no_matching_project_folders)) }
                        items(folders, key = { it.id }, contentType = { "drive-folder" }) { entry ->
                            val downloaded = state.library?.projects?.firstOrNull { it.account == gateway?.accountId && it.source == entry.id }
                            ProjectFolderRow(entry.name, if (downloaded != null) stringResource(R.string.available_offline) else stringResource(R.string.project_subfolder),
                                onClick = { if (path.size <= PROJECT_DEPTH_LIMIT) path =
                                    (if (searchingDrive) listOf(ProjectBreadcrumb("root", "My Drive")) else path) + ProjectBreadcrumb(entry.id, entry.name) },
                                trailing = if (choosingSource) null else { {
                                    IconButton(onClick = { if (downloaded != null) onDownloaded(downloaded) else download(entry) }) {
                                        Icon(if (downloaded == null) Icons.Default.Download else Icons.Default.FolderOpen,
                                            if (downloaded == null) "Download ${entry.name}" else "Open ${entry.name}")
                                    }
                                } })
                        }
                    }
                }
                error?.let { message -> item(key = "browser-slot-17", contentType = "browser-slot-17") { ProjectMessage(message) { if (gateway == null) connect() else refresh++ } } }
            }
            }
        }
    }
}

private tailrec fun Context.projectActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.projectActivity()
    else -> error("An Activity is required")
}
