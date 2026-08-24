package com.example.myapplication.stage4

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Prepared photo bytes are not visible at their final names until the
 * canonical snapshot has passed the Stage 3 durable/apply boundary.  A
 * transaction keeps the previous files in same-directory backups so a later
 * move, repository save, cancellation, or in-memory apply failure can restore
 * the complete old set.
 */
interface PhotoContentTransaction {
    suspend fun publish()
    suspend fun commit()
    suspend fun rollback()
}

/** A rollback could not restore every pre-transaction byte; callers must fail closed. */
class PhotoRollbackException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

data class PhotoContentPreparation(
    val result: com.example.myapplication.stage2.DocumentSaveResult,
    val transaction: PhotoContentTransaction? = null
)

class StagedPhotoContentTransaction private constructor(
    private val root: File,
    private val entries: MutableList<Entry>,
    private val move: (Path, Path) -> Unit
) : PhotoContentTransaction {
    private data class Entry(
        val staged: File,
        val target: File,
        val backup: File,
        val targetExisted: Boolean,
        var backupMoved: Boolean = false,
        var published: Boolean = false
    )

    @Volatile
    private var committed = false

    @Volatile
    private var rolledBack = false

    /**
     * A failed rollback is deliberately sticky and observable.  The
     * coordinator may perform a compensating rollback after publish() fails;
     * returning normally here would turn a partially restored photo set into
     * an ordinary local-persistence failure.
     */
    @Volatile
    private var rollbackFailure: PhotoRollbackException? = null

    /** The forward publish failure must remain available if compensation fails. */
    @Volatile
    private var publishFailure: Throwable? = null

    override suspend fun publish() = withContext(Dispatchers.IO) {
        check(!committed) { "photo transaction is already committed" }
        rollbackFailure?.let { throw it }
        if (rolledBack) error("photo transaction has already been rolled back")
        try {
            entries.forEach { entry ->
                if (entry.targetExisted) {
                    moveWithFallback(entry.target.toPath(), entry.backup.toPath())
                    entry.backupMoved = true
                }
                moveWithFallback(entry.staged.toPath(), entry.target.toPath())
                entry.published = true
            }
        } catch (error: Throwable) {
            publishFailure = error
            try {
                rollbackInternal()
            } catch (rollback: PhotoRollbackException) {
                // Keep the rollback failure as the surfaced exception, while
                // retaining the original publish/move failure for recovery
                // diagnostics and coordinator error classification.
                if (rollback.suppressed.none { it === error }) {
                    rollback.addSuppressed(error)
                }
                throw rollback
            }
            throw error
        }
    }

    override suspend fun commit() = withContext(Dispatchers.IO) {
        rollbackFailure?.let { throw it }
        if (committed || rolledBack) return@withContext
        entries.forEach { entry ->
            // Cleanup is not part of the accepted document bytes.  A cleanup
            // failure must not turn an already durable/applied snapshot into
            // a false failed result after the coordinator records its cursor.
            runCatching { Files.deleteIfExists(entry.backup.toPath()) }
            runCatching { Files.deleteIfExists(entry.staged.toPath()) }
        }
        committed = true
    }

    override suspend fun rollback() = withContext(Dispatchers.IO) {
        rollbackFailure?.let { throw it }
        rollbackInternal()
    }

    private fun rollbackInternal() {
        rollbackFailure?.let { throw it }
        if (committed || rolledBack) return
        var firstFailure: Throwable? = null
        entries.asReversed().forEach { entry ->
            try {
                if (entry.published) Files.deleteIfExists(entry.target.toPath())
                if (entry.backupMoved) {
                    moveWithFallback(entry.backup.toPath(), entry.target.toPath())
                }
                Files.deleteIfExists(entry.staged.toPath())
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                } else {
                    firstFailure?.addSuppressed(error)
                }
            }
        }
        if (firstFailure != null) {
            val failure = PhotoRollbackException(
                "photo content rollback could not restore every prior file",
                firstFailure
            )
            publishFailure?.let { original ->
                if (original !== failure && failure.suppressed.none { it === original }) {
                    failure.addSuppressed(original)
                }
            }
            rollbackFailure = failure
            // Do not mark the transaction successfully rolled back.  A
            // subsequent compensating call must observe this failure rather
            // than silently returning as though all bytes were restored.
            throw failure
        }
        rolledBack = true
    }

    private fun moveWithFallback(source: Path, target: Path) {
        try {
            move(source, target)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Stages and validates the full incoming photo set without publishing it. */
        fun stage(
            rootDirectory: File,
            photoFiles: Map<String, ByteArray>,
            move: (Path, Path) -> Unit = { source, target ->
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        ): StagedPhotoContentTransaction {
            val root = rootDirectory.canonicalFile
            require(root.isDirectory || root.mkdirs()) { "photo root is unavailable" }
            val staged = mutableListOf<Entry>()
            try {
                photoFiles.forEach { (name, bytes) ->
                    require(name.isNotBlank()) { "photo filename is blank" }
                    val target = File(root, name).canonicalFile
                    require(target.toPath().startsWith(root.toPath())) {
                        "photo path escapes the document photo root: $name"
                    }
                    require(bytes.isNotEmpty()) { "photo content is empty: $name" }
                    val temp = File(root, ".stage4-photo-${UUID.randomUUID()}.tmp")
                    temp.outputStream().use { it.write(bytes) }
                    require(temp.length() == bytes.size.toLong()) {
                        "photo staging was incomplete: $name"
                    }
                    staged += Entry(
                        staged = temp,
                        target = target,
                        backup = File(root, ".stage4-photo-${UUID.randomUUID()}.bak"),
                        targetExisted = target.exists()
                    )
                }
            } catch (error: Throwable) {
                staged.forEach { it.staged.delete() }
                throw error
            }
            return StagedPhotoContentTransaction(root, staged, move)
        }
    }
}
