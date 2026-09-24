package com.example.myapplication.stage8

import com.example.myapplication.*

fun PageItem.appearance(): DrawingToolStyle = when (this) {
    is PageItem.Path -> DrawingToolStyle(colorArgb = data.colorArgb, width = data.strokeWidthRatio)
    is PageItem.Measure -> DrawingToolStyle(colorArgb = data.colorArgb, width = data.strokeWidthRatio)
    is PageItem.NoteItem -> DrawingToolStyle(colorArgb = data.colorArgb, fontSize = data.fontSizeRatio, bold = data.isBold)
    is PageItem.ShapeItem -> DrawingToolStyle(colorArgb = data.colorArgb, width = data.strokeWidthRatio, shape = data.type, filled = data.isFilled)
    is PageItem.PhotoPinItem -> error("A photo pin has no drawing style")
}

fun PageItem.appearanceMode(): ToolMode = when (this) {
    is PageItem.NoteItem -> ToolMode.NOTE
    is PageItem.ShapeItem -> ToolMode.SHAPE
    is PageItem.Path -> if (data.isHighlighter) ToolMode.HIGHLIGHTER else ToolMode.PEN
    is PageItem.Measure -> ToolMode.MEASURE
    is PageItem.PhotoPinItem -> ToolMode.PHOTO
}

fun AnnotationReducer.changeAppearance(page: Int, item: PageItem, style: DrawingToolStyle): AnnotationReducer.Result = when (item) {
    is PageItem.Path -> updatePdfPath(page, item.data, item.data.copy(colorArgb = style.colorArgb, strokeWidthRatio = style.width))
    is PageItem.Measure -> updateMeasurement(page, item.data, item.data.copy(colorArgb = style.colorArgb, strokeWidthRatio = style.width))
    is PageItem.NoteItem -> updatePdfNote(page, item.data, item.data.copy(colorArgb = style.colorArgb, fontSizeRatio = style.fontSize, isBold = style.bold))
    is PageItem.ShapeItem -> updatePdfShape(page, item.data, item.data.copy(colorArgb = style.colorArgb, strokeWidthRatio = style.width, type = style.shape, isFilled = style.filled))
    is PageItem.PhotoPinItem -> AnnotationReducer.Result.Rejected
}

fun AnnotationReducer.changeImageAppearance(page: Int, pin: PhotoPin, file: String, item: PageItem, style: DrawingToolStyle): AnnotationReducer.Result {
    val replacement = when (item) {
        is PageItem.Path -> {
            if (item.data !in pin.imagePaths[file].orEmpty()) return AnnotationReducer.Result.Rejected
            pin.copy(imagePaths = pin.imagePaths + (file to pin.imagePaths[file].orEmpty().map {
                if (it.id == item.data.id) it.copy(colorArgb = style.colorArgb, strokeWidthRatio = style.width) else it }))
        }
        is PageItem.Measure -> {
            if (item.data !in pin.imageMeasurements[file].orEmpty()) return AnnotationReducer.Result.Rejected
            pin.copy(imageMeasurements = pin.imageMeasurements + (file to pin.imageMeasurements[file].orEmpty().map {
                if (it.id == item.data.id) it.copy(colorArgb = style.colorArgb, strokeWidthRatio = style.width) else it }))
        }
        is PageItem.NoteItem -> {
            if (item.data !in pin.imageNotes[file].orEmpty()) return AnnotationReducer.Result.Rejected
            pin.copy(imageNotes = pin.imageNotes + (file to pin.imageNotes[file].orEmpty().map {
                if (it.id == item.data.id) it.copy(colorArgb = style.colorArgb, fontSizeRatio = style.fontSize, isBold = style.bold) else it }))
        }
        is PageItem.ShapeItem -> {
            if (item.data !in pin.imageShapes[file].orEmpty()) return AnnotationReducer.Result.Rejected
            pin.copy(imageShapes = pin.imageShapes + (file to pin.imageShapes[file].orEmpty().map {
                if (it.id == item.data.id) it.copy(colorArgb = style.colorArgb, strokeWidthRatio = style.width, type = style.shape, isFilled = style.filled) else it }))
        }
        is PageItem.PhotoPinItem -> return AnnotationReducer.Result.Rejected
    }
    return updatePhotoPin(page, pin, replacement)
}
