package com.example.myapplication.stage2

import com.example.myapplication.stage0.CurrentStateFixture
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Regression for rollback publication with a stale/forged document association. */
class LocalDocumentRepositoryStaleRestoreRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun matchingAssociationCanRestoreAnOriginallyAbsentSnapshot() = runBlocking {
        val repository = LocalDocumentRepository(temporary.newFolder("legitimate-rollback"))
        val source = DocumentSourceIdentityV1("content://provider/provisional", "provisional.pdf")
        val association = (repository.resolveOrCreate(source, SourceFingerprint.fromBytes("PDF".toByteArray()))
            as ResolveDocumentResult.Resolved).association
        assertTrue(repository.save(association, CurrentStateFixture.fullyPopulatedSnapshot(source))
            is DocumentSaveResult.Saved)
        val result = repository.restoreDurableSnapshotState(
            association, DocumentDurableSnapshotState(current = null, previous = null, accepted = false)
        )
        assertTrue(result is DocumentSaveResult.Saved)
        assertTrue(repository.load(association) == DocumentLoadResult.NotFound)
        assertTrue(!repository.currentSnapshotFile(association.documentId).exists())
        assertTrue(!repository.acceptedSnapshotStateFile(association.documentId).exists())
    }

    @Test
    fun restoreWithMismatchedAssociationCannotDeleteAcceptedDocument() = runBlocking {
        val root: File = temporary.newFolder("documents")
        val repository = LocalDocumentRepository(root)
        val authoritativeSource = DocumentSourceIdentityV1(
            sourceUri = "content://provider/authoritative-plan",
            displayName = "plan.pdf",
            providerMetadata = mapOf("authority" to "provider")
        )
        val authoritativeFingerprint = SourceFingerprint.fromBytes("authoritative PDF".toByteArray())
        val association = (repository.resolveOrCreate(authoritativeSource, authoritativeFingerprint)
            as ResolveDocumentResult.Resolved).association
        val acceptedSnapshot = CurrentStateFixture.fullyPopulatedSnapshot(authoritativeSource)
        assertTrue(repository.save(association, acceptedSnapshot) is DocumentSaveResult.Saved)

        val currentBefore = repository.currentSnapshotFile(association.documentId).readBytes()
        val previousBefore = repository.previousSnapshotFile(association.documentId)
            .takeIf { it.isFile }?.readBytes()
        val acceptedStateBefore = repository.acceptedSnapshotStateFile(association.documentId).readBytes()
        val forgedAssociation = association.copy(
            source = DocumentSourceIdentityV1(
                sourceUri = "content://provider/different-plan",
                displayName = "other.pdf",
                providerMetadata = mapOf("authority" to "provider")
            ),
            sourceFingerprint = SourceFingerprint.fromBytes("different PDF".toByteArray())
        )
        val emptyRollback = DocumentDurableSnapshotState(
            current = null,
            previous = null,
            accepted = false
        )

        val result = repository.restoreDurableSnapshotState(forgedAssociation, emptyRollback)

        assertTrue("mismatched source association must be rejected", result is DocumentSaveResult.Failed)
        assertArrayEquals(currentBefore, repository.currentSnapshotFile(association.documentId).readBytes())
        val previousAfter = repository.previousSnapshotFile(association.documentId)
            .takeIf { it.isFile }?.readBytes()
        if (previousBefore == null) {
            assertTrue(previousAfter == null)
        } else {
            assertArrayEquals(previousBefore, previousAfter)
        }
        assertArrayEquals(
            acceptedStateBefore,
            repository.acceptedSnapshotStateFile(association.documentId).readBytes()
        )
        val loaded = repository.load(association)
        assertTrue("the accepted snapshot must remain loadable", loaded is DocumentLoadResult.Loaded)
        assertTrue((loaded as DocumentLoadResult.Loaded).snapshot.pages.isNotEmpty())
    }
}
