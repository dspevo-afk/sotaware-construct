package com.example.myapplication.projects

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
internal const val PROJECT_ENTRY_LIMIT = 2000
internal const val PROJECT_DEPTH_LIMIT = 24
internal const val PROJECT_PDF_BYTE_LIMIT = 128L * 1024 * 1024
internal const val PROJECT_DOWNLOAD_BYTE_LIMIT = 1024L * 1024 * 1024
private const val CATALOG_BYTE_LIMIT = 4 * 1024 * 1024

internal data class DriveProjectEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long = 0,
    val version: Long = 0,
    val md5: String = ""
) {
    val isFolder: Boolean get() = mimeType == DRIVE_FOLDER_MIME
    val isPdf: Boolean get() = mimeType == "application/pdf" ||
        (mimeType == "application/octet-stream" && name.endsWith(".pdf", ignoreCase = true))
    init {
        require(id.isNotBlank() && id.length <= PROJECT_FIELD_LIMIT)
        require(name.isNotBlank() && name.length <= PROJECT_FIELD_LIMIT)
        require(size >= 0 && version >= 0)
    }
}

/** Only listing, metadata and download operations are available to this feature. */
internal interface DriveProjectGateway {
    val accountId: String
    suspend fun children(folderId: String): List<DriveProjectEntry>
    suspend fun metadata(fileId: String): DriveProjectEntry
    suspend fun readPdf(file: DriveProjectEntry, consume: (InputStream) -> Unit)
    fun requireCurrent()
}

/** Names are display metadata. Only app-generated UUIDs are ever used as disk paths. */
internal data class DownloadedEntry(val id: String, val parent: String, val name: String, val folder: Boolean)
internal data class DownloadProgress(val name: String, val completed: Int, val total: Int)

internal class ProjectDownloads(private val baseDirectory: File, private val library: ProjectLibraryStore,
    private val checkpoint: (ProjectDownloadPhase, String) -> Unit = { _, _ -> }) {
    suspend fun recover() = downloadMutex.withLock {
        withContext(Dispatchers.IO) { ProjectDownloadJournal(baseDirectory, library).reconcile() }
    }

    fun directory(projectId: String): File {
        requireProjectId(projectId)
        return File(baseDirectory, projectId)
    }

    fun pdf(projectId: String, entryId: String): File {
        requireProjectId(entryId)
        return File(directory(projectId), "$entryId.pdf")
    }

    fun readCatalog(project: ProjectRecord): List<DownloadedEntry> {
        require(project.isDownload)
        val file = File(directory(project.id), "catalog.json")
        require(file.isFile && file.length() in 1..CATALOG_BYTE_LIMIT.toLong()) { "Downloaded project is unavailable" }
        return decodeCatalog(file.readText())
    }

    /** Stage every PDF, verify it, then publish one complete immutable project. */
    suspend fun download(
        gateway: DriveProjectGateway,
        selected: DriveProjectEntry,
        validatePdf: (File) -> Unit,
        progress: suspend (DownloadProgress) -> Unit
    ): ProjectRecord = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val journal = ProjectDownloadJournal(baseDirectory, library)
            journal.reconcile()
            gateway.requireCurrent()
            require(selected.isFolder)
            library.read().projects.firstOrNull { it.account == gateway.accountId && it.source == selected.id }?.let {
                readCatalog(it) // Never turn an unavailable previous download into a new empty project.
                return@withContext it
            }
            require(library.read().projects.size < MAX_PROJECTS)
            val root = gateway.metadata(selected.id)
            require(root.isFolder)
            val project = ProjectRecord(UUID.randomUUID().toString(), root.name, root.id, gateway.accountId)
            val catalog = mutableListOf<DownloadedEntry>()
            val pdfs = mutableListOf<Pair<DownloadedEntry, DriveProjectEntry>>()
            val seen = hashSetOf(root.id)
            val listings = linkedMapOf<String, List<DriveProjectEntry>>()
            var count = 0
            var expectedBytes = 0L
            suspend fun scan(folder: String, parent: String, depth: Int) {
                currentCoroutineContext().ensureActive()
                require(depth <= PROJECT_DEPTH_LIMIT) { "Project has too many nested folders" }
                val children = gateway.children(folder)
                listings[folder] = children
                for (entry in children) {
                    require(++count <= PROJECT_ENTRY_LIMIT) { "Project contains too many entries" }
                    require(seen.add(entry.id)) { "Drive folder contains a repeated document" }
                    if (!entry.isFolder && !entry.isPdf) continue
                    val local = DownloadedEntry(UUID.randomUUID().toString(), parent, entry.name, entry.isFolder)
                    catalog += local
                    if (entry.isFolder) scan(entry.id, local.id, depth + 1) else {
                        require(entry.size in 1..PROJECT_PDF_BYTE_LIMIT) { "PDF exceeds download limit or has no size" }
                        expectedBytes += entry.size
                        require(expectedBytes <= PROJECT_DOWNLOAD_BYTE_LIMIT) { "Project exceeds download limit" }
                        pdfs += local to entry
                    }
                }
            }
            progress(DownloadProgress(root.name, 0, 0))
            scan(root.id, "", 0)
            require(pdfs.isNotEmpty()) { "This project has no PDF drawings" }
            check(baseDirectory.isDirectory || baseDirectory.mkdirs())
            require(baseDirectory.usableSpace > expectedBytes + 32L * 1024 * 1024) { "Not enough storage for this project" }
            val staging = File(baseDirectory, ".incoming-${project.id}")
            val destination = directory(project.id)
            journal.begin(project)
            try {
                check(!destination.exists() && staging.mkdir())
                checkpoint(ProjectDownloadPhase.STAGING_CREATED, project.id)
                for ((index, pair) in pdfs.withIndex()) {
                    val (local, remote) = pair
                    currentCoroutineContext().ensureActive()
                    progress(DownloadProgress(remote.name, index, pdfs.size))
                    val output = File(staging, "${local.id}.pdf")
                    val digest = MessageDigest.getInstance("MD5")
                    var copied = 0L
                    var zeroReads = 0
                    val copyContext = currentCoroutineContext()
                    gateway.readPdf(remote) { input -> FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            copyContext.ensureActive()
                            gateway.requireCurrent()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) {
                                if (++zeroReads > com.example.myapplication.stage5.Stage5Limits.MAX_ZERO_READS) {
                                    throw IOException("PDF download made no progress")
                                }
                                continue
                            }
                            zeroReads = 0
                            copied += read
                            require(copied <= remote.size && copied <= PROJECT_PDF_BYTE_LIMIT) { "PDF exceeds declared size" }
                            stream.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        stream.fd.sync()
                    } }
                    checkpoint(ProjectDownloadPhase.PDF_SYNCED, project.id)
                    require(copied == remote.size) { "PDF download is incomplete" }
                    val md5 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(remote.md5.isNotBlank() && md5.equals(remote.md5, ignoreCase = true)) { "PDF checksum mismatch" }
                    validatePdf(output)
                    require(gateway.metadata(remote.id) == remote) { "PDF changed during download" }
                }
                // A failed page/list or a changed folder cannot be published as a complete project.
                for ((folder, original) in listings) {
                    require(gateway.children(folder).sortedBy { it.id } == original.sortedBy { it.id }) {
                        "Project changed during download; try again"
                    }
                }
                val serialized = encodeCatalog(catalog)
                FileOutputStream(File(staging, "catalog.json")).use { stream ->
                    stream.write(serialized.toByteArray(Charsets.UTF_8)); stream.fd.sync()
                }
                forceProjectDirectory(staging)
                checkpoint(ProjectDownloadPhase.CATALOG_SYNCED, project.id)
                journal.verified(project)
                checkpoint(ProjectDownloadPhase.VERIFIED, project.id)
                currentCoroutineContext().ensureActive()
                gateway.requireCurrent()
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                forceProjectDirectory(baseDirectory)
                forceProjectDirectory(requireNotNull(baseDirectory.parentFile))
                checkpoint(ProjectDownloadPhase.RENAMED, project.id)
                currentCoroutineContext().ensureActive()
                gateway.requireCurrent()
                // No suspension between publication and its durable library entry.
                val accepted = library.add(project)
                check(accepted.id == project.id)
                checkpoint(ProjectDownloadPhase.REGISTERED, project.id)
                journal.retire(project.id)
                checkpoint(ProjectDownloadPhase.RETIRED, project.id)
                project
            } catch (failure: Exception) {
                // Process death leaves the receipt for startup. Normal failure removes
                // only bytes proven unaccepted by the authoritative library.
                try { journal.abort(project.id) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    private companion object { val downloadMutex = Mutex() }
}

internal fun forceProjectDirectory(directory: File) {
    val path = directory.toPath()
    // Windows' provider cannot open a directory channel. Android must fsync it.
    if (path.fileSystem.provider().javaClass.name == "sun.nio.fs.WindowsFileSystemProvider") {
        require(Files.isDirectory(path) && !Files.isSymbolicLink(path))
        return
    }
    java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
}

internal fun encodeCatalog(entries: List<DownloadedEntry>): String = JsonObject().apply {
    addProperty("schema", 1)
    add("entries", JsonArray().apply { entries.forEach { entry -> add(JsonObject().apply {
        addProperty("id", entry.id); addProperty("parent", entry.parent)
        addProperty("name", entry.name); addProperty("folder", entry.folder)
    }) } })
}.toString().also { decodeCatalog(it) }

internal fun decodeCatalog(value: String): List<DownloadedEntry> {
    val root = boundedJsonObject(value, CATALOG_BYTE_LIMIT)
    root.requireKeys("schema", "entries")
    require(root.integer("schema") == 1L)
    val entries = root.array("entries", PROJECT_ENTRY_LIMIT).map { element ->
        val e = element.asJsonObject
        e.requireKeys("id", "parent", "name", "folder")
        require(e.get("folder").isJsonPrimitive && e.getAsJsonPrimitive("folder").isBoolean)
        DownloadedEntry(e.text("id").also(::requireProjectId), e.text("parent", true), e.text("name"), e.get("folder").asBoolean)
    }
    val byId = entries.associateBy { it.id }
    require(byId.size == entries.size && entries.any { !it.folder })
    entries.forEach { entry ->
        var parent = entry.parent
        val seen = hashSetOf(entry.id)
        while (parent.isNotEmpty()) {
            require(seen.add(parent) && seen.size <= PROJECT_DEPTH_LIMIT + 2)
            val folder = byId[parent] ?: throw IOException("Project folder is missing")
            require(folder.folder)
            parent = folder.parent
        }
    }
    return entries
}
