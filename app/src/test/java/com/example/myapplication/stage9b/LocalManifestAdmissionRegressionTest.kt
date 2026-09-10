package com.example.myapplication.stage9b

import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.LocalDocumentRepository
import com.example.myapplication.stage2.LocalRepositoryError
import com.example.myapplication.stage2.ManifestReadResult
import com.example.myapplication.stage2.ResolveDocumentResult
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.Stage5Limits
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Raw-input admission tests for the local manifest boundary. These tests use
 * the real repository entry points so an invalid manifest cannot accidentally
 * allocate a source association or fall through to an empty repository.
 */
class LocalManifestAdmissionRegressionTest {
    private val roots = mutableListOf<File>()
    private val gson = Gson()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun currentWriterOutput_withNullableFieldsAndProviderLimit_isAccepted() = runBlocking {
        val root = newRoot()
        val repository = LocalDocumentRepository(root)
        val metadata = (1..Stage5Limits.MAX_PROVIDER_PROPERTIES).associate { index ->
            "provider-$index" to "value-$index"
        }
        val source = DocumentSourceIdentityV1(
            sourceUri = "content://manifest/nullable",
            displayName = null,
            providerMetadata = metadata
        )

        val resolved = repository.resolveOrCreate(source, currentFingerprint = null)
        assertTrue(resolved is ResolveDocumentResult.Resolved)

        val read = repository.readManifest()
        assertTrue(read is ManifestReadResult.Loaded)
        val entry = (read as ManifestReadResult.Loaded).entries.single()
        assertEquals(source.sourceUri, entry.sourceUri)
        assertEquals(null, entry.displayName)
        assertEquals(metadata, entry.providerMetadata)
        assertEquals(null, entry.sourceFingerprint)
    }

    @Test
    fun validBoundaryValues_maxStringsFingerprintAndEntryCount_areAccepted() = runBlocking {
        val root = newRoot()
        val repository = LocalDocumentRepository(root)
        val sourceUriPrefix = "content://manifest/"
        val sourceUri = sourceUriPrefix + "u".repeat(Stage5Limits.MAX_STRING_CHARS - sourceUriPrefix.length)
        val displayName = "d".repeat(Stage5Limits.MAX_STRING_CHARS)
        val providerKey = "k".repeat(Stage5Limits.MAX_STRING_CHARS)
        val providerValue = "v".repeat(Stage5Limits.MAX_STRING_CHARS)
        val fingerprint = SourceFingerprint(
            algorithm = SourceFingerprint.SHA256_ALGORITHM,
            digestHex = "a".repeat(64),
            byteCount = Long.MAX_VALUE
        )
        val source = DocumentSourceIdentityV1(
            sourceUri = sourceUri,
            displayName = displayName,
            providerMetadata = mapOf(providerKey to providerValue)
        )
        val resolved = repository.resolveOrCreate(source, fingerprint)
        assertTrue(resolved is ResolveDocumentResult.Resolved)
        val loaded = repository.readManifest()
        assertTrue(loaded is ManifestReadResult.Loaded)
        val entry = (loaded as ManifestReadResult.Loaded).entries.single()
        assertEquals(source, DocumentSourceIdentityV1(entry.sourceUri, entry.displayName, entry.providerMetadata))
        assertEquals(fingerprint, entry.sourceFingerprint)

        // The association list limit is admitted exactly at its upper bound.
        val entries = (1..Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT).joinToString(",") { index ->
            val id = "00000000-0000-0000-0000-%012d".format(index)
            "{\"documentId\":${quote(id)},\"sourceUri\":${quote("content://manifest/entry/$index")},\"providerMetadata\":{}}"
        }
        val boundedRoot = newRoot("manifest-entry-boundary")
        val boundedFile = File(boundedRoot, "document-manifest.json")
        boundedFile.writeText("{\"schemaVersion\":2,\"entries\":[$entries]}", StandardCharsets.UTF_8)
        val boundedRead = LocalDocumentRepository(boundedRoot).readManifest()
        assertTrue(boundedRead is ManifestReadResult.Loaded)
        assertEquals(
            Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT,
            (boundedRead as ManifestReadResult.Loaded).entries.size
        )
    }

    @Test
    fun unknownField_isRejectedBeforeAssociationAndOriginalBytesRemainRecoverable() {
        assertCorruptAndPreserved(
            "{\"schemaVersion\":2,\"entries\":[${validEntry()}],\"unexpected\":true}"
        )
    }

    @Test
    fun missingRequiredField_isRejectedBeforeGsonDefaults() {
        val entry = "{\"documentId\":${quote(CANONICAL_ID)},\"sourceUri\":${quote(SOURCE_URI)}}"
        assertCorruptAndPreserved("{\"schemaVersion\":2,\"entries\":[$entry]}")
    }

    @Test
    fun coercedTypes_areRejectedForVersionIdentityMetadataAndFingerprint() {
        val cases = listOf(
            "{\"schemaVersion\":\"2\",\"entries\":[${validEntry()}]}",
            "{\"schemaVersion\":2.0,\"entries\":[${validEntry()}]}",
            "{\"schemaVersion\":2,\"entries\":[${validEntry(documentId = "1")}]}",
            "{\"schemaVersion\":2,\"entries\":[${validEntry(sourceUriValue = "false")}]}",
            "{\"schemaVersion\":2,\"entries\":[${validEntry(providerMetadataValue = "[]")}]}",
            "{\"schemaVersion\":2,\"entries\":[${validEntry(sourceFingerprintValue = quote("SHA-256:bad:0"))}]}",
            "{\"schemaVersion\":2,\"entries\":[${validEntry(byteCountValue = quote("0"))}]}"
        )
        cases.forEach { assertCorruptAndPreserved(it) }
    }

    @Test
    fun oversizedStringProviderAndEntryBudgets_areRejected() {
        val oversizedSource = validEntry(
            sourceUri = "content://manifest/oversized",
            sourceUriValue = quote("x".repeat(Stage5Limits.MAX_STRING_CHARS + 1))
        )
        assertCorruptAndPreserved("{\"schemaVersion\":2,\"entries\":[$oversizedSource]}")

        val oversizedProvider = (0..Stage5Limits.MAX_PROVIDER_PROPERTIES).joinToString(",") { index ->
            "${quote("key-$index")}:${quote("value")}"
        }
        val providerEntry = validEntry(providerMetadataValue = "{$oversizedProvider}")
        assertCorruptAndPreserved("{\"schemaVersion\":2,\"entries\":[$providerEntry]}")

        val tooManyEntries = (0..Stage5Limits.MAX_REMOTE_DESCRIPTOR_COUNT).joinToString(",") { "null" }
        assertCorruptAndPreserved("{\"schemaVersion\":2,\"entries\":[$tooManyEntries]}")
    }

    @Test
    fun duplicateKeys_atRootAndNestedObjects_areRejectedByStrictParser() {
        val rootDuplicate = """
            {"schemaVersion":2,"schemaVersion":2,"entries":[${validEntry()}]}
        """.trimIndent()
        assertCorruptAndPreserved(rootDuplicate)

        val nestedDuplicate = """
            {"schemaVersion":2,"entries":[{"documentId":"$CANONICAL_ID","sourceUri":"$SOURCE_URI","sourceUri":"content://other","providerMetadata":{}}]}
        """.trimIndent()
        assertCorruptAndPreserved(nestedDuplicate)
    }

    @Test
    fun excessiveNesting_isRejectedBeforeJsonTreeCanBeAccepted() {
        val deep = buildString {
            append("{\"schemaVersion\":2,\"entries\":")
            repeat(Stage5Limits.MAX_JSON_DEPTH + 2) { append('[') }
            append("null")
            repeat(Stage5Limits.MAX_JSON_DEPTH + 2) { append(']') }
            append('}')
        }
        assertCorruptAndPreserved(deep)
    }

    @Test
    fun malformedUtf8_isRejectedWithoutChangingTheRetainedBytes() {
        assertCorruptAndPreserved(
            "{\"schemaVersion\":2,\"entries\":[".toByteArray(StandardCharsets.UTF_8) +
                byteArrayOf('{'.code.toByte(), '}'.code.toByte(), 0xc3.toByte()) +
                "]}".toByteArray(StandardCharsets.UTF_8)
        )
    }

    @Test
    fun invalidUuidAndFingerprintComponents_areRejectedWithoutPartialEntry() {
        val invalidUuid = validEntry(documentId = "not-a-uuid")
        assertCorruptAndPreserved("{\"schemaVersion\":2,\"entries\":[$invalidUuid]}")

        val invalidAlgorithm = validEntry(
            sourceFingerprintValue = "{\"algorithm\":\"MD5\",\"digestHex\":${quote("a".repeat(64))},\"byteCount\":0}"
        )
        assertCorruptAndPreserved("{\"schemaVersion\":2,\"entries\":[$invalidAlgorithm]}")

        val invalidDigest = validEntry(
            sourceFingerprintValue = "{\"algorithm\":\"SHA-256\",\"digestHex\":\"bad\",\"byteCount\":0}"
        )
        assertCorruptAndPreserved("{\"schemaVersion\":2,\"entries\":[$invalidDigest]}")

        val negativeByteCount = validEntry(
            sourceFingerprintValue = "{\"algorithm\":\"SHA-256\",\"digestHex\":${quote("a".repeat(64))},\"byteCount\":-1}"
        )
        assertCorruptAndPreserved("{\"schemaVersion\":2,\"entries\":[$negativeByteCount]}")

        val duplicateIdentity = validEntry() + "," + validEntry(
            sourceUri = "content://manifest/other",
            documentId = CANONICAL_ID
        )
        assertCorruptAndPreserved("{\"schemaVersion\":2,\"entries\":[$duplicateIdentity]}")
    }

    @Test
    fun futureAndRetiredFormats_areUnsupportedAndStayInPlace() {
        val future = "{\"schemaVersion\":3,\"futureField\":{\"nested\":true},\"entries\":[]}"
        assertUnsupportedAndPreserved(future)

        val retired = """
            {"schemaVersion":2,"entries":[${validEntry()}],"legacyArtifactName":"old.bin"}
        """.trimIndent()
        assertUnsupportedAndPreserved(retired)
    }

    private fun assertCorruptAndPreserved(raw: String) {
        assertCorruptAndPreserved(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun assertCorruptAndPreserved(bytes: ByteArray) {
        val root = newRoot("manifest-corrupt")
        val manifest = File(root, "document-manifest.json")
        manifest.writeBytes(bytes)
        val result = runBlocking {
            LocalDocumentRepository(root).resolveOrCreate(
                DocumentSourceIdentityV1("content://manifest/new-association"),
                currentFingerprint = null
            )
        }
        assertTrue(result is ResolveDocumentResult.Failed)
        assertTrue((result as ResolveDocumentResult.Failed).error is LocalRepositoryError.CorruptManifest)
        assertNoPartiallyAcceptedAssociation(root)
        assertArrayEquals(bytes, preservedManifestBytes(root, manifest))
    }

    private fun assertUnsupportedAndPreserved(raw: String) {
        val root = newRoot("manifest-unsupported")
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        val manifest = File(root, "document-manifest.json")
        manifest.writeBytes(bytes)
        val result = runBlocking { LocalDocumentRepository(root).readManifest() }
        assertTrue(result is ManifestReadResult.Failed)
        assertTrue((result as ManifestReadResult.Failed).error is LocalRepositoryError.UnsupportedFormat)
        assertNoPartiallyAcceptedAssociation(root)
        assertArrayEquals(bytes, preservedManifestBytes(root, manifest))
    }

    private fun preservedManifestBytes(root: File, manifest: File): ByteArray {
        if (manifest.exists()) return manifest.readBytes()
        val quarantined = root.resolve("quarantine").walk()
            .filter { it.isFile }
            .singleOrNull()
            ?: error("rejected manifest bytes were not retained")
        return quarantined.readBytes()
    }

    private fun assertNoPartiallyAcceptedAssociation(root: File) {
        val documents = root.resolve("documents")
        assertTrue(documents.exists())
        assertFalse(documents.listFiles().orEmpty().any { it.isDirectory })
    }

    private fun newRoot(prefix: String = "manifest-admission"): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    private fun quote(value: String): String = gson.toJson(value)

    private fun validEntry(
        documentId: String = CANONICAL_ID,
        sourceUri: String = SOURCE_URI,
        sourceUriValue: String = quote(sourceUri),
        providerMetadataValue: String = "{}",
        sourceFingerprintValue: String? = null,
        byteCountValue: String = "0"
    ): String {
        val fingerprint = sourceFingerprintValue ?: "{\"algorithm\":\"SHA-256\",\"digestHex\":${quote("a".repeat(64))},\"byteCount\":$byteCountValue}"
        return "{\"documentId\":${quote(documentId)},\"sourceUri\":$sourceUriValue,\"displayName\":\"plan.pdf\",\"providerMetadata\":$providerMetadataValue,\"sourceFingerprint\":$fingerprint}"
    }

    private companion object {
        const val CANONICAL_ID = "00000000-0000-0000-0000-000000000001"
        const val SOURCE_URI = "content://manifest/source"
    }
}
