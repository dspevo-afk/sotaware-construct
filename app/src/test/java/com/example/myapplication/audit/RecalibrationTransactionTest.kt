package com.example.myapplication.audit

import androidx.compose.runtime.mutableStateListOf
import com.example.myapplication.*
import com.example.myapplication.stage8.*
import com.example.myapplication.stage9b.testAnnotationReducer
import org.junit.Assert.*
import org.junit.Test

class RecalibrationTransactionTest {
    private fun model() = BlueprintViewModel().apply {
        pageMeasurements[0] = mutableStateListOf()
        pagePhotoPins[0] = mutableStateListOf()
    }
    @Test fun pageRecalibrationUpdatesAllLabelsAndUndoRedoAsOneChange() {
        val vm = model(); val reducer = testAnnotationReducer(vm)
        assertTrue(reducer.setScale(0, PageScale(12f)).changed)
        val straight = Measurement(Point(0f, 0f), Point(1f, 0f), "old")
        val polyline = Measurement(Point(0f, 0f), Point(1f, 1f), "old polyline", intermediatePoints = listOf(Point(1f, 0f)))
        assertTrue(reducer.addMeasurement(0, straight).changed)
        assertTrue(reducer.addMeasurement(0, polyline).changed)
        val before = vm.pageMeasurements[0]!!.toList()
        assertTrue(reducer.setScale(0, PageScale(24f), AnnotationSize(144f, 72f)).changed)
        assertEquals(listOf("6' 0\"", "9' 0\""), vm.pageMeasurements[0]!!.map { it.text })
        assertEquals(before.map { it.id }, vm.pageMeasurements[0]!!.map { it.id })
        assertEquals(polyline.vertices, vm.pageMeasurements[0]!![1].vertices)
        assertTrue(reducer.undo(0).changed)
        assertEquals(PageScale(12f), vm.pageScales[0]); assertEquals(before, vm.pageMeasurements[0])
        assertTrue(reducer.redo(0).changed)
        assertEquals(PageScale(24f), vm.pageScales[0]); assertEquals("6' 0\"", vm.pageMeasurements[0]!![0].text)
    }
    @Test fun missingGeometryAndOverflowRejectWithoutPartialStateOrHistory() {
        val vm = model(); val reducer = testAnnotationReducer(vm)
        reducer.setScale(0, PageScale(12f))
        val measurement = Measurement(Point(0f,0f), Point(1f,0f), "12 feet")
        reducer.addMeasurement(0, measurement)
        assertEquals(AnnotationReducer.Result.Rejected, reducer.setScale(0, PageScale(24f)))
        assertEquals(AnnotationReducer.Result.Rejected, reducer.setScale(0, PageScale(Float.MIN_VALUE), AnnotationSize(144f,72f)))
        assertEquals(PageScale(12f), vm.pageScales[0]); assertEquals(listOf(measurement), vm.pageMeasurements[0])
        assertTrue(reducer.undo(0).changed); assertTrue(vm.pageMeasurements[0]!!.isEmpty())
    }
    @Test fun photoRecalibrationUsesOrientedAspectAndIsUndoable() {
        val vm = model(); val reducer = testAnnotationReducer(vm)
        val measurement = Measurement(Point(0f,0f), Point(0f,1f), "old")
        val pin = PhotoPin(.5f,.5f, imageFileNames = listOf("photo.jpg"),
            imageMeasurements = mapOf("photo.jpg" to listOf(measurement)), imageScales = mapOf("photo.jpg" to PageScale(.5f)))
        assertTrue(reducer.addPhotoPin(0,pin).changed)
        assertTrue(reducer.setImageScale(0,pin,"photo.jpg",PageScale(1f),2f).changed)
        assertEquals("2' 0\"",vm.pagePhotoPins[0]!!.single().imageMeasurements["photo.jpg"]!!.single().text)
        assertTrue(reducer.undo(0).changed);assertEquals(pin,vm.pagePhotoPins[0]!!.single())
        assertTrue(reducer.redo(0).changed)
        assertEquals(PageScale(1f),vm.pagePhotoPins[0]!!.single().imageScales["photo.jpg"])
    }
}
