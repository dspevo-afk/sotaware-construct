package com.example.myapplication.stage8

import com.example.myapplication.*

/** Back-to-front scene shared by photo display, export and reverse-order selection. */
object PhotoAnnotationScene {
    fun items(paths: List<DrawnPath>, measurements: List<Measurement>, notes: List<Note>, shapes: List<Shape>): List<PageItem> = buildList {
        paths.forEach { add(PageItem.Path(it)) }
        measurements.forEach { add(PageItem.Measure(it)) }
        notes.forEachIndexed { index, note -> add(PageItem.NoteItem(note, index)) }
        shapes.forEach { add(PageItem.ShapeItem(it)) }
    }
    fun items(pin: PhotoPin, file: String): List<PageItem> = items(pin.imagePaths[file].orEmpty(),
        pin.imageMeasurements[file].orEmpty(), pin.imageNotes[file].orEmpty(), pin.imageShapes[file].orEmpty())
    fun hit(scene: List<PageItem>, contains: (PageItem) -> Boolean): PageItem? = scene.lastOrNull(contains)
}
