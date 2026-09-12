package com.example.myapplication.stage10

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.DocumentSaveResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.ResolveDocumentResult
import com.example.myapplication.stage2.SourceFingerprint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android file-backed recovery evidence; each fixture owns one disposable directory. */
@RunWith(AndroidJUnit4::class)
class AuditRecoveryInstrumentedTest {
    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun acceptedRecoveryExhaustion_remainsTypedFailureAfterRestart() = withRepository { root, repository ->
        val source = source("content://audit/recovery-exhaustion")
        val association = resolve(repository, source)
        assertTrue(repository.save(association, snapshot(source, 1L)) is DocumentSaveResult.Saved)
        assertTrue(repository.save(association, snapshot(source, 2L)) is DocumentSaveResult.Saved)

        repository.currentSnapshotFile(association.documentId).writeText("corrupt current", Charsets.UTF_8)
        repository.previousSnapshotFile(association.documentId).writeText("corrupt previous", Charsets.UTF_8)

        repeat(2) {
            val result = repository.load(association)
            assertCorruptFailure(result)
        }
        assertTrue(repository.acceptedSnapshotStateFile(association.documentId).isFile)

        val reopened = LocalDocumentRepository(root)
        repeat(2) {
            val result = reopened.load(association)
            assertCorruptFailure(result)
        }
    }

    @Test
    fun acceptedSlotsDeleted_neverBecomeNotFoundAfterRestart() = withRepository { root, repository ->
        val source = source("content://audit/deleted-slots")
        val association = resolve(repository, source)
        assertTrue(repository.save(association, snapshot(source, 1L)) is DocumentSaveResult.Saved)
        assertTrue(repository.currentSnapshotFile(association.documentId).delete())
        assertFalse(repository.previousSnapshotFile(association.documentId).exists())

        assertCorruptFailure(repository.load(association))
        assertCorruptFailure(LocalDocumentRepository(root).load(association))
    }

    @Test
    fun previousGoodRecovery_remainsLoadedAndNeverPublishesEmpty() = withRepository { _, repository ->
        val source = source("content://audit/previous-good")
        val association = resolve(repository, source)
        val first = snapshot(source, 1L)
        assertTrue(repository.save(association, first) is DocumentSaveResult.Saved)
        assertTrue(repository.save(association, snapshot(source, 2L)) is DocumentSaveResult.Saved)
        repository.currentSnapshotFile(association.documentId).writeText("corrupt current", Charsets.UTF_8)

        val loaded = repository.load(association)
        assertTrue(loaded is DocumentLoadResult.Loaded)
        assertEquals(first, (loaded as DocumentLoadResult.Loaded).snapshot)
        assertTrue(loaded.recoveredFromPrevious)
    }

    @Test
    fun neverSavedAssociation_remainsNotFound() = withRepository { _, repository ->
        val association = resolve(repository, source("content://audit/never-saved"))
        assertEquals(DocumentLoadResult.NotFound, repository.load(association))
        assertFalse(repository.acceptedSnapshotStateFile(association.documentId).exists())
    }

    private fun assertCorruptFailure(result: DocumentLoadResult) {
        assertTrue("accepted recovery must remain a typed failure: $result", result is DocumentLoadResult.Failed)
        assertTrue(
            (result as DocumentLoadResult.Failed).error is LocalRepositoryError.CorruptSnapshot
        )
    }

    private fun source(uri: String) = DocumentSourceIdentityV1(uri, "audit.pdf")

    private fun snapshot(source: DocumentSourceIdentityV1, revision: Long) =
        DocumentSnapshotV1(
            schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = revision,
            source = source,
            pages = emptyMap()
        )

    private suspend fun resolve(
        repository: LocalDocumentRepository,
        source: DocumentSourceIdentityV1
    ): DocumentAssociation = when (
        val resolved = repository.resolveOrCreate(source, SourceFingerprint.fromBytes(source.sourceUri.toByteArray()))
    ) {
        is ResolveDocumentResult.Resolved -> resolved.association
        else -> error("fixture association could not be resolved: $resolved")
    }

    private fun <T> withRepository(block: suspend (File, LocalDocumentRepository) -> T): T {
        val root = File(targetContext.filesDir, "audit-recovery-${UUID.randomUUID()}")
        check(root.mkdirs()) { "could not create isolated repository fixture" }
        return try {
            runBlocking { block(root, LocalDocumentRepository(root)) }
        } finally {
            root.deleteRecursively()
        }
    }
}
