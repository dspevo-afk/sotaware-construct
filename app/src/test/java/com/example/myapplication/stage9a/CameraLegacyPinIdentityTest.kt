package com.example.myapplication.stage9a

import com.example.myapplication.stage1.*
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import com.example.myapplication.stage5.*
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraLegacyPinIdentityTest {
    @Test fun cameraAdmissionPreservesCanonicalLegacyPinIdentityInsteadOfRequiringUuid() {
        val legacyId = "legacy-import-pin-42"
        val source = DocumentSourceIdentityV1("content://stage9a/legacy-pin", "fixture.pdf")
        validateSnapshot(DocumentSnapshotV1(1,0,source,mapOf(0 to PageSnapshotV1(
            photoPins=listOf(PhotoPinSnapshotV1(.2f,.3f,legacyId,emptyList(),emptyMap(),emptyMap()))
        ))))
        val request = CameraCaptureOperationRequest(
            processInstanceId = UUID.randomUUID().toString(),
            documentId = DocumentId.new(),
            sourceUri = source.sourceUri,
            sourceFingerprint = SourceFingerprint.fromBytes("fixture source".toByteArray()),
            sessionGeneration = 1,
            pageIndex = 0,
            pinId = legacyId
        )
        assertEquals(legacyId, request.pinId)
    }
}
