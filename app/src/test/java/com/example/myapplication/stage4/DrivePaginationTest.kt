package com.example.myapplication.stage4

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.ShapeSnapshotV1
import com.example.myapplication.stage1.SnapshotShapeTypeV1

@OptIn(ExperimentalCoroutinesApi::class)
class DrivePaginationTest {
    @Test
    fun activeDrivePageHelper_followsEveryContinuationToken() = runTest {
        val requestedTokens = mutableListOf<String?>()
        val pages = mapOf(
            null to DrivePage(listOf("folder-a"), "page-1"),
            "page-1" to DrivePage(listOf("folder-b"), "page-2"),
            "page-2" to DrivePage(listOf("folder-c"), null)
        )

        val result = collectDrivePages { token ->
            requestedTokens += token
            pages.getValue(token)
        }

        assertEquals(listOf("folder-a", "folder-b", "folder-c"), result)
        assertEquals(listOf(null, "page-1", "page-2"), requestedTokens)
    }

    @Test(expected = IllegalStateException::class)
    fun activeDrivePageHelper_rejectsRepeatedContinuationToken() = runTest {
        collectDrivePages { token ->
            if (token == null) DrivePage(listOf("first"), "same")
            else DrivePage(emptyList(), "same")
        }
    }

    @Test
    fun activeDrivePageHelper_cancellationAfterFirstResponseDoesNotFetchPageTwo() = runTest {
        var fetches = 0
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            collectDrivePages { _ ->
                fetches += 1
                if (fetches == 1) {
                    currentCoroutineContext().cancel(CancellationException("synthetic page-one cancellation"))
                }
                DrivePage(listOf("item-$fetches"), if (fetches < 5) "page-$fetches" else null)
            }
        }

        var cancelled = false
        try {
            request.await()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals("cancellation must be admitted before the next request", 1, fetches)
    }

    @Test
    fun activeDrivePageHelper_treatsWhitespaceTokenAsTerminal() = runTest {
        val requestedTokens = mutableListOf<String?>()

        val result = collectDrivePages { token ->
            requestedTokens += token
            DrivePage(listOf("only-item"), "   ")
        }

        assertEquals(listOf("only-item"), result)
        assertEquals(listOf<String?>(null), requestedTokens)
    }

    @Test
    fun activeDrivePageHelper_rejectsDuplicateIdentitiesAcrossPages() = runTest {
        val failure = runCatching {
            collectDrivePages(
                identity = { it },
                fetchPage = { token ->
                    if (token == null) DrivePage(listOf("same"), "page-2")
                    else DrivePage(listOf("same"), null)
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is DrivePaginationException)
        assertTrue(requireNotNull(failure).message.orEmpty().contains("duplicate"))
    }

    @Test
    fun activeDrivePageHelper_rejectsBudgetBeforeReturningPartialResults() = runTest {
        var fetches = 0
        val failure = runCatching {
            collectDrivePages(
                limits = DriveListingLimits(maxPages = 5, maxItems = 5, maxMetadataBytes = 4),
                metadataBytes = { 3L },
                fetchPage = {
                    fetches += 1
                    DrivePage(listOf("a", "b"), null)
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is DrivePaginationException)
        assertEquals("the over-budget response is rejected on its first page", 1, fetches)
        assertFalse(failure is CancellationException)
    }

    @Test
    fun canonicalValidation_rejectsMissingStableAnnotationIds() {
        val snapshot = DocumentSnapshotV1(
            schemaVersion = 2,
            snapshotRevision = 0,
            source = DocumentSourceIdentityV1("content://validation", "plan.pdf"),
            pages = mapOf(
                0 to PageSnapshotV1(
                    shapes = listOf(
                        ShapeSnapshotV1(
                            x = 0f,
                            y = 0f,
                            rotation = 0f,
                            type = SnapshotShapeTypeV1.RECTANGLE,
                            colorArgb = 0,
                            isFilled = false,
                            strokeWidthRatio = 0f,
                            widthRatio = 1f,
                            heightRatio = 1f,
                            id = ""
                        )
                    )
                )
            )
        )

        val rejected = runCatching { requireValidSnapshot(snapshot) }.isFailure

        assertTrue(rejected)
    }
}
