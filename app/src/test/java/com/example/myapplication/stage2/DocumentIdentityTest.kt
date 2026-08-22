package com.example.myapplication.stage2

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class DocumentIdentityTest {
    @Test
    fun documentId_isNotUriFilenameOrContentHash() {
        val id = DocumentId.new()
        assertTrue(id.value.matches(Regex("[0-9a-f-]{36}")))
        assertNotEquals("plan.pdf", id.value)
        assertNotEquals("content://documents/plan.pdf", id.value)
        assertNotEquals("0", id.value)
    }

    @Test
    fun documentId_parseRejectsNonCanonicalValues() {
        val id = DocumentId.new()
        assertEquals(id, DocumentId.parse(id.value.uppercase()))
        try {
            DocumentId.parse("not-a-uuid")
            throw AssertionError("invalid id was accepted")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun fingerprint_isRawSha256AndStableForSameBytes() = runBlocking {
        val bytes = "raw PDF bytes".toByteArray()
        val reader = SourceFingerprintReader { ByteArrayInputStream(bytes) }
        val first = fingerprintSource(reader, "content://one")
        val second = fingerprintSource(reader, "content://two")
        assertEquals(first, second)
        assertEquals(13L, first?.byteCount)
        assertEquals("SHA-256", first?.algorithm)
        assertEquals(64, first?.digestHex?.length)
    }

    @Test
    fun fingerprint_changesWhenOneByteChanges_evenIfLengthDoesNot() = runBlocking {
        val firstReader = SourceFingerprintReader { ByteArrayInputStream("AAAA".toByteArray()) }
        val secondReader = SourceFingerprintReader { ByteArrayInputStream("AAAB".toByteArray()) }
        assertNotEquals(
            fingerprintSource(firstReader, "content://same"),
            fingerprintSource(secondReader, "content://same")
        )
    }

    @Test
    fun fingerprintUnavailable_isExplicitNullRatherThanAnInventedIdentity() = runBlocking {
        val result = fingerprintSource(SourceFingerprintReader { null }, "content://unreadable")
        assertEquals(null, result)
    }
}
