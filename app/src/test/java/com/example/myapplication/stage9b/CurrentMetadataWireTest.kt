package com.example.myapplication.stage9b

import com.example.myapplication.stage5.Stage5ValidationException
import com.example.myapplication.stage5.validateSyncMetadataTree
import com.google.gson.JsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

/** The current metadata boundary rejects retired data before materialization. */
class CurrentMetadataWireTest {
    @Test fun currentEmptyMetadataIsAccepted() {
        validateSyncMetadataTree(metadata())
    }

    @Test fun retiredMetadataIsNotInterpretedAsCurrent() {
        val retired = metadata().apply { addProperty("schemaVersion", 1) }
        assertThrows(Stage5ValidationException::class.java) { validateSyncMetadataTree(retired) }
    }

    @Test fun currentMetadataRejectsBothRetiredInlineFields() {
        listOf("pendingUploadSnapshotJson", "pendingUploadPhotoFiles").forEach { name ->
            val value = metadata().apply { addProperty(name, "retired") }
            assertThrows(Stage5ValidationException::class.java) { validateSyncMetadataTree(value) }
        }
    }

    @Test fun completeCurrentSidecarIncludesPhotoFreePendingWork() {
        validateSyncMetadataTree(pending())
    }

    @Test fun retiredSidecarAndIncompletePendingGroupsAreRejected() {
        val retired = pending().apply {
            getAsJsonObject("pendingUploadPhotoSidecar").addProperty("schemaVersion", 2)
        }
        assertThrows(Stage5ValidationException::class.java) { validateSyncMetadataTree(retired) }
        val incomplete = pending().apply { remove("pendingUploadPhotoSidecar") }
        assertThrows(Stage5ValidationException::class.java) { validateSyncMetadataTree(incomplete) }
        val orphan = metadata().apply { addProperty("pendingUploadGeneration", 1L) }
        assertThrows(Stage5ValidationException::class.java) { validateSyncMetadataTree(orphan) }
    }

    @Test fun PhotoFreeSidecarCannotClaimNonzeroPhotoBytes() {
        val impossible = pending().apply {
            getAsJsonObject("pendingUploadPhotoSidecar").addProperty("totalPhotoBytes", 1L)
        }
        assertThrows(Stage5ValidationException::class.java) { validateSyncMetadataTree(impossible) }
    }

    private fun metadata() = JsonObject().apply {
        addProperty("schemaVersion", 2)
        addProperty("accountId", "synthetic-account")
        addProperty("backupRootId", "synthetic-root")
        addProperty("documentId", "00000000-0000-0000-0000-000000000001")
    }

    private fun pending() = metadata().apply {
        addProperty("pendingUploadReason", "MANUAL")
        addProperty("pendingUploadIntent", "AUTOMATIC_RETRY")
        addProperty("pendingUploadSourceUri", "content://synthetic/document")
        addProperty("pendingUploadGeneration", 1L)
        add("pendingUploadPhotoSidecar", JsonObject().apply {
            addProperty("schemaVersion", 3)
            addProperty("contentId", "a".repeat(64))
            addProperty("manifestSha256", "b".repeat(64))
            addProperty("snapshotSha256", "c".repeat(64))
            addProperty("snapshotByteCount", 1L)
            addProperty("photoCount", 0)
            addProperty("totalPhotoBytes", 0L)
        })
    }
}
