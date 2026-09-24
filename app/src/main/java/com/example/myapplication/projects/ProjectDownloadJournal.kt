package com.example.myapplication.projects

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal enum class ProjectDownloadPhase { STAGING_CREATED, PDF_SYNCED, CATALOG_SYNCED, VERIFIED, RENAMED, REGISTERED, RETIRED }
internal data class ProjectDownloadFile(val name: String, val bytes: Long, val sha256: String)
private data class ProjectDownloadIntent(val project: ProjectRecord, val files: List<ProjectDownloadFile>?)

/** One UUID-owned transaction receipt, durable before any project bytes are created.
 * Call only under ProjectDownloads' process-wide mutex. No unlisted directory is garbage.
 */
internal class ProjectDownloadJournal(private val base: File, private val library: ProjectLibraryStore) {
    private val markerPattern = Regex("[.]download-([0-9a-f-]{36})[.]json(?:[.]tmp)?")
    private fun marker(id: String) = File(base, ".download-$id.json").also { requireProjectId(id) }
    private fun staging(id: String) = File(base, ".incoming-$id").also { requireProjectId(id) }
    private fun destination(id: String) = File(base, id).also { requireProjectId(id) }
    private fun requireRoot() {
        require(Files.isDirectory(base.toPath(), NOFOLLOW_LINKS)) { "Project storage unavailable" }
        require(base.canonicalFile.parentFile == base.parentFile.canonicalFile) { "Project storage contains an alias" }
    }
    fun begin(project: ProjectRecord) {
        requireRoot()
        require(project.isDownload && project.recent.isEmpty())
        for (file in listOf(marker(project.id), File(marker(project.id).path + ".tmp"), staging(project.id), destination(project.id))) {
            require(!Files.exists(file.toPath(), NOFOLLOW_LINKS)) { "Project transaction identity already exists" }
        }
        forceProjectDirectory(requireNotNull(base.parentFile))
        write(ProjectDownloadIntent(project, null))
    }
    fun verified(project: ProjectRecord) {
        val owned = staging(project.id)
        val entries = readCatalog(owned)
        val names = entries.filterNot { it.folder }.map { "${it.id}.pdf" } + "catalog.json"
        requireOwnedDirectory(owned, names.toSet())
        val files = names.sorted().map { name ->
            val file = File(owned, name)
            val size = file.length()
            require(size in 1..if (name == "catalog.json") 4L * 1024 * 1024 else PROJECT_PDF_BYTE_LIMIT)
            ProjectDownloadFile(name, size, digest(file))
        }
        require(files.sumOf { it.bytes } <= PROJECT_DOWNLOAD_BYTE_LIMIT + 4L * 1024 * 1024)
        forceProjectDirectory(owned)
        write(ProjectDownloadIntent(project, files))
    }
    fun retire(id: String) {
        requireRoot()
        for (file in listOf(File(marker(id).path + ".tmp"), marker(id))) {
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                Files.delete(file.toPath())
            }
        }
        forceProjectDirectory(base)
    }
    /** Ordinary cancellation discards unaccepted output, never an uncertain or accepted project. */
    fun abort(id: String) {
        requireRoot()
        val current = library.read()
        val file = marker(id)
        if (!file.exists()) return
        val intent = read(file, id)
        val accepted = current.projects.firstOrNull { it.id == id }
        if (accepted != null) {
            require(accepted.source == intent.project.source && accepted.account == intent.project.account)
            return // Accepted bytes and receipt are verified/retired on the next reconciliation.
        }
        // Persist abandonment before removing verified bytes; interrupted cleanup
        // must resume cleanup rather than verify an already partly removed project.
        if (intent.files != null) write(intent.copy(files = null))
        removeOwned(staging(id)); removeOwned(destination(id)); retire(id)
    }
    fun reconcile() {
        if (!Files.exists(base.toPath(), NOFOLLOW_LINKS)) return
        requireRoot()
        library.read() // Corruption never authorizes cleanup of an apparently empty library.
        val roots = base.listFiles() ?: throw IOException("Project storage could not be enumerated")
        require(roots.size <= 8192) { "Too many project storage entries" }
        val ids = roots.mapNotNull { markerPattern.matchEntire(it.name)?.groupValues?.get(1) }.toSortedSet()
        for (id in ids) {
            requireProjectId(id)
            val file = marker(id)
            val temporary = File(file.path + ".tmp")
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                // A crash during the first atomic receipt write precedes staging creation.
                // The first receipt can be torn before its atomic rename. Its
                // reserved temporary name is reclaimable only if neither a
                // project directory nor an authoritative library entry exists.
                // Do not parse incomplete bytes as an accepted receipt.
                require(Files.isRegularFile(temporary.toPath(), NOFOLLOW_LINKS))
                require(!Files.exists(staging(id).toPath(), NOFOLLOW_LINKS) &&
                    !Files.exists(destination(id).toPath(), NOFOLLOW_LINKS))
                require(library.read().projects.none { it.id == id })
                retire(id)
                continue
            }
            val intent = read(file, id)
            val accepted = library.read().projects.firstOrNull { it.id == id }
            if (accepted != null) {
                require(accepted.source == intent.project.source && accepted.account == intent.project.account)
                verifyContents(destination(id), intent)
                require(!staging(id).exists()) { "Accepted project has ambiguous staging" }
                retire(id)
            } else if (intent.files == null) {
                removeOwned(staging(id)); removeOwned(destination(id)); retire(id)
            } else {
                val staged = staging(id); val final = destination(id)
                require(!(staged.exists() && final.exists())) { "Project publication is ambiguous" }
                val complete = if (final.exists()) final else staged
                verifyContents(complete, intent)
                // An independently registered source wins; reclaim only this receipt's unaccepted copy.
                val duplicate = library.read().projects.firstOrNull {
                    it.source == intent.project.source && it.account == intent.project.account
                }
                if (duplicate != null) {
                    write(intent.copy(files = null))
                    removeOwned(complete); retire(id); continue
                }
                if (complete == staged) {
                    Files.move(staged.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    forceProjectDirectory(base)
                }
                check(library.add(intent.project).id == id)
                retire(id)
            }
        }
    }
    private fun verifyContents(directory: File, intent: ProjectDownloadIntent) {
        val files = requireNotNull(intent.files) { "Accepted project has no verification receipt" }
        requireOwnedDirectory(directory, files.map { it.name }.toSet())
        val entries = readCatalog(directory)
        require(files.map { it.name }.toSet() == entries.filterNot { it.folder }.map { "${it.id}.pdf" }.toSet() + "catalog.json")
        for (entry in files) {
            val file = File(directory, entry.name)
            require(file.length() == entry.bytes && digest(file) == entry.sha256) { "Recovered project content changed" }
        }
    }
    private fun readCatalog(directory: File): List<DownloadedEntry> {
        requireOwnedDirectory(directory)
        return decodeCatalog(readBounded(File(directory, "catalog.json")))
    }
    private fun requireOwnedDirectory(directory: File, expected: Set<String>? = null): Array<File> {
        requireRoot()
        require(directory.absoluteFile.parentFile == base.absoluteFile)
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) && directory.canonicalFile.parentFile == base.canonicalFile)
        val files = directory.listFiles() ?: throw IOException("Project transaction directory unavailable")
        require(files.size <= PROJECT_ENTRY_LIMIT + 1)
        for (file in files) {
            require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Unexpected project storage entry" }
            require(file.name == "catalog.json" || (file.name.endsWith(".pdf") && runCatching {
                requireProjectId(file.name.removeSuffix(".pdf"))
            }.isSuccess)) { "Unowned project storage entry" }
        }
        if (expected != null) require(files.map { it.name }.toSet() == expected)
        return files
    }
    private fun removeOwned(directory: File) {
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return
        val files = requireOwnedDirectory(directory)
        files.forEach { Files.delete(it.toPath()) }
        Files.delete(directory.toPath())
        forceProjectDirectory(base)
    }
    private fun write(intent: ProjectDownloadIntent) {
        val value = JsonObject().apply {
            addProperty("schema", 1)
            addProperty("project", ProjectLibraryCodec.encode(ProjectLibrary(projects = listOf(intent.project))))
            add("files", intent.files?.let { files -> JsonArray().apply { files.forEach { entry -> add(JsonObject().apply {
                addProperty("name", entry.name); addProperty("bytes", entry.bytes); addProperty("sha256", entry.sha256)
            }) } } } ?: JsonNull.INSTANCE)
        }.toString()
        require(value.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024)
        val file = marker(intent.project.id); val temporary = File(file.path + ".tmp")
        if (Files.exists(temporary.toPath(), NOFOLLOW_LINKS)) {
            // Only a valid durable receipt authorizes replacing its own stale
            // temporary file. Unlink it; never truncate a possible hard link.
            read(file, intent.project.id)
            require(Files.isRegularFile(temporary.toPath(), NOFOLLOW_LINKS))
            Files.delete(temporary.toPath())
        }
        FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(value.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
            channel.force(true)
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        forceProjectDirectory(base)
    }
    private fun read(file: File, id: String): ProjectDownloadIntent {
        val root = boundedJsonObject(readBounded(file), 4 * 1024 * 1024)
        root.requireKeys("schema", "project", "files"); require(root.integer("schema") == 1L)
        val record = root.get("project")
        require(record.isJsonPrimitive && record.asJsonPrimitive.isString)
        val library = ProjectLibraryCodec.decode(record.asString)
        require(library.driveRoots.isEmpty() && library.projects.size == 1)
        val project = library.projects.single()
        require(project.id == id && project.isDownload && project.recent.isEmpty())
        val entries = root.get("files")
        val files = if (entries.isJsonNull) null else root.array("files", PROJECT_ENTRY_LIMIT + 1).map { value ->
            val entry = value.asJsonObject; entry.requireKeys("name", "bytes", "sha256")
            val name = entry.text("name")
            require(name == "catalog.json" || (name.endsWith(".pdf") && runCatching { requireProjectId(name.removeSuffix(".pdf")) }.isSuccess))
            val bytes = entry.integer("bytes"); require(bytes in 1..if (name == "catalog.json") 4L * 1024 * 1024 else PROJECT_PDF_BYTE_LIMIT)
            val digest = entry.text("sha256"); require(Regex("[0-9a-f]{64}").matches(digest))
            ProjectDownloadFile(name, bytes, digest)
        }
        if (files != null) {
            require(files.size >= 2 && files.distinctBy { it.name }.size == files.size && files.any { it.name == "catalog.json" })
            require(files.sumOf { it.bytes } <= PROJECT_DOWNLOAD_BYTE_LIMIT + 4L * 1024 * 1024)
        }
        return ProjectDownloadIntent(project, files)
    }
    private fun readBounded(file: File): String {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..4L * 1024 * 1024)
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(count > 0 && output.size() + count <= 4 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    }
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(count > 0); total += count
                require(total <= PROJECT_PDF_BYTE_LIMIT)
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
