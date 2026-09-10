package com.example.myapplication.stage4

import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Explicit JVM-only replacement for the native outbox directory capability.
 *
 * This adapter is a controlled temporary-directory fixture. It validates
 * containment, direct-child names, and no-follow attributes, but its
 * path-based operations do not provide native race-safe descriptor semantics.
 * It must never be selected by a production constructor.
 */
internal object TestOutboxDirectoryStreamFactory : OutboxDirectoryStreamFactory {
    override fun open(path: Path, trustedRoot: Path): OutboxDirectoryStream =
        TestOutboxDirectoryStream.open(path, trustedRoot)
}

internal class TestOutboxDirectoryStream private constructor(
    private val directory: Path,
    private val trustedRoot: Path,
    private val entries: DirectoryStream<Path>
) : OutboxDirectoryStream {
    private var closed = false

    companion object {
        fun open(path: Path, trustedRoot: Path): TestOutboxDirectoryStream {
            val boundary = trustedRoot.toAbsolutePath().normalize()
            val absolute = path.toAbsolutePath().normalize()
            requireContained(absolute, boundary)
            requireDirectory(absolute)
            val stream = try {
                Files.newDirectoryStream(absolute)
            } catch (error: Exception) {
                throw IOException("test outbox directory could not be opened", error)
            }
            return TestOutboxDirectoryStream(absolute, boundary, stream)
        }

        private fun requireContained(path: Path, boundary: Path) {
            if (!path.startsWith(boundary)) {
                throw IOException("test outbox directory escaped its trusted root")
            }
            ensureNoSymlinkAncestors(path, boundary)
        }

        private fun requireDirectory(path: Path) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("test outbox path is not a directory")
            }
        }

        private fun ensureNoSymlinkAncestors(path: Path, boundary: Path) {
            var current: Path? = path
            while (current != null && current.startsWith(boundary)) {
                if (Files.isSymbolicLink(current)) {
                    throw IOException("test outbox path contains a symbolic link")
                }
                if (current == boundary) return
                current = current.parent
            }
            throw IOException("test outbox path escaped its trusted root")
        }
    }

    private fun requireOpen() {
        if (closed) throw IOException("test outbox directory stream is closed")
    }

    private fun child(name: String): Path {
        requireOpen()
        if (name.isEmpty() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0
        ) {
            throw IOException("test outbox operation requires one relative child name")
        }
        val candidate = directory.resolve(name).toAbsolutePath().normalize()
        if (candidate.parent != directory || !candidate.startsWith(directory)) {
            throw IOException("test outbox operation escaped its directory")
        }
        // Leave the candidate itself available to the NOFOLLOW attribute
        // read; callers must observe and reject a symlink rather than follow
        // it. Only its already-established ancestors are checked here.
        candidate.parent?.let { Companion.ensureNoSymlinkAncestors(it, trustedRoot) }
        return candidate
    }

    private fun attributes(name: String): BasicFileAttributes =
        try {
            Files.readAttributes(child(name), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: NoSuchFileException) {
            throw IOException("test outbox entry is missing: $name", error)
        }

    override fun listNames(maxEntries: Int): List<String> {
        requireOpen()
        require(maxEntries > 0) { "test outbox directory entry bound must be positive" }
        val names = ArrayList<String>()
        for (entry in entries) {
            if (names.size >= maxEntries) {
                throw IOException("test outbox directory has too many entries")
            }
            val name = entry.fileName?.toString()
                ?: throw IOException("test outbox directory entry has no name")
            // DirectoryStream must enumerate direct children. Recheck the
            // same single-name contract before the cleanup phase records it.
            child(name)
            names += name
        }
        return names
    }

    override fun readAttributes(name: String): BasicFileAttributes = attributes(name)

    override fun openDirectory(name: String): OutboxDirectoryStream {
        val target = child(name)
        val targetAttributes = attributes(name)
        if (targetAttributes.isSymbolicLink || !targetAttributes.isDirectory) {
            throw IOException("test outbox entry is not a directory: $name")
        }
        return open(target, trustedRoot)
    }

    override fun deleteFile(name: String) {
        val target = child(name)
        val targetAttributes = attributes(name)
        if (targetAttributes.isSymbolicLink || !targetAttributes.isRegularFile) {
            throw IOException("test outbox entry is not a regular file: $name")
        }
        Files.delete(target)
    }

    override fun deleteDirectory(name: String) {
        val target = child(name)
        val targetAttributes = attributes(name)
        if (targetAttributes.isSymbolicLink || !targetAttributes.isDirectory) {
            throw IOException("test outbox entry is not a directory: $name")
        }
        Files.delete(target)
    }

    override fun close() {
        if (!closed) {
            closed = true
            entries.close()
        }
    }
}
