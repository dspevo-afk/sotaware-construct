package com.example.myapplication.projects

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import com.example.myapplication.stage2.DocumentManifestEntryV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class ProjectFile(val id: String, val name: String, val isFolder: Boolean, val uri: String)
internal data class ProjectDrawing(val projectId: String, val uri: String, val folder: String, val displayName: String)
internal class ProjectFiles(
    context: Context,
    private val downloads: ProjectDownloads,
    private val store: ProjectLibraryStore
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun addTree(
        uri: Uri,
        isGrantStillNeeded: suspend (Uri) -> Boolean
    ): ProjectRecord = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val tree = DocumentsContract.buildTreeDocumentUri(requireNotNull(uri.authority), documentId)
        val grant = resolver.takePersistableReadGrant(tree)
        try {
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            val root = query(document).singleOrNull() ?: throw IOException("Project folder is unavailable")
            require(root.isFolder)
            val accepted = store.add(ProjectRecord(UUID.randomUUID().toString(), root.name, tree.toString()))
            grant.markAccepted()
            accepted
        } catch (failure: Throwable) {
            releaseAdmissionGrant(grant, isGrantStillNeeded)
            throw failure
        }
    }

    /** Read accepted roots and project recents before releasing a failed admission. */
    fun hasAcceptedUseOfTreeGrant(tree: Uri, otherSources: Iterable<String>): Boolean = try {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
        val current = store.read()
        current.projects.any { project ->
            sourceUsesTreeGrant(project.source, tree) || project.recent.any { recent ->
                sourceUsesTreeGrant(recent.sourceUri, tree)
            }
        } || otherSources.any { sourceUsesTreeGrant(it, tree) }
    } catch (_: Exception) {
        // Corrupt or unavailable ownership state cannot prove that release is safe.
        true
    }

    private suspend fun releaseAdmissionGrant(
        grant: PersistedUriReadGrant,
        isGrantStillNeeded: suspend (Uri) -> Boolean
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val stillNeeded = try { isGrantStillNeeded(grant.uri) }
        catch (_: Exception) { true }
        grant.releaseIfUnused(resolver) { stillNeeded }
    }

    suspend fun list(project: ProjectRecord, folderId: String?): List<ProjectFile> = withContext(Dispatchers.IO) {
        val entries = if (project.isDownload) {
            downloads.readCatalog(project).filter { it.parent == folderId.orEmpty() }.map { entry ->
                ProjectFile(entry.id, entry.name, entry.folder,
                    if (entry.folder) "" else Uri.fromFile(downloads.pdf(project.id, entry.id)).toString())
            }
        } else {
            val tree = project.source.toUri()
            require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
            val parentId = folderId ?: DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            // A deleted/inaccessible folder must not look like a successfully empty listing.
            require(query(parent).singleOrNull()?.isFolder == true) { "Folder is unavailable" }
            query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)).map { file ->
                file.copy(uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.id).toString())
            }
        }
        entries.sortedWith(compareByDescending<ProjectFile> { it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })
    }

    private suspend fun query(uri: Uri): List<ProjectFile> = coroutineScope {
        val signal = CancellationSignal()
        // Cancel the provider query immediately even if its Binder call ignores thread interruption.
        val cancellation = launch(Dispatchers.Unconfined) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                val result = mutableListOf<ProjectFile>()
                val ids = hashSetOf<String>()
                resolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
                    null, null, null, signal)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                    val mimeColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                    var count = 0
                    while (cursor.moveToNext()) {
                        if (Thread.currentThread().isInterrupted) { signal.cancel(); throw InterruptedException() }
                        require(++count <= PROJECT_ENTRY_LIMIT) { "Folder contains too many entries" }
                        val id = cursor.getString(idColumn) ?: throw IOException("Folder entry has no identity")
                        val name = cursor.getString(nameColumn) ?: throw IOException("Folder entry has no name")
                        require(id.isNotBlank() && id.length <= PROJECT_FIELD_LIMIT && ids.add(id))
                        require(name.isNotBlank() && name.length <= PROJECT_FIELD_LIMIT)
                        val mime = cursor.getString(mimeColumn)
                        val folder = mime == Document.MIME_TYPE_DIR
                        if (folder || mime == "application/pdf" ||
                            ((mime.isNullOrBlank() || mime == "application/octet-stream") && name.endsWith(".pdf", true))) {
                            result += ProjectFile(id, name, folder, uri.toString())
                        }
                    }
                    if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                        throw IOException("Folder is still loading; refresh to try again")
                    }
                } ?: throw IOException("Folder is unavailable")
                result
            }
        } finally { cancellation.cancel(); signal.cancel() }
    }
}

/**
 * A document opened through a tree URI depends on that exact tree grant. A
 * standalone document URI has its own grant and is deliberately not treated as
 * depending on a tree grant. Malformed content URIs fail closed so cleanup
 * keeps access when ownership cannot be established.
 */
internal fun sourceUsesTreeGrant(sourceUri: String, treeUri: Uri): Boolean {
    return try {
        require(treeUri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(treeUri))
        val grantAuthority = requireNotNull(treeUri.authority)
        val grantDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            .also { require(it.isNotBlank() && it.length <= PROJECT_FIELD_LIMIT) }
        val source = Uri.parse(sourceUri)
        when {
            source == treeUri -> true
            source.scheme != ContentResolver.SCHEME_CONTENT -> false
            source.authority == null -> true
            source.authority != grantAuthority -> false
            !DocumentsContract.isTreeUri(source) -> source.pathSegments.firstOrNull() == "tree"
            else -> DocumentsContract.getTreeDocumentId(source)
                .also { require(it.isNotBlank() && it.length <= PROJECT_FIELD_LIMIT) } == grantDocumentId
        }
    } catch (_: Exception) {
        true
    }
}

/** Match only the provider's opaque document ID, never a display name or fingerprint. */
internal fun existingProjectSource(selected: String, entries: List<DocumentManifestEntryV1>): String {
    entries.firstOrNull { it.sourceUri == selected }?.let { return it.sourceUri }
    val uri = selected.toUri()
    val identity = providerDocumentIdentity(uri) ?: return selected
    val matches = entries.filter { providerDocumentIdentity(it.sourceUri.toUri()) == identity }
    require(matches.size <= 1) { "More than one saved association exists for this drawing" }
    return matches.singleOrNull()?.sourceUri ?: selected
}

private fun providerDocumentIdentity(uri: Uri): Pair<String, String>? = try {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.query != null || uri.fragment != null) null
    else requireNotNull(uri.authority) to DocumentsContract.getDocumentId(uri)
} catch (_: IllegalArgumentException) { null }
