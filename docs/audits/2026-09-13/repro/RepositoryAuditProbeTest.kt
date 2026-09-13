package com.example.myapplication.audit
import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.*
import com.example.myapplication.stage1.*
import com.example.myapplication.stage5.validateSnapshot
import com.example.myapplication.stage7.BitmapBudgetPolicy
import com.example.myapplication.stage8.*
import com.example.myapplication.stage9b.testAnnotationReducer
import org.junit.Assert.*
import org.junit.Test

/** Negative audit probes: these assert the desired contract, not the existing defect. */
class RepositoryAuditProbeTest {
    @Test fun recalibrationMustUpdateExistingDimensionLabels() {
        val vm = BlueprintViewModel()
        vm.pageMeasurements[0] = mutableStateListOf()
        val reducer = testAnnotationReducer(vm)
        assertTrue(reducer.setScale(0, PageScale(12f)).changed)
        val measured = Measurement(Point(0f, 0f), Point(1f, 0f), formatFeet(144f / 12f))
        assertTrue(reducer.addMeasurement(0, measured).changed)
        assertTrue(reducer.setScale(0, PageScale(24f)).changed)
        assertEquals("144 source points at 24 points/foot must be six feet", formatFeet(144f / 24f), vm.pageMeasurements[0]!!.single().text)
    }
    @Test fun acceptedScaleMustBeSafeForTheProductionMeasurementFormatter() {
        val scale = Float.MIN_VALUE
        validateSnapshot(DocumentSnapshotV1(schemaVersion = DOCUMENT_SNAPSHOT_V1_SCHEMA_VERSION,
            snapshotRevision = 0L, source = DocumentSourceIdentityV1("content://audit/scale"),
            pages = mapOf(0 to PageSnapshotV1(scale = PageScaleSnapshotV1(scale)))))
        val vm = BlueprintViewModel()
        vm.pageMeasurements[0] = mutableStateListOf()
        assertTrue(testAnnotationReducer(vm).setScale(0, PageScale(scale)).changed)
        formatFeet(144f / vm.pageScales[0]!!.pointsPerFoot)
    }
    @Test fun fortyEightByThirtySixInchSheetExceedsRasterBudget() {
        val plan = requireNotNull(BitmapBudgetPolicy.pdfRenderPlan(3456, 2592, scaleFactor = 1))
        println("AUDIT raster=${plan.width}x${plan.height}; physical=3456x2592 points")
        assertTrue(plan.width < 3456 && plan.height < 2592)
    }
}
