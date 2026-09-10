package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentLoadResult
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.ResolveDocumentResult
import com.example.myapplication.stage2.SourceFingerprint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Current-only local persistence must reject retired envelopes as data-format
 * failures. These tests exercise the real repository and verify that rejection
 * neither allocates a new identity nor destroys the unsupported bytes.
 */
class CurrentFormatRejectionTest {
    @Test
    fun unsupportedManifest_isExplicitAndNonDestructive() = runBlocking {
        val root = Files.createTempDirectory("stage9b-current-manifest").toFile()
        try {
            val manifest = File(root, "document-manifest.json")
            val oldBytes = """
                {"schemaVersion":1,"entries":[{"documentId":"00000000-0000-0000-0000-000000000001","sourceUri":"content://retired/plan","displayName":"plan.pdf","providerMetadata":{},"sourceFingerprint":null,"migrationVerified":false,"legacyMigrationClaimed":false,"legacyArtifactName":"markups-1.bin"}]}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            manifest.writeBytes(oldBytes)

            val repository = LocalDocumentRepository(root)
            val read = repository.readManifest()
            assertTrue(read is com.example.myapplication.stage2.ManifestReadResult.Failed)
            val readError = (read as com.example.myapplication.stage2.ManifestReadResult.Failed).error
            assertTrue(readError is LocalRepositoryError.UnsupportedFormat)
            assertArrayEquals(oldBytes, manifest.readBytes())
            assertFalse(File(root, "quarantine").walk().any { it.isFile })

            val resolve = repository.resolveOrCreate(
                source = DocumentSourceIdentityV1("content://retired/new", "new.pdf"),
                currentFingerprint = fingerprint("new")
            )
            assertTrue(resolve is ResolveDocumentResult.Failed)
            assertTrue((resolve as ResolveDocumentResult.Failed).error is LocalRepositoryError.UnsupportedFormat)
            assertArrayEquals(oldBytes, manifest.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unsupportedSnapshotEnvelope_isExplicitAndDoesNotQuarantineOrReplaceBytes() = runBlocking {
        val root = Files.createTempDirectory("stage9b-current-snapshot").toFile()
        try {
            val repository = LocalDocumentRepository(root)
            val source = DocumentSourceIdentityV1("content://retired/snapshot", "plan.pdf")
            val association = when (
                val resolved = repository.resolveOrCreate(source, fingerprint("source"))
            ) {
                is ResolveDocumentResult.Resolved -> resolved.association
                else -> error("test association could not be created: $resolved")
            }
            val current = repository.currentSnapshotFile(association.documentId)
            require(current.parentFile?.exists() == true || current.parentFile?.mkdirs() == true) {
                "document snapshot directory could not be created"
            }
            val oldBytes = """
                {"storageSchemaVersion":1,"documentId":"${association.documentId.value}","sourceFingerprint":null,"snapshot":{"schemaVersion":2,"snapshotRevision":0,"source":{"sourceUri":"${source.sourceUri}","displayName":"${source.displayName}","providerMetadata":{}},"pages":{}}}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            current.writeBytes(oldBytes)

            val loaded = repository.load(association)
            assertTrue(loaded is DocumentLoadResult.Failed)
            val error = (loaded as DocumentLoadResult.Failed).error
            assertTrue(error is LocalRepositoryError.UnsupportedFormat)
            assertArrayEquals(oldBytes, current.readBytes())
            assertFalse(repository.snapshotQuarantineDirectory(association.documentId).walk().any { it.isFile })

            // A rejected old envelope cannot be converted into an empty/new
            // success through a second repository instance either.
            val reopened = LocalDocumentRepository(root).load(association)
            assertTrue(reopened is DocumentLoadResult.Failed)
            assertTrue((reopened as DocumentLoadResult.Failed).error is LocalRepositoryError.UnsupportedFormat)
            assertArrayEquals(oldBytes, current.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unsupportedEmbeddedSnapshotSchema_isExplicitAndNonDestructive() = runBlocking {
        val root = Files.createTempDirectory("stage9b-embedded-snapshot").toFile()
        try {
            val repository = LocalDocumentRepository(root)
            val source = DocumentSourceIdentityV1("content://retired/embedded", "plan.pdf")
            val association = when (
                val resolved = repository.resolveOrCreate(source, fingerprint("embedded"))
            ) {
                is ResolveDocumentResult.Resolved -> resolved.association
                else -> error("test association could not be created: $resolved")
            }
            val current = repository.currentSnapshotFile(association.documentId)
            require(current.parentFile?.exists() == true || current.parentFile?.mkdirs() == true) {
                "document snapshot directory could not be created"
            }
            val oldBytes = """
                {"storageSchemaVersion":2,"documentId":"${association.documentId.value}","sourceFingerprint":null,"snapshot":{"schemaVersion":1}}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            current.writeBytes(oldBytes)

            val loaded = repository.load(association)
            assertTrue(loaded is DocumentLoadResult.Failed)
            val error = (loaded as DocumentLoadResult.Failed).error
            assertTrue(error is LocalRepositoryError.UnsupportedFormat)
            assertArrayEquals(oldBytes, current.readBytes())
            assertFalse(repository.snapshotQuarantineDirectory(association.documentId).walk().any { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fingerprint(value: String): SourceFingerprint =
        SourceFingerprint.fromBytes(value.toByteArray(Charsets.UTF_8))
}
