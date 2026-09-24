package com.example.myapplication.stage2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test
    fun fingerprintRepeatedZeroReads_failsClosedAndClosesOwnedStream() = runBlocking {
        val stream = ScriptedInputStream(zeroReadsBeforeBytes = Int.MAX_VALUE, bytes = byteArrayOf())

        val result = fingerprintSource(SourceFingerprintReader { stream }, "content://stalled")

        assertEquals(null, result)
        assertTrue("zero reads must be bounded", stream.readCalls < 100)
        assertTrue("fingerprinting owns and closes the stream", stream.closed)
    }

    @Test
    fun fingerprintTransientZeroReads_resumesHashingAndClosesOwnedStream() = runBlocking {
        val bytes = "provider transient reads".toByteArray()
        val stream = ScriptedInputStream(zeroReadsBeforeBytes = 4, bytes = bytes)

        val result = fingerprintSource(SourceFingerprintReader { stream }, "content://transient")

        assertEquals(SourceFingerprint.fromBytes(bytes), result)
        assertTrue(stream.readCalls > 4)
        assertTrue(stream.closed)
    }

    @Test
    fun fingerprintCancellation_closesSlowOwnedStreamAndFinishesPromptly() = runBlocking {
        val stream = CloseUnblocksInputStream()
        val operation = async(Dispatchers.Default) {
            fingerprintSource(SourceFingerprintReader { stream }, "content://slow")
        }
        assertTrue("hashing should reach the provider stream", stream.readStarted.await(5, TimeUnit.SECONDS))

        operation.cancel()
        withTimeout(5_000) { operation.join() }

        assertTrue("cancellation closes the owned stream", stream.closed)
        assertTrue(operation.isCancelled)
    }

    private class ScriptedInputStream(
        private val zeroReadsBeforeBytes: Int,
        private val bytes: ByteArray
    ) : InputStream() {
        var readCalls: Int = 0
            private set
        @Volatile
        var closed: Boolean = false
            private set
        private var position = 0

        override fun read(): Int = throw UnsupportedOperationException("buffered reads expected")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls += 1
            if (closed) throw IOException("stream closed")
            if (readCalls <= zeroReadsBeforeBytes) return 0
            if (position == bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            System.arraycopy(bytes, position, buffer, offset, count)
            position += count
            return count
        }

        override fun close() {
            closed = true
        }
    }

    private class CloseUnblocksInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile
        var closed: Boolean = false
            private set

        override fun read(): Int = throw UnsupportedOperationException("buffered reads expected")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readStarted.countDown()
            if (!released.await(5, TimeUnit.SECONDS)) {
                throw IOException("test stream timed out")
            }
            if (closed) throw IOException("stream closed")
            return -1
        }

        override fun close() {
            closed = true
            released.countDown()
        }
    }
}
