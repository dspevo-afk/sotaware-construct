package com.example.myapplication.projects

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.core.net.toUri
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.R
import com.example.myapplication.stage9.GoogleDriveAuthClient
import com.example.myapplication.stage9.GoogleIdentity
import com.example.myapplication.stage9b.RecentDocumentRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectBrowserScreen(
    state: ProjectBrowserState,
    recentFiles: List<RecentDocumentRecord>,
    recentLoadFailed: Boolean,
    onOpenDrawing: (ProjectDrawing) -> Unit,
    onOpenIndividual: (Uri) -> Unit,
    onPickPdf: () -> Unit,
    onExport: (String, String) -> Unit,
    onImport: (String) -> Unit,
    onSettings: () -> Unit,
    googleAuth: GoogleDriveAuthClient,
    googleIdentity: GoogleIdentity?,
    isUriGrantInUse: suspend (Uri) -> Boolean
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showDrive by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<ProjectFile>?>(null) }
    var folderError by remember { mutableStateOf(false) }
    val project = state.project
    val folder = state.breadcrumbs.lastOrNull()
    var loadedLocation by remember { mutableStateOf<String?>(null) }
    val location = "${state.projectId}:${folder?.id}:${state.refresh}"
    val visibleEntries = entries.takeIf { loadedLocation == location }

    suspend fun grantStillNeeded(tree: Uri): Boolean {
        if (recentLoadFailed || state.files.hasAcceptedUseOfTreeGrant(tree, recentFiles.map { it.sourceUri })) return true
        return try { isUriGrantInUse(tree) } catch (_: Exception) { true }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            adding = true
            try {
                val accepted = state.files.addTree(uri, ::grantStillNeeded)
                state.reload()
                state.open(accepted)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar("Could not add this folder. Select it again to grant access.") }
            finally { adding = false }
        }
    }
    LaunchedEffect(location, project?.id) {
        entries = null; folderError = false; loadedLocation = location
        if (project != null) {
            try { entries = state.files.list(project, folder?.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { folderError = true }
        }
    }
    BackHandler(enabled = state.projectId != null && !showDrive) { state.back() }

    Scaffold(
        topBar = { TopAppBar(
            title = { Text(folder?.name ?: project?.name ?: stringResource(R.string.projects), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (state.projectId != null) IconButton(onClick = state::back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_back))
            } },
            actions = {
                if (state.projectId != null) {
                    IconButton(onClick = state::home) { Icon(Icons.Default.Home, stringResource(R.string.all_projects)) }
                    IconButton(onClick = { state.refresh++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh_folder)) }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
            }
        ) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        key(state.projectId, folder?.id) {
        val locationListState = rememberLazyListState()
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("project-browser-list"), state = locationListState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.libraryError) {
                item(key = "browser-slot-1", contentType = "browser-slot-1") { ProjectMessage(stringResource(R.string.project_library_unavailable)) { scope.launch { state.reload() } } }
            } else if (state.library == null) {
                item(key = "browser-slot-2", contentType = "browser-slot-2") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else if (state.projectId == null) {
                item(key = "browser-slot-3", contentType = "browser-slot-3") {
                    Text(stringResource(R.string.project_intro), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                item(key = "browser-slot-4", contentType = "browser-slot-4") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { folderPicker.launch(null) }, enabled = !adding, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CreateNewFolder, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_project_folder))
                        }
                        OutlinedButton(onClick = { showDrive = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.google_drive))
                        }
                    }
                }
                if (adding) item(key = "browser-slot-5", contentType = "browser-slot-5") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.library!!.projects.isEmpty()) item(key = "browser-slot-6", contentType = "browser-slot-6") {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.no_projects), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.no_projects_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(state.library!!.projects, key = { "project:${it.id}" }, contentType = { "project" }) { item ->
                    ProjectFolderRow(item.name, stringResource(if (item.isDownload) R.string.available_offline else R.string.linked_folder),
                        onClick = { state.open(item) })
                }
                item(key = "browser-slot-7", contentType = "browser-slot-7") { TextButton(onClick = onPickPdf) { Icon(Icons.Default.NoteAdd, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_pdf)) } }
                if (recentFiles.isNotEmpty() || recentLoadFailed) item(key = "browser-slot-8", contentType = "browser-slot-8") { SectionLabel(stringResource(R.string.recent_drawings)) }
                if (recentLoadFailed) item(key = "browser-slot-9", contentType = "browser-slot-9") { Text(stringResource(R.string.recent_files_unavailable)) }
                else items(recentFiles, key = { "single:${it.documentId}:${it.sourceUri}" }, contentType = { "drawing" }) { file ->
                    ProjectDrawingRow(file.displayName ?: "Drawing", stringResource(R.string.blueprint),
                        onClick = {
                            val memberships = state.library!!.projects.mapNotNull { candidate ->
                                candidate.recent.firstOrNull { it.documentId == file.documentId.value && it.sourceUri == file.sourceUri }
                                    ?.let { candidate to it }
                            }
                            val membership = memberships.singleOrNull()
                            if (membership == null) onOpenIndividual(file.sourceUri.toUri()) else {
                                val (owner, recent) = membership
                                onOpenDrawing(ProjectDrawing(owner.id, recent.sourceUri, recent.folder, recent.name))
                            }
                        },
                        onExport = { onExport(file.sourceUri, file.displayName ?: "Drawing") }, onImport = { onImport(file.sourceUri) })
                }
            } else if (project == null) {
                item(key = "browser-slot-10", contentType = "browser-slot-10") { Text(stringResource(R.string.project_library_unavailable)) }
            } else {
                item(key = "browser-slot-11", contentType = "browser-slot-11") { Text(state.folderLabel(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                item(key = "browser-slot-12", contentType = "browser-slot-12") { SectionLabel(stringResource(R.string.project_recent_files)) }
                if (project.recent.isEmpty()) item(key = "browser-slot-13", contentType = "browser-slot-13") { Text(stringResource(R.string.project_no_recent_files), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(project.recent, key = { "recent:${it.documentId}:${it.sourceUri}" }, contentType = { "drawing" }) { file ->
                    ProjectDrawingRow(file.name, file.folder, compact = true,
                        onClick = { onOpenDrawing(ProjectDrawing(project.id, file.sourceUri, file.folder, file.name)) },
                        onExport = { onExport(file.sourceUri, file.name) }, onImport = { onImport(file.sourceUri) })
                }
                item(key = "browser-slot-14", contentType = "browser-slot-14") { HorizontalDivider(Modifier.padding(vertical = 8.dp)); SectionLabel(stringResource(R.string.project_folder_contents)) }
                if (folderError && loadedLocation == location) {
                    item(key = "browser-slot-15", contentType = "browser-slot-15") { ProjectMessage(stringResource(R.string.project_folder_unavailable)) { state.refresh++ } }
                    if (!project.isDownload) item(key = "browser-slot-16", contentType = "browser-slot-16") { TextButton(onClick = { folderPicker.launch(project.source.toUri()) }) {
                        Text(stringResource(R.string.reconnect_project_folder))
                    } }
                } else if (visibleEntries == null) item(key = "browser-slot-17", contentType = "browser-slot-17") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (visibleEntries.isEmpty()) item(key = "browser-slot-18", contentType = "browser-slot-18") { Text(stringResource(R.string.project_folder_empty)) }
                else items(visibleEntries, key = { "entry:${it.id}" }, contentType = { if (it.isFolder) "folder" else "drawing" }) { file ->
                    if (file.isFolder) ProjectFolderRow(file.name, stringResource(R.string.project_subfolder), onClick = {
                        try { state.enter(file) }
                        catch (_: IllegalArgumentException) { scope.launch { snackbar.showSnackbar("This folder cannot be opened: nesting limit or repeated folder.") } }
                    }) else ProjectDrawingRow(file.name, "PDF", onClick = { onOpenDrawing(ProjectDrawing(project.id, file.uri, state.folderLabel(), file.name)) },
                        onExport = { onExport(file.uri, file.name) }, onImport = { onImport(file.uri) })
                }
            }
        }
        }
    }
    if (showDrive) DriveProjectPicker(state, googleAuth, googleIdentity,
        onDismiss = { showDrive = false }, onDownloaded = { project -> state.open(project); showDrive = false })
}

@Composable
internal fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }

@Composable
internal fun ProjectFolderRow(name: String, subtitle: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        ListItem(headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = trailing ?: { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
}

@Composable
private fun ProjectDrawingRow(name: String, subtitle: String, compact: Boolean = false,
    onClick: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        ListItem(headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
            leadingContent = { Icon(if (compact) Icons.Default.History else Icons.Default.PictureAsPdf, null,
                tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_save_file)) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.load_save_file)) }, onClick = { menu = false; onImport() })
                }
            } }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
    }
}

@Composable
internal fun ProjectMessage(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.project_retry)) }
    }
}
