package com.example.myapplication.stage9b

import com.example.myapplication.stage2.DocumentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentDocumentStoreTest {
    @Test
    fun duplicateJsonFieldsAreRejectedWithoutReplacingStoredInput() {
        val storage = MemoryStorage()
        val raw = "{\"schemaVersion\":99,\"schemaVersion\":1,\"records\":[]}"
        storage.serialized = raw
        val store = SharedPreferencesRecentDocumentStore(storage)
        assertTrue(store.read() is RecentDocumentReadResult.Failed)
        assertTrue(store.record(record(1, "content://plans/a", "a.pdf", 1L)) is RecentDocumentWriteResult.Failed)
        assertEquals(raw, storage.serialized)
    }

    @Test
    fun recordsAreNewestFirstAndDoNotDependOnInputOrder() {
        val older = record(1, "content://plans/older", "older.pdf", 10L)
        val newer = record(2, "content://plans/newer", "newer.pdf", 20L)

        assertEquals(
            listOf(newer, older),
            orderRecentDocumentRecords(listOf(older, newer))
        )
        assertEquals(
            listOf(newer, older),
            orderRecentDocumentRecords(listOf(newer, older))
        )
    }

    @Test
    fun sameNameDocumentsRemainSeparateWhenIdentityOrSourceDiffers() {
        val first = record(1, "content://plans/a", "plan.pdf", 10L)
        val second = record(2, "content://plans/b", "plan.pdf", 9L)

        val ordered = orderRecentDocumentRecords(listOf(first, second))

        assertEquals(2, ordered.size)
        assertEquals(setOf(first.documentId, second.documentId), ordered.map { it.documentId }.toSet())
        assertEquals(setOf(first.sourceUri, second.sourceUri), ordered.map { it.sourceUri }.toSet())
    }

    @Test
    fun timestampTiesUseStableDocumentAndSourceOrder() {
        val idOne = record(1, "content://plans/z", "same.pdf", 10L)
        val idTwo = record(2, "content://plans/a", "same.pdf", 10L)

        // The DocumentId is the first deterministic tie-breaker, so the result
        // is unchanged when the input order is reversed.
        assertEquals(
            listOf(idOne, idTwo),
            orderRecentDocumentRecords(listOf(idTwo, idOne))
        )
        assertEquals(
            listOf(idOne, idTwo),
            orderRecentDocumentRecords(listOf(idOne, idTwo))
        )
    }

    @Test
    fun reopeningTheSameDocumentAndSourceReplacesItsEntryAndTimestamp() {
        val first = record(1, "content://plans/a", "old-name.pdf", 10L)
        val reopened = record(1, "content://plans/a", "new-name.pdf", 30L)

        val result = recordRecentDocument(listOf(first), reopened)

        assertEquals(listOf(reopened), result)
    }

    @Test
    fun entriesAreBoundedToTwentyNewestRecords() {
        val records = (1..25).map { index ->
            record(index, "content://plans/$index", "plan-$index.pdf", index.toLong())
        }

        val result = orderRecentDocumentRecords(records)

        assertEquals(MAX_RECENT_DOCUMENTS, result.size)
        assertEquals(record(25, "content://plans/25", "plan-25.pdf", 25L), result.first())
        assertEquals(record(6, "content://plans/6", "plan-6.pdf", 6L), result.last())
    }

    @Test
    fun currentFormatRoundTripsThroughInjectedDurableStorage() {
        val storage = MemoryStorage()
        val store = SharedPreferencesRecentDocumentStore(storage)
        val expected = record(1, "content://plans/a", null, 123L)

        assertEquals(RecentDocumentWriteResult.Committed, store.record(expected))
        assertEquals(
            RecentDocumentReadResult.Loaded(listOf(expected)),
            SharedPreferencesRecentDocumentStore(storage).read()
        )
    }

    @Test
    fun unsupportedOrCorruptCurrentValueIsFailedAndPreserved() {
        val storage = MemoryStorage()
        val store = SharedPreferencesRecentDocumentStore(storage)
        val expected = record(1, "content://plans/a", "plan.pdf", 123L)
        assertEquals(RecentDocumentWriteResult.Committed, store.record(expected))

        val unsupported = "{\"schemaVersion\":99,\"records\":[]}"
        storage.serialized = unsupported
        val unsupportedResult = store.read()
        assertTrue(unsupportedResult is RecentDocumentReadResult.Failed)
        assertEquals(
            RecentDocumentStoreError.UnsupportedSchema(99),
            (unsupportedResult as RecentDocumentReadResult.Failed).error
        )
        assertTrue(store.record(record(2, "content://plans/b", "other.pdf", 124L)) is RecentDocumentWriteResult.Failed)
        assertEquals(unsupported, storage.serialized)

        val corrupt = "{\"schemaVersion\":1,\"records\":[{\"documentId\":\"${id(1).value}\",\"sourceUri\":\"content://plans/a\",\"displayName\":\"plan.pdf\"}]}"
        storage.serialized = corrupt
        val corruptResult = store.read()
        assertTrue(corruptResult is RecentDocumentReadResult.Failed)
        assertTrue((corruptResult as RecentDocumentReadResult.Failed).error is RecentDocumentStoreError.CorruptData)
        assertEquals(corrupt, storage.serialized)
    }

    @Test
    fun failedCommitReturnsTypedFailureAndDoesNotReplacePriorAuthority() {
        val storage = MemoryStorage()
        val store = SharedPreferencesRecentDocumentStore(storage)
        val first = record(1, "content://plans/a", "plan.pdf", 10L)
        val second = record(2, "content://plans/b", "other.pdf", 20L)
        assertEquals(RecentDocumentWriteResult.Committed, store.record(first))
        val prior = storage.serialized

        storage.failCommit = true
        val result = store.record(second)

        assertTrue(result is RecentDocumentWriteResult.Failed)
        assertTrue((result as RecentDocumentWriteResult.Failed).error is RecentDocumentStoreError.WriteFailed)
        assertEquals(prior, storage.serialized)
    }

    @Test
    fun missingTimestampIsCorruptRatherThanAssignedALegacyTimestamp() {
        val storage = MemoryStorage(
            "{\"schemaVersion\":1,\"records\":[{" +
                "\"documentId\":\"${id(1).value}\",\"sourceUri\":\"content://plans/a\",\"displayName\":\"plan.pdf\"}] }"
        )

        assertTrue(SharedPreferencesRecentDocumentStore(storage).read() is RecentDocumentReadResult.Failed)
        assertEquals(
            "{\"schemaVersion\":1,\"records\":[{" +
                "\"documentId\":\"${id(1).value}\",\"sourceUri\":\"content://plans/a\",\"displayName\":\"plan.pdf\"}] }",
            storage.serialized
        )
    }

    private fun record(
        id: Int,
        sourceUri: String,
        displayName: String?,
        timestamp: Long
    ): RecentDocumentRecord = RecentDocumentRecord(
        documentId = id(id),
        sourceUri = sourceUri,
        displayName = displayName,
        lastSuccessfullyOpenedAtEpochMillis = timestamp
    )

    private fun id(value: Int): DocumentId = DocumentId.parse(
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
    )

    private class MemoryStorage(initial: String? = null) : RecentDocumentStoreStorage {
        var serialized: String? = initial
        var failCommit: Boolean = false

        override fun readSerialized(): String? = serialized

        override fun commitSerialized(serialized: String): Boolean {
            if (failCommit) return false
            this.serialized = serialized
            return true
        }
    }
}
