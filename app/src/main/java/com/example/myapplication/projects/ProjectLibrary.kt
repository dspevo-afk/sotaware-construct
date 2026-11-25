package com.example.myapplication.projects

import android.content.Context
import android.util.AtomicFile
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage9b.RecentDocumentRecord
import com.example.myapplication.stage5.validateNoDuplicateJsonMembers
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.File
import java.util.UUID

internal const val MAX_PROJECTS = 64
internal const val PROJECT_RECENT_LIMIT = 5
internal const val PROJECT_FIELD_LIMIT = 4096
private const val LIBRARY_BYTE_LIMIT = 4 * 1024 * 1024

internal data class ProjectRecent(
    val documentId: String,
    val sourceUri: String,
    val name: String,
    val folder: String,
    val openedAt: Long
)

/** Local trees retain their exact SAF grant; downloads retain account and Drive IDs. */
internal data class ProjectRecord(
    val id: String,
    val name: String,
    val source: String,
    val account: String = "",
    val recent: List<ProjectRecent> = emptyList()
) {
    val isDownload: Boolean get() = account.isNotEmpty()
}

/** The Drive id of a folder chosen as a source; the picker's own "My Drive" crumb is never saved. */
internal const val DRIVE_ROOT_FOLDER_ID = "root"

/** Chosen Drive sources are kept per account, newest choices last. */
internal const val DRIVE_ROOT_LIMIT = 8

/** Shown when a remembered Drive folder cannot be listed any more (deleted, access removed, other account). */
internal const val DRIVE_SAVED_FOLDER_UNAVAILABLE =
    "Your chosen Google Drive folder is not available any more. Start from My Drive and choose it again."

/** A saved source folder must name a real Drive folder, never the picker's own "My Drive" crumb. */
internal fun requireDriveFolderId(id: String) {
    require(id.isNotBlank() && id.length <= PROJECT_FIELD_LIMIT && id != DRIVE_ROOT_FOLDER_ID) {
        "Invalid Google Drive folder identity"
    }
}

/**
 * The Google Drive folder an account chose as its project source, or null when there is none.
 * [accounts] are the internal identities of the signed-in accounts, the current sign-in first, so a
 * tablet with several accounts opens the folder belonging to the one in use. With no account signed
 * in yet, the last saved choice is returned: it names the folder on the Projects screen and opens
 * there as soon as that account connects. A saved folder that cannot be reached any more is still
 * returned - the picker reports it and starts from My Drive.
 */
internal fun ProjectLibrary.chosenDriveRoot(accounts: List<String>): DriveProjectRoot? {
    val known = accounts.filter { it.isNotBlank() }
    if (known.isEmpty()) return driveRoots.lastOrNull()
    return known.firstNotNullOfOrNull { account -> driveRoots.firstOrNull { it.account == account } }
}

/**
 * The Google Drive folder an account chose as its project source. [account] is the stable internal
 * identity used to match projects; [driveName] is the sign-in address shown only so two accounts on
 * one tablet are told apart, and is empty in libraries saved before it existed.
 */
internal data class DriveProjectRoot(
    val account: String,
    val folderId: String,
    val name: String,
    val driveName: String = ""
)
internal data class ProjectLibrary(
    val projects: List<ProjectRecord> = emptyList(),
    val driveRoots: List<DriveProjectRoot> = emptyList()
)

internal interface ProjectLibraryStorage {
    fun read(): String?
    fun commit(value: String): Boolean
}

/** A failed/corrupt index is never overwritten with an empty project list. Call on IO. */
internal class ProjectLibraryStore(private val storage: ProjectLibraryStorage) {
    constructor(context: Context) : this(object : ProjectLibraryStorage {
        private val file = File(context.applicationContext.filesDir, "project-library-v1.json")
        private val atomic = AtomicFile(file)
        override fun read(): String? {
            val backup = File(file.path + ".bak")
            if (!file.exists() && !backup.exists()) return null
            require(file.length() <= LIBRARY_BYTE_LIMIT && backup.length() <= LIBRARY_BYTE_LIMIT)
            return atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        }
        override fun commit(value: String): Boolean {
            val stream = atomic.startWrite()
            return try {
                stream.write(value.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                atomic.finishWrite(stream)
                check(file.isFile && !File(file.path + ".new").exists() && file.readText() == value) {
                    "Project library publication did not complete"
                }
                forceProjectDirectory(requireNotNull(file.parentFile))
                true
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
    })

    fun read(): ProjectLibrary = synchronized(lock) {
        storage.read()?.let(ProjectLibraryCodec::decode) ?: ProjectLibrary()
    }

    fun add(project: ProjectRecord): ProjectRecord = synchronized(lock) {
        val current = read()
        current.projects.firstOrNull { it.source == project.source && it.account == project.account }?.let { return it }
        require(current.projects.size < MAX_PROJECTS) { "Project limit reached" }
        write(current.copy(projects = current.projects + project))
        project
    }

    /**
     * Remembers [root] as this account's chosen Drive source, replacing any folder it chose
     * before, and returns the root that is now stored so callers show exactly what was saved.
     */
    fun selectDriveRoot(root: DriveProjectRoot): DriveProjectRoot = synchronized(lock) {
        require(root.account.isNotBlank() && root.name.isNotBlank()) { "Google Drive source is incomplete" }
        requireDriveFolderId(root.folderId)
        val current = read()
        val updated = (current.driveRoots.filterNot { it.account == root.account } + root)
            .takeLast(DRIVE_ROOT_LIMIT)
        write(current.copy(driveRoots = updated))
        updated.last()
    }

    fun recordOpen(projectId: String, opened: RecentDocumentRecord, folder: String) = synchronized(lock) {
        val current = read()
        require(current.projects.any { it.id == projectId }) { "Project no longer exists" }
        val recent = ProjectRecent(opened.documentId.value, opened.sourceUri, opened.displayName ?: "Drawing", folder,
            opened.lastSuccessfullyOpenedAtEpochMillis)
        write(current.copy(projects = current.projects.map { project ->
            if (project.id != projectId) project else project.copy(recent =
                (project.recent.filterNot { it.documentId == recent.documentId && it.sourceUri == recent.sourceUri } + recent)
                    .sortedByDescending { it.openedAt }.take(PROJECT_RECENT_LIMIT))
        }))
    }

    private fun write(value: ProjectLibrary) {
        if (!storage.commit(ProjectLibraryCodec.encode(value))) throw IOException("Project library could not be saved")
    }

    private companion object { val lock = Any() }
}

internal object ProjectLibraryCodec {
    fun encode(library: ProjectLibrary): String {
        val root = JsonObject().apply {
            addProperty("schema", 1)
            add("projects", JsonArray().apply { library.projects.forEach { project -> add(JsonObject().apply {
                addProperty("id", project.id); addProperty("name", project.name)
                addProperty("source", project.source); addProperty("account", project.account)
                add("recent", JsonArray().apply { project.recent.forEach { file -> add(JsonObject().apply {
                    addProperty("documentId", file.documentId); addProperty("sourceUri", file.sourceUri)
                    addProperty("name", file.name); addProperty("folder", file.folder); addProperty("openedAt", file.openedAt)
                }) } })
            }) } })
            add("driveRoots", JsonArray().apply { library.driveRoots.forEach { location -> add(JsonObject().apply {
                addProperty("account", location.account); addProperty("folderId", location.folderId)
                addProperty("name", location.name); addProperty("driveName", location.driveName)
            }) } })
        }.toString()
        decode(root) // Apply exactly the same bounds to writes and restored input.
        return root
    }

    fun decode(value: String): ProjectLibrary {
        val root = boundedJsonObject(value, LIBRARY_BYTE_LIMIT)
        root.requireKeys("schema", "projects", "driveRoots")
        require(root.integer("schema") == 1L) { "Unsupported project library" }
        val projects = root.array("projects", MAX_PROJECTS).map { element ->
            val p = element.asJsonObject
            p.requireKeys("id", "name", "source", "account", "recent")
            val recent = p.array("recent", PROJECT_RECENT_LIMIT).map { item ->
                val f = item.asJsonObject
                f.requireKeys("documentId", "sourceUri", "name", "folder", "openedAt")
                ProjectRecent(DocumentId.parse(f.text("documentId")).value, f.text("sourceUri"),
                    f.text("name"), f.text("folder"), f.integer("openedAt").also { require(it >= 0) })
            }
            require(recent.distinctBy { it.documentId to it.sourceUri }.size == recent.size)
            ProjectRecord(p.text("id").also(::requireProjectId), p.text("name"), p.text("source"), p.text("account", true), recent)
        }
        require(projects.distinctBy { it.id }.size == projects.size)
        require(projects.distinctBy { it.account to it.source }.size == projects.size)
        val roots = root.array("driveRoots", 8).map { element ->
            val r = element.asJsonObject
            // [driveName] is optional: a library saved before the sign-in address was kept
            // restores with no label, and a label is only ever display text.
            require(r.keySet().all { it in setOf("account", "folderId", "name", "driveName") })
            DriveProjectRoot(
                account = r.text("account"),
                folderId = r.text("folderId").also(::requireDriveFolderId),
                name = r.text("name"),
                driveName = r.optionalText("driveName")
            )
        }
        require(roots.distinctBy { it.account }.size == roots.size)
        return ProjectLibrary(projects, roots)
    }
}

internal fun requireProjectId(id: String) {
    require(UUID.fromString(id).toString() == id) { "Invalid project identity" }
}

internal fun boundedJsonObject(value: String, byteLimit: Int): JsonObject {
    require(value.length <= byteLimit)
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size in 1..byteLimit)
    validateNoDuplicateJsonMembers(bytes, "project index")
    return JsonParser.parseString(value).asJsonObject
}

internal fun JsonObject.requireKeys(vararg keys: String) { require(keySet() == keys.toSet()) }

/** Optional display text: absent means empty, present must stay a bounded string. */
internal fun JsonObject.optionalText(key: String): String {
    val element = get(key) ?: return ""
    require(element.isJsonPrimitive && element.asJsonPrimitive.isString)
    return element.asString.also { require(it.length <= PROJECT_FIELD_LIMIT) }
}
internal fun JsonObject.text(key: String, allowEmpty: Boolean = false): String {
    val element = get(key)
    require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString)
    return element.asString.also { require(it.length <= PROJECT_FIELD_LIMIT && (allowEmpty || it.isNotBlank())) }
}
internal fun JsonObject.integer(key: String): Long {
    val element = get(key)
    require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
    require(Regex("0|[1-9][0-9]*").matches(element.asString))
    return element.asString.toLong()
}
internal fun JsonObject.array(key: String, limit: Int): JsonArray = getAsJsonArray(key).also { require(it.size() <= limit) }
