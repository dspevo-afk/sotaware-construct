package com.example.myapplication.projects

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication.stage9b.RecentDocumentRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class ProjectBreadcrumb(val id: String, val name: String)

@Stable
internal class ProjectBrowserState(context: Context) {
    val store = ProjectLibraryStore(context)
    val downloads = ProjectDownloads(File(context.applicationContext.filesDir, "project-downloads"), store)
    val files = ProjectFiles(context, downloads, store)
    var library by mutableStateOf<ProjectLibrary?>(null)
        private set
    var libraryError by mutableStateOf(false)
        private set
    var projectId by mutableStateOf<String?>(null)
    var breadcrumbs by mutableStateOf<List<ProjectBreadcrumb>>(emptyList())
    var refresh by mutableIntStateOf(0)
    val project: ProjectRecord? get() = library?.projects?.firstOrNull { it.id == projectId }

    suspend fun reload() {
        try {
            downloads.recover()
            library = withContext(Dispatchers.IO) { store.read() }
            libraryError = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { libraryError = true }
    }

    fun home() { projectId = null; breadcrumbs = emptyList() }
    fun open(project: ProjectRecord) { projectId = project.id; breadcrumbs = emptyList(); refresh++ }
    fun back() {
        if (breadcrumbs.isNotEmpty()) breadcrumbs = breadcrumbs.dropLast(1) else home()
    }
    fun enter(folder: ProjectFile) {
        require(folder.isFolder && breadcrumbs.size < PROJECT_DEPTH_LIMIT)
        require(breadcrumbs.none { it.id == folder.id })
        breadcrumbs = breadcrumbs + ProjectBreadcrumb(folder.id, folder.name)
    }
    fun folderLabel(): String = (listOfNotNull(project?.name) + breadcrumbs.map { it.name })
        .joinToString(" / ").take(PROJECT_FIELD_LIMIT)

    suspend fun recordOpen(selection: ProjectDrawing, opened: RecentDocumentRecord) {
        withContext(Dispatchers.IO) { store.recordOpen(selection.projectId, opened, selection.folder) }
        reload()
    }
}

@Composable
internal fun rememberProjectBrowserState(): ProjectBrowserState {
    val context = LocalContext.current.applicationContext
    val saver = remember(context) { listSaver<ProjectBrowserState, String>(
        save = { state -> listOf(state.projectId.orEmpty()) + state.breadcrumbs.flatMap { listOf(it.id, it.name) } },
        restore = { saved -> ProjectBrowserState(context).apply {
            projectId = saved.firstOrNull()?.takeIf { it.isNotEmpty() }
            breadcrumbs = saved.drop(1).chunked(2).map { ProjectBreadcrumb(it[0], it[1]) }
        } }
    ) }
    val state = rememberSaveable(saver = saver) { ProjectBrowserState(context) }
    LaunchedEffect(state) { state.reload() }
    return state
}
