package com.example.myapplication.stage5

import java.io.InputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM-only filesystem seam. Production always selects the
 * SecureDirectoryStream factory; this implementation exists so Windows/JVM
 * tests do not need to pretend that a provider without descriptor-relative
 * operations is safe.
 */
internal object TestPhotoPathOperationsFactory : PhotoPathOperationsFactory {
    override fun open(root: Path): PhotoPathOperations = TestPhotoPathOperations(root)
}

/** Fails the durable photo commit marker after canonical apply has reported success. */
internal class FailOnPhotoCommitMarkerFactory : PhotoPathOperationsFactory {
    override fun open(root: Path): PhotoPathOperations {
        val delegate = TestPhotoPathOperations(root)
        return object : PhotoPathOperations {
            override fun exists(name: String): Boolean = delegate.exists(name)
            override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
            override fun size(name: String): Long = delegate.size(name)
            override fun openRead(name: String): InputStream = delegate.openRead(name)
            override fun openNewOutput(name: String): FileChannel {
                if (name == ".stage5-photo-transaction.commit") {
                    throw java.io.IOException("injected photo commit marker failure")
                }
                return delegate.openNewOutput(name)
            }
            override fun move(source: String, target: String, replaceExisting: Boolean) =
                delegate.move(source, target, replaceExisting)
            override fun delete(name: String) = delegate.delete(name)
            override fun close() = delegate.close()
        }
    }
}

/** Writes the commit marker, then makes its authoritative readback ambiguous once. */
internal class FailOnPhotoCommitMarkerReadbackFactory : PhotoPathOperationsFactory {
    override fun open(root: Path): PhotoPathOperations {
        val delegate = TestPhotoPathOperations(root)
        var failRead = true
        return object : PhotoPathOperations {
            override fun exists(name: String): Boolean = delegate.exists(name)
            override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
            override fun size(name: String): Long = delegate.size(name)
            override fun openRead(name: String): InputStream {
                if (name == ".stage5-photo-transaction.commit" && failRead) {
                    failRead = false
                    throw IOException("injected authoritative commit-marker readback failure")
                }
                return delegate.openRead(name)
            }
            override fun openNewOutput(name: String): FileChannel = delegate.openNewOutput(name)
            override fun move(source: String, target: String, replaceExisting: Boolean) =
                delegate.move(source, target, replaceExisting)
            override fun delete(name: String) = delegate.delete(name)
            override fun close() = delegate.close()
        }
    }
}

/** Makes the authoritative commit-marker read remain ambiguous for the owning resolver. */
internal class AlwaysFailOnPhotoCommitMarkerReadFactory : PhotoPathOperationsFactory {
    var opened: Int = 0
        private set
    var closed: Int = 0
        private set
    var usedAfterClose: Int = 0
        private set

    override fun open(root: Path): PhotoPathOperations {
        opened++
        val delegate = TestPhotoPathOperations(root)
        var isClosed = false

        fun requireOpen() {
            if (isClosed) {
                usedAfterClose++
                throw IOException("photo operations were used after close")
            }
        }

        return object : PhotoPathOperations {
            override fun exists(name: String): Boolean {
                requireOpen()
                return delegate.exists(name)
            }
            override fun isRegularFile(name: String): Boolean {
                requireOpen()
                return delegate.isRegularFile(name)
            }
            override fun size(name: String): Long {
                requireOpen()
                return delegate.size(name)
            }
            override fun openRead(name: String): InputStream {
                requireOpen()
                if (name == ".stage5-photo-transaction.commit") {
                    throw IOException("injected authoritative commit-marker read failure")
                }
                return delegate.openRead(name)
            }
            override fun openNewOutput(name: String): FileChannel {
                requireOpen()
                return delegate.openNewOutput(name)
            }
            override fun move(source: String, target: String, replaceExisting: Boolean) {
                requireOpen()
                delegate.move(source, target, replaceExisting)
            }
            override fun delete(name: String) {
                requireOpen()
                delegate.delete(name)
            }
            override fun close() {
                if (!isClosed) {
                    isClosed = true
                    closed++
                    delegate.close()
                }
            }
        }
    }
}

/** A real close-enforcing provider used to prove rollback retains its resolver. */
internal class CloseEnforcingPhotoPathOperationsFactory(
    private val failCommitMarker: Boolean = false
) : PhotoPathOperationsFactory {
    var opened: Int = 0
        private set
    var closed: Int = 0
        private set
    var usedAfterClose: Int = 0
        private set

    override fun open(root: Path): PhotoPathOperations {
        opened++
        val delegate = TestPhotoPathOperations(root)
        var isClosed = false

        fun requireOpen() {
            if (isClosed) {
                usedAfterClose++
                throw IOException("photo operations were used after close")
            }
        }

        return object : PhotoPathOperations {
            override fun exists(name: String): Boolean {
                requireOpen()
                return delegate.exists(name)
            }

            override fun isRegularFile(name: String): Boolean {
                requireOpen()
                return delegate.isRegularFile(name)
            }

            override fun size(name: String): Long {
                requireOpen()
                return delegate.size(name)
            }

            override fun openRead(name: String): InputStream {
                requireOpen()
                return delegate.openRead(name)
            }

            override fun openNewOutput(name: String): FileChannel {
                requireOpen()
                if (failCommitMarker && name == ".stage5-photo-transaction.commit") {
                    throw IOException("injected photo commit marker failure")
                }
                return delegate.openNewOutput(name)
            }

            override fun move(source: String, target: String, replaceExisting: Boolean) {
                requireOpen()
                delegate.move(source, target, replaceExisting)
            }

            override fun delete(name: String) {
                requireOpen()
                delegate.delete(name)
            }

            override fun close() {
                if (!isClosed) {
                    isClosed = true
                    closed++
                    delegate.close()
                }
            }
        }
    }
}

/** Fails one marker deletion once and rejects every operation after close. */
internal class FailOnPhotoMarkerDeleteFactory(
    private val markerName: String
) : PhotoPathOperationsFactory {
    var opened: Int = 0
        private set
    var closed: Int = 0
        private set
    var usedAfterClose: Int = 0
        private set

    override fun open(root: Path): PhotoPathOperations {
        opened++
        val delegate = TestPhotoPathOperations(root)
        var failed = false

        fun requireOpen(isClosed: Boolean) {
            if (isClosed) {
                usedAfterClose++
                throw IOException("photo operations were used after close")
            }
        }

        var isClosed = false
        return object : PhotoPathOperations {
            override fun exists(name: String): Boolean {
                requireOpen(isClosed)
                return delegate.exists(name)
            }

            override fun isRegularFile(name: String): Boolean {
                requireOpen(isClosed)
                return delegate.isRegularFile(name)
            }

            override fun size(name: String): Long {
                requireOpen(isClosed)
                return delegate.size(name)
            }

            override fun openRead(name: String): InputStream {
                requireOpen(isClosed)
                return delegate.openRead(name)
            }

            override fun openNewOutput(name: String): FileChannel {
                requireOpen(isClosed)
                return delegate.openNewOutput(name)
            }

            override fun move(source: String, target: String, replaceExisting: Boolean) {
                requireOpen(isClosed)
                delegate.move(source, target, replaceExisting)
            }

            override fun delete(name: String) {
                requireOpen(isClosed)
                if (!failed && name == markerName) {
                    failed = true
                    throw IOException("injected marker deletion failure: $name")
                }
                delegate.delete(name)
            }

            override fun close() {
                if (!isClosed) {
                    isClosed = true
                    closed++
                    delegate.close()
                }
            }
        }
    }
}

/** Fails creation of one durable marker once, after prior evidence is intact. */
internal class FailOnPhotoMarkerCreateFactory(
    private val markerName: String
) : PhotoPathOperationsFactory {
    override fun open(root: Path): PhotoPathOperations {
        val delegate = TestPhotoPathOperations(root)
        var failed = false
        return object : PhotoPathOperations {
            override fun exists(name: String): Boolean = delegate.exists(name)
            override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
            override fun size(name: String): Long = delegate.size(name)
            override fun openRead(name: String): InputStream = delegate.openRead(name)
            override fun openNewOutput(name: String): FileChannel {
                if (!failed && name == markerName) {
                    failed = true
                    throw IOException("injected marker creation failure: $name")
                }
                return delegate.openNewOutput(name)
            }
            override fun move(source: String, target: String, replaceExisting: Boolean) =
                delegate.move(source, target, replaceExisting)
            override fun delete(name: String) = delegate.delete(name)
            override fun close() = delegate.close()
        }
    }
}

internal class TestPhotoPathOperations(private val root: Path) : PhotoPathOperations {
    private fun path(name: String): Path = root.resolve(name)

    override fun exists(name: String): Boolean = Files.exists(path(name), LinkOption.NOFOLLOW_LINKS)

    override fun isRegularFile(name: String): Boolean = Files.isRegularFile(path(name), LinkOption.NOFOLLOW_LINKS)

    override fun size(name: String): Long = Files.size(path(name))

    override fun openRead(name: String): InputStream = Files.newInputStream(path(name), LinkOption.NOFOLLOW_LINKS)

    override fun openNewOutput(name: String): FileChannel = FileChannel.open(
        path(name),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS
    )

    override fun move(source: String, target: String, replaceExisting: Boolean) {
        if (replaceExisting) throw Stage5ValidationException("test photo moves do not allow replacement")
        if (Files.exists(path(target), LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target)
        Files.move(path(source), path(target), StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete(name: String) {
        val target = path(name)
        if (Files.isSymbolicLink(target)) throw Stage5ValidationException("test photo target is a symbolic link")
        Files.deleteIfExists(target)
    }

    override fun close() = Unit
}

/** Deterministically models a parent replacement being detected by the secure seam. */
internal class ParentReplacementFailClosedFactory : PhotoPathOperationsFactory {
    var parentWasReplaced = false

    override fun open(root: Path): PhotoPathOperations = object : PhotoPathOperations {
        private fun rejectIfReplaced() {
            if (parentWasReplaced) {
                throw Stage5ValidationException("secure photo operation rejected after parent replacement")
            }
        }

        override fun exists(name: String): Boolean {
            rejectIfReplaced()
            return false
        }

        override fun isRegularFile(name: String): Boolean {
            rejectIfReplaced()
            return false
        }

        override fun size(name: String): Long {
            rejectIfReplaced()
            throw java.io.FileNotFoundException(name)
        }

        override fun openRead(name: String): InputStream {
            rejectIfReplaced()
            throw java.io.FileNotFoundException(name)
        }

        override fun openNewOutput(name: String): FileChannel {
            rejectIfReplaced()
            throw java.io.IOException("injected test operation is not a writable filesystem")
        }

        override fun move(source: String, target: String, replaceExisting: Boolean) {
            rejectIfReplaced()
            throw java.io.IOException("injected test operation is not a movable filesystem")
        }

        override fun delete(name: String) {
            rejectIfReplaced()
            throw java.io.IOException("injected test operation is not a deletable filesystem")
        }

        override fun close() = Unit
    }
}

/** Creates the current staging file and then fails, exercising pre-Entry cleanup. */
internal class CreateThenFailPhotoPathOperationsFactory : PhotoPathOperationsFactory {
    override fun open(root: Path): PhotoPathOperations = object : PhotoPathOperations {
        private val delegate = TestPhotoPathOperations(root)

        override fun exists(name: String): Boolean = delegate.exists(name)
        override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
        override fun size(name: String): Long = delegate.size(name)
        override fun openRead(name: String): InputStream = delegate.openRead(name)
        override fun openNewOutput(name: String): FileChannel {
            Files.createFile(root.resolve(name))
            throw java.io.IOException("injected staging write failure")
        }
        override fun move(source: String, target: String, replaceExisting: Boolean) =
            delegate.move(source, target, replaceExisting)
        override fun delete(name: String) = delegate.delete(name)
        override fun close() = delegate.close()
    }
}

/** Test-only source stat injection used to exercise aggregate preflight cheaply. */
internal class ReportedSizePhotoPathOperationsFactory(
    private val reportedSize: Long
) : PhotoPathOperationsFactory {
    override fun open(root: Path): PhotoPathOperations = object : PhotoPathOperations {
        private val delegate = TestPhotoPathOperations(root)

        override fun exists(name: String): Boolean = delegate.exists(name)
        override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
        override fun size(name: String): Long = reportedSize
        override fun openRead(name: String): InputStream = delegate.openRead(name)
        override fun openNewOutput(name: String): FileChannel = delegate.openNewOutput(name)
        override fun move(source: String, target: String, replaceExisting: Boolean) =
            delegate.move(source, target, replaceExisting)
        override fun delete(name: String) = delegate.delete(name)
        override fun close() = delegate.close()
    }
}

/** Fails one deterministic atomic move while leaving later operations usable. */
internal class FailOnPhotoMoveFactory(
    private val failOnMove: Int
) : PhotoPathOperationsFactory {
    private val moveCount = AtomicInteger()

    override fun open(root: Path): PhotoPathOperations {
        val delegate = TestPhotoPathOperations(root)
        return object : PhotoPathOperations {
            override fun exists(name: String): Boolean = delegate.exists(name)
            override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
            override fun size(name: String): Long = delegate.size(name)
            override fun openRead(name: String): InputStream = delegate.openRead(name)
            override fun openNewOutput(name: String): FileChannel = delegate.openNewOutput(name)
            override fun move(source: String, target: String, replaceExisting: Boolean) {
                if (moveCount.incrementAndGet() == failOnMove) {
                    throw java.io.IOException("injected photo move failure")
                }
                delegate.move(source, target, replaceExisting)
            }
            override fun delete(name: String) = delegate.delete(name)
            override fun close() = delegate.close()
        }
    }
}

/** Counts resolver lifetimes so failure paths cannot leak a legacy descriptor. */
internal class CountingPhotoPathOperationsFactory : PhotoPathOperationsFactory {
    var opened: Int = 0
        private set
    var closed: Int = 0
        private set

    override fun open(root: Path): PhotoPathOperations {
        opened++
        val delegate = TestPhotoPathOperations(root)
        return object : PhotoPathOperations {
            override fun exists(name: String): Boolean = delegate.exists(name)
            override fun isRegularFile(name: String): Boolean = delegate.isRegularFile(name)
            override fun size(name: String): Long = delegate.size(name)
            override fun openRead(name: String): InputStream = delegate.openRead(name)
            override fun openNewOutput(name: String): FileChannel = delegate.openNewOutput(name)
            override fun move(source: String, target: String, replaceExisting: Boolean) =
                delegate.move(source, target, replaceExisting)
            override fun delete(name: String) = delegate.delete(name)
            override fun close() {
                closed++
                delegate.close()
            }
        }
    }
}
