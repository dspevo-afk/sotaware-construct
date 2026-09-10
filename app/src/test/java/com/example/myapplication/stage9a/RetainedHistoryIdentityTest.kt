package com.example.myapplication.stage9a

import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.stage1.DocumentSourceIdentityV1
import com.example.myapplication.stage2.DocumentAssociation
import com.example.myapplication.stage2.DocumentId
import com.example.myapplication.stage2.SourceFingerprint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedHistoryIdentityTest {
    @Test fun onlyIdenticalVerifiedDocumentCanReuseTheRetainedViewModelHistoryOwner() {
        val vm = BlueprintViewModel()
        val first = DocumentAssociation(
            DocumentId.new(), DocumentSourceIdentityV1("content://stage9a/history", "fixture.pdf"),
            SourceFingerprint.fromBytes("first revision".toByteArray())
        )
        assertFalse(vm.canRetainHistoryForTarget(first))
        vm.recordHistoryDocument(first)
        assertTrue(vm.canRetainHistoryForTarget(first.copy()))
        assertFalse(vm.canRetainHistoryForTarget(first.copy(documentId = DocumentId.new())))
        assertFalse(vm.canRetainHistoryForTarget(first.copy(source = first.source.copy(sourceUri = "content://other"))))
        assertFalse(vm.canRetainHistoryForTarget(first.copy(sourceFingerprint = SourceFingerprint.fromBytes("new revision".toByteArray()))))
        assertFalse(vm.canRetainHistoryForTarget(first.copy(sourceFingerprint = null)))
        vm.clearSession()
        assertFalse(vm.canRetainHistoryForTarget(first))
    }
}
