package com.example.myapplication.stage4

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun canonicalValidation_rejectsMissingStableAnnotationIds() {
        val snapshot = DocumentSnapshotV1(
            schemaVersion = 1,
            snapshotRevision = 0,
            source = DocumentSourceIdentityV1("content://validation", "plan.pdf"),
            pages = mapOf(
                0 to PageSnapshotV1(
                    shapes = listOf(
                        ShapeSnapshotV1(
                            x = 0f,
                            y = 0f,
                            width = 1f,
                            height = 1f,
                            rotation = 0f,
                            type = SnapshotShapeTypeV1.RECTANGLE,
                            colorArgb = 0,
                            strokeWidth = 1f,
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
