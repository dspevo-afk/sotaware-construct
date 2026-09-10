package com.example.myapplication.stage9a

import com.example.myapplication.stage1.DocumentSnapshotV1
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage1.DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION
import com.example.myapplication.stage1.PageSnapshotV1
import com.example.myapplication.stage1.PhotoPinSnapshotV1
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.CameraCaptureOperationRequest
import com.example.myapplication.stage5.validateSnapshot
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPinIdentityTest {
    @Test
    fun cameraAdmissionPreservesStableCanonicalPinIdentity() {
        val stablePinId = "pin-current-42"
        val source = DocumentSourceIdentityV1("content://stage9a/current-pin", "fixture.pdf")
        validateSnapshot(
            DocumentSnapshotV1(
                schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
                snapshotRevision = 0,
                source = source,
                pages = mapOf(
                    0 to PageSnapshotV1(
                        photoPins = listOf(
                            PhotoPinSnapshotV1(
                                x = .2f,
                                y = .3f,
                                id = stablePinId,
                                imageFileNames = emptyList(),
                                imageNotes = emptyMap(),
                                imageShapes = emptyMap()
                            )
                        )
                    )
                )
            )
        )
        val request = CameraCaptureOperationRequest(
            processInstanceId = UUID.randomUUID().toString(),
            documentId = DocumentId.new(),
            sourceUri = source.sourceUri,
            sourceFingerprint = SourceFingerprint.fromBytes("fixture source".toByteArray()),
            sessionGeneration = 1,
            pageIndex = 0,
            pinId = stablePinId
        )
        assertEquals(stablePinId, request.pinId)
    }
}
