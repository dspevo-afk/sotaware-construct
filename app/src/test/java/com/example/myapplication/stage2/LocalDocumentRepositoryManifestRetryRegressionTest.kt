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

/** Regression for a quarantined corrupt manifest becoming a fresh empty mapping on retry. */
class LocalDocumentRepositoryManifestRetryRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun quarantinedManifestEvidenceBlocksNewIdentityOnRetry() = runBlocking {
        val root = temporary.newFolder("documents")
        val repository = LocalDocumentRepository(root)
        val source = DocumentSourceIdentityV1(
            sourceUri = "content://provider/accepted-plan",
            displayName = "plan.pdf",
            providerMetadata = mapOf("authority" to "provider")
        )
        val fingerprint = SourceFingerprint.fromBytes("accepted PDF".toByteArray())
        val original = (repository.resolveOrCreate(source, fingerprint)
            as ResolveDocumentResult.Resolved).association
        assertTrue(repository.save(original, CurrentStateFixture.fullyPopulatedSnapshot(source))
            is DocumentSaveResult.Saved)
        val acceptedBytes = repository.currentSnapshotFile(original.documentId).readBytes()

        File(root, "document-manifest.json").writeText("{ truncated manifest", Charsets.UTF_8)
        val firstAttempt = repository.resolveOrCreate(source, fingerprint)
        assertTrue(firstAttempt is ResolveDocumentResult.Failed)
        assertTrue(File(root, "quarantine").listFiles().orEmpty().isNotEmpty())

        val retry = repository.resolveOrCreate(source, fingerprint)

        assertTrue(
            "quarantined manifest evidence must not be reinterpreted as a never-used repository",
            retry is ResolveDocumentResult.Failed
        )
        assertArrayEquals(acceptedBytes, repository.currentSnapshotFile(original.documentId).readBytes())
        assertTrue(repository.load(original) is DocumentLoadResult.Loaded)
    }
}
