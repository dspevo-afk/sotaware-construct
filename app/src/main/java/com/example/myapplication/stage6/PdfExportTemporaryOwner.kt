package com.example.myapplication.stage6

import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The only directory used for PDF source and result staging. */
internal const val PDF_EXPORT_TEMPORARY_DIRECTORY = "construct-export-temporaries"
private const val DEFAULT_PDF_EXPORT_TEMPORARY_AGE_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * Export temporary files are deliberately separate from the general cache.
 * File names carry their request identity, while last-modified time provides a
 * conservative lease across process death. No PDF byte-size policy is imposed
 * here; the source fingerprint and renderer remain the content authorities.
 */
internal enum class PdfExportTemporaryKind(val label: String) {
    SOURCE("source"),
    RESULT("result")
}

internal data class PdfExportTemporaryReconciliation(
    val removedFileNames: Set<String>,
    val retainedFileNames: Set<String>
)

/**
 * Dedicated owner for verified source copies and picker-waiting result PDFs.
 * Unknown files in the directory are left untouched so this owner cannot
 * become a blanket cache cleaner.
 */
internal class PdfExportTemporaryOwner(
    cacheDirectory: File,
    private val abandonedAfterMillis: Long = DEFAULT_PDF_EXPORT_TEMPORARY_AGE_MILLIS
) {
    private val directory = File(cacheDirectory, PDF_EXPORT_TEMPORARY_DIRECTORY)
    private val shared = sharedStateFor(directory)

    init {
        require(abandonedAfterMillis > 0L) {
            "PDF export temporary lease must be positive"
        }
    }

    /** The directory is exposed for narrow integration and fixture checks. */
    internal val directoryForTests: File
        get() = directory

    fun createSourceFile(requestId: String = UUID.randomUUID().toString()): File =
        create(PdfExportTemporaryKind.SOURCE, requestId)

    fun createResultFile(requestId: String): File =
        create(PdfExportTemporaryKind.RESULT, requestId)

    /**
     * Reconciles only owned source/result names. Fresh files and explicitly
     * active request IDs survive a restart; stale abandoned files are removed.
     */
    fun reconcile(
        nowMillis: Long = System.currentTimeMillis(),
        activeRequestIds: Set<String> = emptySet()
    ): PdfExportTemporaryReconciliation = synchronized(shared.lock) {
        require(nowMillis >= 0L) { "PDF export reconciliation time must be non-negative" }
        pruneMissingActiveFilesLocked()
        val files = listOwnedDirectoryFilesLocked()
        val removed = linkedSetOf<String>()
        val retained = linkedSetOf<String>()
        val active = activeRequestIds + shared.activeRequestIds.keys
        files.forEach { file ->
            val identity = parseOwnedName(file.name)
            if (identity.requestId in active || !isExpired(file, nowMillis)) {
                retained += file.name
                return@forEach
            }
            ensureOwnedPathLocked(file)
            if (!file.delete()) {
                throw IOException("PDF export temporary could not be reclaimed: ${file.name}")
            }
            removed += file.name
        }
        PdfExportTemporaryReconciliation(removed, retained)
    }

    /** Removes one path after verifying that it belongs to this owner. */
    fun release(file: File) = synchronized(shared.lock) {
        ensureOwnedPathLocked(file)
        if (file.exists() && !file.delete()) {
            throw IOException("PDF export temporary could not be removed: ${file.name}")
        }
        unregisterCreatedFileLocked(file)
        if (isDirectoryEmptyLocked()) {
            directory.delete()
        }
    }

    /** Refreshes the lease for a file that is still actively being used. */
    fun touch(file: File, nowMillis: Long = System.currentTimeMillis()) = synchronized(shared.lock) {
        require(nowMillis >= 0L) { "PDF export lease time must be non-negative" }
        ensureOwnedPathLocked(file)
        if (!file.exists() || !file.setLastModified(nowMillis)) {
            throw IOException("PDF export temporary lease could not be refreshed: ${file.name}")
        }
    }

    internal fun isOwnedResultFile(requestId: String, file: File): Boolean = synchronized(shared.lock) {
        parseOwnedNameOrNull(file.name)?.let { identity ->
            identity.kind == PdfExportTemporaryKind.RESULT &&
                identity.requestId == requestId &&
                samePath(file, directory) &&
                !Files.isSymbolicLink(file.toPath()) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        } == true
    }

    private fun create(kind: PdfExportTemporaryKind, requestId: String): File = synchronized(shared.lock) {
        requireExportRequestId(requestId)
        val now = System.currentTimeMillis()
        // A fresh request ID is an active lease while its file is being built.
        reconcile(nowMillis = now, activeRequestIds = setOf(requestId))
        ensureDirectoryLocked()
        val ownedCount = listOwnedDirectoryFilesLocked().size
        if (ownedCount >= MAX_PDF_EXPORT_TEMPORARY_FILES) {
            throw IOException("PDF export temporary directory is at its bounded file limit")
        }
        val file = directory.toPath().resolve(
            "construct-export-${kind.label}-$requestId-${UUID.randomUUID()}.pdf"
        ).toFile()
        var created = false
        try {
            Files.createFile(file.toPath())
            created = true
            ensureOwnedPathLocked(file)
            registerCreatedFileLocked(file, requestId)
        } catch (error: IOException) {
            if (created) runCatching { Files.deleteIfExists(file.toPath()) }
            throw IOException("PDF export temporary could not be created", error)
        } catch (error: Throwable) {
            if (created) runCatching { Files.deleteIfExists(file.toPath()) }
            throw error
        }
        file
    }

    private fun listOwnedDirectoryFilesLocked(): List<File> {
        ensureDirectoryLocked()
        val owned = ArrayList<File>()
        var entries = 0
        try {
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (path in stream) {
                    entries++
                    if (entries > MAX_PDF_EXPORT_DIRECTORY_ENTRIES) {
                        throw IOException("PDF export temporary directory exceeds its bounded entry limit")
                    }
                    val file = path.toFile()
                    if (parseOwnedNameOrNull(file.name) == null) continue
                    if (Files.isSymbolicLink(path) ||
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        throw IOException("PDF export temporary is not a regular file: ${file.name}")
                    }
                    ensureOwnedPathLocked(file)
                    owned += file
                    if (owned.size > MAX_PDF_EXPORT_TEMPORARY_FILES) {
                        throw IOException("PDF export temporary tracking exceeds its bounded file limit")
                    }
                }
            }
        } catch (error: IOException) {
            throw error
        } catch (error: SecurityException) {
            throw IOException("PDF export temporary directory could not be listed", error)
        } catch (error: DirectoryIteratorException) {
            throw IOException("PDF export temporary directory could not be listed", error.cause)
        } catch (error: UncheckedIOException) {
            throw IOException("PDF export temporary directory could not be listed", error)
        }
        return owned
    }

    private fun isDirectoryEmptyLocked(): Boolean {
        ensureDirectoryLocked()
        try {
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                return !stream.iterator().hasNext()
            }
        } catch (error: IOException) {
            throw IOException("PDF export temporary directory could not be listed", error)
        } catch (error: SecurityException) {
            throw IOException("PDF export temporary directory could not be listed", error)
        } catch (error: DirectoryIteratorException) {
            throw IOException("PDF export temporary directory could not be listed", error.cause)
        } catch (error: UncheckedIOException) {
            throw IOException("PDF export temporary directory could not be listed", error)
        }
    }

    private fun ensureDirectoryLocked() {
        if (Files.isSymbolicLink(directory.toPath())) {
            throw IOException("PDF export temporary path cannot be a symbolic link")
        }
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("PDF export temporary path is not a directory")
            }
            return
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("PDF export temporary directory could not be created")
        }
    }

    private fun ensureOwnedPathLocked(file: File) {
        parseOwnedNameOrNull(file.name)
            ?: throw IOException("unowned PDF export temporary: ${file.name}")
        if (!samePath(file, directory)) {
            throw IOException("PDF export temporary is outside its owner")
        }
        if (Files.isSymbolicLink(file.toPath()) ||
            (file.exists() && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        ) {
            throw IOException("PDF export temporary is not a regular file: ${file.name}")
        }
    }

    private fun registerCreatedFileLocked(file: File, requestId: String) {
        val key = file.canonicalPath
        check(shared.activeFileRequests[key] == null) {
            "PDF export temporary path is already tracked"
        }
        shared.activeFileRequests[key] = requestId
        shared.activeRequestIds[requestId] =
            (shared.activeRequestIds[requestId] ?: 0) + 1
    }

    private fun unregisterCreatedFileLocked(file: File) {
        val key = runCatching { file.canonicalPath }.getOrNull() ?: return
        val requestId = shared.activeFileRequests.remove(key) ?: return
        decrementActiveRequestLocked(requestId)
    }

    private fun pruneMissingActiveFilesLocked() {
        val missing = shared.activeFileRequests.keys.filter { key ->
            !Files.exists(File(key).toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        missing.forEach { key ->
            val requestId = shared.activeFileRequests.remove(key) ?: return@forEach
            decrementActiveRequestLocked(requestId)
        }
    }

    private fun decrementActiveRequestLocked(requestId: String) {
        val count = shared.activeRequestIds[requestId] ?: return
        if (count <= 1) shared.activeRequestIds.remove(requestId)
        else shared.activeRequestIds[requestId] = count - 1
    }

    private fun samePath(file: File, parent: File): Boolean =
        runCatching { file.canonicalFile.parentFile == parent.canonicalFile }.getOrDefault(false)

    private fun isExpired(file: File, nowMillis: Long): Boolean {
        val modified = file.lastModified()
        if (modified <= 0L || modified > nowMillis) return false
        return nowMillis - modified >= abandonedAfterMillis
    }

    private data class OwnedIdentity(
        val kind: PdfExportTemporaryKind,
        val requestId: String
    )

    private fun parseOwnedName(name: String): OwnedIdentity =
        parseOwnedNameOrNull(name)
            ?: throw IOException("invalid PDF export temporary name: $name")

    private fun parseOwnedNameOrNull(name: String): OwnedIdentity? {
        val match = OWNED_FILE_PATTERN.matchEntire(name) ?: return null
        val kind = PdfExportTemporaryKind.values().firstOrNull { it.label == match.groupValues[1] }
            ?: return null
        return OwnedIdentity(kind, match.groupValues[2])
    }

    companion object {
        const val MAX_PDF_EXPORT_TEMPORARY_FILES: Int = 64
        private const val MAX_PDF_EXPORT_DIRECTORY_ENTRIES: Int = 128
        private const val UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val OWNED_FILE_PATTERN = Regex(
            "construct-export-(source|result)-($UUID_TEXT)-$UUID_TEXT\\.pdf"
        )

        private class SharedDirectoryState {
            val lock = Any()
            val activeRequestIds = mutableMapOf<String, Int>()
            val activeFileRequests = mutableMapOf<String, String>()
        }

        private val sharedDirectories = ConcurrentHashMap<String, SharedDirectoryState>()

        private fun sharedStateFor(directory: File): SharedDirectoryState =
            sharedDirectories.computeIfAbsent(directory.canonicalPath) { SharedDirectoryState() }
    }
}

private fun requireExportRequestId(value: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
        "PDF export request id must be a canonical UUID"
    }
}
