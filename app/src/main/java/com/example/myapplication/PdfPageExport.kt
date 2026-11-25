package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage6.PdfExportTemporaryOwner
import com.example.myapplication.stage6.withVerifiedPdfSource
import com.example.myapplication.stage7.BitmapBudgetPolicy
import com.example.myapplication.stage7.Stage7ResourceOwner
import com.example.myapplication.stage7.Stage7WorkerResourceBoundary
import com.example.myapplication.stage8.PhotoAnnotationScene
import com.example.myapplication.stage9.DiagnosticEvent
import com.example.myapplication.stage9.SafeDiagnostics
import com.example.myapplication.stage9b.AnnotationCanvasRendering
import com.example.myapplication.stage9b.PhotoAssetSet
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.UUID

data class PdfExportData(
    val sessionToken: DocumentSessionToken,
    val sourceUri: Uri,
    val pageIndex: Int,
    val paths: List<DrawnPath>,
    val measurements: List<Measurement>,
    val notes: List<Note>,
    val photoPins: List<PhotoPin>,
    val shapes: List<Shape>,
    val subDrawings: List<SubDrawing> = emptyList(),
    // Sheet number as the sheet list shows it, so an exported crop badge carries
    // the same label the app draws. Null exports the page number instead.
    val sheetLabel: String? = null
)

internal suspend fun exportPageAsPdf(
    context: Context,
    outputUri: Uri,
    sourceUri: Uri,
    pageIndex: Int,
    paths: List<DrawnPath>,
    measurements: List<Measurement>,
    notes: List<Note>,
    photoPins: List<PhotoPin>,
    shapes: List<Shape> = emptyList(),
    subDrawings: List<SubDrawing> = emptyList(),
    sheetLabel: String? = null,
    photoSessionToken: DocumentSessionToken,
    stage7Worker: Stage7WorkerResourceBoundary = Stage7WorkerResourceBoundary(),
    photoAssets: PhotoAssetSet? = null,
    temporaryOwner: PdfExportTemporaryOwner? = null,
    exportRequestId: String = UUID.randomUUID().toString()
): Boolean = stage7Worker.withWorker {
    try {
        require(sourceUri.toString() == photoSessionToken.sourceUri) {
            "export source does not match its captured document session"
        }
        val expected = requireNotNull(photoSessionToken.sourceFingerprint) {
            "PDF export requires a verified source revision"
        }
        withVerifiedPdfSource(temporaryOwner ?: PdfExportTemporaryOwner(context.cacheDir), exportRequestId, expected,
            openSource = { context.contentResolver.openInputStream(sourceUri) }
        ) { verifiedSource ->
            renderVerifiedPageAsPdf(context, outputUri, verifiedSource, pageIndex,
                paths, measurements, notes, photoPins, shapes, subDrawings, sheetLabel, photoSessionToken,
                stage7Worker, photoAssets)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        SafeDiagnostics.error(DiagnosticEvent.EXPORT_ACTIVITY, error = error)
        false
    }
}

/** Sheet number for export titles and badges: the sheet list's label, else the page number. */
private fun exportSheetLabel(sheetLabel: String?, pageIndex: Int): String =
    sheetLabel?.takeIf { it.isNotBlank() } ?: "SHEET ${pageIndex + 1}"

/** Title line of one sub-drawing's exported page, like its badge: "E1.0 - 104". */
private fun subDrawingPageTitle(sheetLabel: String, sub: SubDrawing): String {
    val name = SubDrawingLabels.typedName(sub)
    return if (name == null) sheetLabel else "$sheetLabel - $name"
}

/** Title line of one photo appendix page; sub-drawing pins say which crop they belong to. */
private fun photoAppendixTitle(
    sheetLabel: String,
    subOrdinal: Int?,
    subName: String?,
    pinNumber: Int,
    partIndex: Int
): String {
    val continuation = if (partIndex == 0) "" else " (continued ${partIndex + 1})"
    val prefix = if (subOrdinal == null) "Pin $pinNumber"
    else {
        val owner = if (subName != null) "$sheetLabel - $subName" else "$sheetLabel - Sub-drawing $subOrdinal"
        "Sub-drawing $owner - Pin $pinNumber"
    }
    return "$prefix - Photos$continuation"
}

/**
 * Draws one sub-drawing's own markups onto its export canvas. Coordinates are the
 * sub-drawing's surface-normalized points (the same frame every other page uses),
 * so lines, highlighter strokes, measurements, notes and shapes land exactly where
 * they were drawn inside the crop; measurement labels use the sub-drawing's scale.
 */
private fun drawSubDrawingMarkup(
    canvas: Canvas,
    sub: SubDrawing,
    widthPx: Int,
    heightPx: Int,
    ensureActive: () -> Unit
) {
    val w = widthPx.toFloat()
    val h = heightPx.toFloat()
    val maxDim = maxOf(w, h)
    val paint = android.graphics.Paint().apply { isAntiAlias = true }

    sub.paths.forEach { pathData ->
        ensureActive()
        if (pathData.points.size > 1) {
            paint.color = pathData.colorArgb
            paint.strokeWidth = pathData.strokeWidthRatio * maxDim
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.strokeJoin = android.graphics.Paint.Join.ROUND
            paint.alpha = if (pathData.isHighlighter) 100 else 255
            val path = android.graphics.Path()
            path.moveTo(pathData.points[0].x * w, pathData.points[0].y * h)
            for (i in 1 until pathData.points.size) path.lineTo(pathData.points[i].x * w, pathData.points[i].y * h)
            canvas.drawPath(path, paint)
        }
    }

    sub.measurements.forEach { m ->
        ensureActive()
        paint.color = m.colorArgb
        paint.strokeWidth = (m.strokeWidthRatio * maxDim).coerceAtLeast(1f)
        paint.alpha = 255
        val m1x = m.p1.x * w; val m1y = m.p1.y * h
        val m2x = m.p2.x * w; val m2y = m.p2.y * h
        m.vertices.zipWithNext().forEach { (a, b) -> canvas.drawLine(a.x * w, a.y * h, b.x * w, b.y * h, paint) }
        val endpointRadius = (0.006f * maxDim).coerceAtLeast(1f)
        canvas.drawCircle(m1x, m1y, endpointRadius, paint)
        canvas.drawCircle(m2x, m2y, endpointRadius, paint)

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = (0.02f * h).coerceAtLeast(1f)
            isFakeBoldText = true
        }
        val bgPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            alpha = 180
        }
        val textWidth = textPaint.measureText(m.text)
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val midX = (m1x + m2x) / 2
        val midY = (m1y + m2y) / 2
        val padding = (0.01f * h).coerceAtLeast(1f)
        canvas.drawRect(midX - textWidth / 2 - padding, midY - textHeight / 2 - padding / 2,
            midX + textWidth / 2 + padding, midY + textHeight / 2 + padding / 2, bgPaint)
        canvas.drawText(m.text, midX - textWidth / 2, midY - (fontMetrics.ascent + fontMetrics.descent) / 2, textPaint)
    }

    sub.notes.forEach { n ->
        ensureActive()
        AnnotationCanvasRendering.drawNote(canvas, n, 0f, 0f, w, h, n.colorArgb)
    }

    // Photo pins made inside the crop: numbered pins on the sub-drawing's page.
    sub.photos.forEachIndexed { pinIndex, pin ->
        ensureActive()
        val pinRadius = (0.0125f * maxDim).coerceAtLeast(2f)
        val iconPaint = android.graphics.Paint().apply {
            color = 0xFF4CAF50.toInt()
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        val borderPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (0.0015f * maxDim).coerceAtLeast(1f)
            isAntiAlias = true
        }
        val pinX = pin.x * w; val pinY = pin.y * h
        canvas.drawCircle(pinX, pinY, pinRadius, iconPaint)
        canvas.drawCircle(pinX, pinY, pinRadius, borderPaint)
        val numberPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = (0.012f * h).coerceAtLeast(1f)
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val numberMetrics = numberPaint.fontMetrics
        canvas.drawText("${pinIndex + 1}", pinX, pinY - (numberMetrics.ascent + numberMetrics.descent) / 2, numberPaint)
    }

    sub.shapes.forEach { shape ->
        ensureActive()
        AnnotationCanvasRendering.drawShape(canvas, shape, 0f, 0f, w, h)
    }
}

private suspend fun renderVerifiedPageAsPdf(
    context: Context,
    outputUri: Uri,
    verifiedSource: File,
    pageIndex: Int,
    paths: List<DrawnPath>,
    measurements: List<Measurement>,
    notes: List<Note>,
    photoPins: List<PhotoPin>,
    shapes: List<Shape> = emptyList(),
    subDrawings: List<SubDrawing> = emptyList(),
    sheetLabel: String? = null,
    photoSessionToken: DocumentSessionToken,
    stage7Worker: Stage7WorkerResourceBoundary,
    photoAssets: PhotoAssetSet?
): Boolean = stage7Worker.withWorker {
    try {
        currentCoroutineContext().ensureActive()
        val pfd = ParcelFileDescriptor.open(verifiedSource, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = PdfRenderer(pfd)
            try {
                val page = renderer.openPage(pageIndex)
                try {

                    // Get page dimensions
                    val pageWidth = page.width
                    val pageHeight = page.height

        SafeDiagnostics.debug(DiagnosticEvent.EXPORT_ACTIVITY)

        // Store original page dimensions for photo pages
        val originalPageWidth = page.width
        val originalPageHeight = page.height

        val bitmapPlan = BitmapBudgetPolicy.pdfRenderPlan(
            pageWidthPx = pageWidth,
            pageHeightPx = pageHeight,
            scaleFactor = 1
        ) ?: throw IOException("PDF export page exceeds the bitmap budget")
        val bitmapOwner = Stage7ResourceOwner<Bitmap>(::recycleBitmap)
        val bitmap = try {
            // Register immediately after allocation. The owner exists before
            // allocation so a failed registration cannot leak the bitmap.
            bitmapOwner.ownCreated {
                Bitmap.createBitmap(bitmapPlan.width, bitmapPlan.height, Bitmap.Config.ARGB_8888)
            }
        } catch (error: Throwable) {
            bitmapOwner.close()
            throw error
        }
        try {
            val actual = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                BitmapBudgetPolicy.actualAllocationPlan(
                    widthPx = bitmap.width,
                    heightPx = bitmap.height,
                    actualAllocationBytes = actualBitmapAllocationBytes(bitmap)
                )
            } else {
                null
            }
            if (actual == null || bitmap.width != bitmapPlan.width || bitmap.height != bitmapPlan.height) {
                throw IOException("PDF export bitmap allocation exceeds the bitmap budget")
            }
        } catch (error: Throwable) {
            bitmapOwner.close()
            throw error
        }
        try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        // Render PDF at original resolution (no matrix = 1:1)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        currentCoroutineContext().ensureActive()
        val exportContext = currentCoroutineContext()
        val markupScaleX = bitmap.width.toFloat() / pageWidth.toFloat()
        val markupScaleY = bitmap.height.toFloat() / pageHeight.toFloat()
        // PdfRenderer scales the page to the bounded bitmap. Apply the same
        // explicit transform to page-unit markup so it stays aligned when a
        // large export page is reduced.
        canvas.save()
        canvas.scale(markupScaleX, markupScaleY)

        val paint = android.graphics.Paint().apply { isAntiAlias = true }

        // Draw all markups from normalized visible-surface coordinates. The
        // canvas scale only maps source PDF points to the bounded export bitmap.
        val exportMaxDim = maxOf(pageWidth, pageHeight).toFloat()
        paths.forEach { pathData ->
            exportContext.ensureActive()
            if (pathData.points.size > 1) {
                paint.color = pathData.colorArgb
                paint.strokeWidth = pathData.strokeWidthRatio * exportMaxDim
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.strokeJoin = android.graphics.Paint.Join.ROUND
                if (pathData.isHighlighter) paint.alpha = 100

                val path = android.graphics.Path()
                path.moveTo(pathData.points[0].x * pageWidth, pathData.points[0].y * pageHeight)
                for (i in 1 until pathData.points.size) path.lineTo(pathData.points[i].x * pageWidth, pathData.points[i].y * pageHeight)
                canvas.drawPath(path, paint)
            }
        }

        measurements.forEach { m ->
            exportContext.ensureActive()
            paint.color = m.colorArgb
            paint.strokeWidth = (m.strokeWidthRatio * exportMaxDim).coerceAtLeast(1f)
            paint.alpha = 255
            val m1x = m.p1.x * pageWidth; val m1y = m.p1.y * pageHeight
            val m2x = m.p2.x * pageWidth; val m2y = m.p2.y * pageHeight
            m.vertices.zipWithNext().forEach { (a, b) -> canvas.drawLine(a.x * pageWidth, a.y * pageHeight, b.x * pageWidth, b.y * pageHeight, paint) }
            val endpointRadius = (0.006f * exportMaxDim).coerceAtLeast(1f)
            canvas.drawCircle(m1x, m1y, endpointRadius, paint)
            canvas.drawCircle(m2x, m2y, endpointRadius, paint)

            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = (0.02f * pageHeight).coerceAtLeast(1f)
                isFakeBoldText = true
            }
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                alpha = 180
            }

            val textWidth = textPaint.measureText(m.text)
            val fontMetrics = textPaint.fontMetrics
            val textHeight = fontMetrics.bottom - fontMetrics.top
            val midX = (m1x + m2x) / 2
            val midY = (m1y + m2y) / 2

            val padding = (0.01f * pageHeight).coerceAtLeast(1f)
            canvas.drawRect(midX - textWidth / 2 - padding, midY - textHeight / 2 - padding/2, midX + textWidth / 2 + padding, midY + textHeight / 2 + padding/2, bgPaint)
            canvas.drawText(m.text, midX - textWidth / 2, midY - (fontMetrics.ascent + fontMetrics.descent) / 2, textPaint)
        }

        notes.forEach { n ->
            exportContext.ensureActive()
            AnnotationCanvasRendering.drawNote(canvas, n, 0f, 0f,
                pageWidth.toFloat(), pageHeight.toFloat(), n.colorArgb)
        }

        // Draw photo pins with pin numbers
        photoPins.forEachIndexed { pinIndex, pin ->
            exportContext.ensureActive()
            val pinRadius = (0.0125f * exportMaxDim).coerceAtLeast(2f)
            val iconPaint = android.graphics.Paint().apply {
                color = 0xFF4CAF50.toInt()
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
            val borderPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = (0.0015f * exportMaxDim).coerceAtLeast(1f)
                isAntiAlias = true
            }

            // Draw circle for photo pin icon
            val pinX = pin.x * pageWidth; val pinY = pin.y * pageHeight
            canvas.drawCircle(pinX, pinY, pinRadius, iconPaint)
            canvas.drawCircle(pinX, pinY, pinRadius, borderPaint)

            // Draw pin number inside circle
            val numberPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = (0.012f * pageHeight).coerceAtLeast(1f)
                isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            val numberMetrics = numberPaint.fontMetrics
            canvas.drawText("${pinIndex + 1}", pinX, pinY - (numberMetrics.ascent + numberMetrics.descent) / 2, numberPaint)
        }

        // Draw shapes using ratio-based dimensions
        // Shapes store ratios relative to page size - draw at actual page dimensions
        val pageMaxDim = maxOf(pageWidth, pageHeight).toFloat()

        shapes.forEach { shape ->
            exportContext.ensureActive()
            AnnotationCanvasRendering.drawShape(canvas, shape, 0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
        }

        // Sub-drawing regions export as their faint palette highlight so a
        // shared page keeps the same crop markers the app shows.
        val exportSheet = exportSheetLabel(sheetLabel, pageIndex)
        subDrawings.forEach { sub ->
            exportContext.ensureActive()
            val color = SubDrawingPalette.colorArgb(sub.colorIndex)
            val fillPaint = android.graphics.Paint().apply {
                this.color = color
                alpha = 38
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }
            val strokePaint = android.graphics.Paint().apply {
                this.color = color
                alpha = 217
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = (0.0015f * exportMaxDim).coerceAtLeast(1f)
                isAntiAlias = true
            }
            canvas.drawRect(sub.left * pageWidth, sub.top * pageHeight, sub.right * pageWidth, sub.bottom * pageHeight, fillPaint)
            canvas.drawRect(sub.left * pageWidth, sub.top * pageHeight, sub.right * pageWidth, sub.bottom * pageHeight, strokePaint)
            // The same round badge the app shows: sheet number over the room name,
            // at the crop's own saved badge size so an export matches the sheet.
            SubDrawingBadge.draw(
                canvas = canvas,
                center = SubDrawingGeometry.badgeCenter(sub).let { android.graphics.PointF(it.x * pageWidth, it.y * pageHeight) },
                radiusPx = (sub.badgeRatio ?: SubDrawingGeometry.defaultBadgeRatio(sub)) * pageWidth,
                topText = exportSheet,
                bottomText = sub.name?.trim()?.takeIf { it.isNotEmpty() }.orEmpty()
            )
        }
        canvas.restore()

        // Create PDF document
        val pdfDocument = PdfDocument()
        try {

        // Page 1: Blueprint with markups
        val blueprintPageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val blueprintPage = pdfDocument.startPage(blueprintPageInfo)
        try {
            exportContext.ensureActive()
            blueprintPage.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()), null)
        } finally {
            pdfDocument.finishPage(blueprintPage)
        }

        // Use ORIGINAL (unscaled) page dimensions for photo pages
        // This ensures content appears at correct size when PDF is viewed/printed
        val photoPageWidth = originalPageWidth
        val photoPageHeight = originalPageHeight
        val appendixLayout = com.example.myapplication.stage6.PhotoAppendixLayout.create(photoPageWidth, photoPageHeight)
        val margin = appendixLayout.margin
        val contentWidth = appendixLayout.contentWidth
        val totalAppendixPhotos = (photoPins.sumOf { it.imageFileNames.size } +
            subDrawings.sumOf { sub -> sub.photos.sumOf { it.imageFileNames.size } }).coerceAtLeast(1)

        /**
         * One photo appendix page for [pin]'s [photoFiles]. [title] names the owner
         * (sheet pin, or "Sub-drawing E1.0 - 104 - Pin 2") so a reader can tell
         * which surface each photo belongs to. Photo markups are drawn over each
         * image exactly as on every other appendix page.
         */
        fun startPhotoPages(pin: PhotoPin, pinNumber: Int, title: (Int) -> String, pageNumberStart: Int): Int {
            var pageNumber = pageNumberStart
            if (pin.imageFileNames.isEmpty()) return pageNumber
            pin.imageFileNames.chunked(appendixLayout.capacity).forEachIndexed { partIndex, photoFiles ->
                exportContext.ensureActive()
                val pageInfo = PdfDocument.PageInfo.Builder(photoPageWidth, photoPageHeight, pageNumber).create()
                val photoPage = pdfDocument.startPage(pageInfo)
                try {
                    exportContext.ensureActive()
                    val photoCanvas = photoPage.canvas
                    photoCanvas.drawColor(android.graphics.Color.WHITE)

                    // Draw header - scale text size based on page size
                    val headerTextSize = appendixLayout.headerSize
                    val headerPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = headerTextSize
                        isFakeBoldText = true
                    }
                    val headerY = margin + headerTextSize
                    photoCanvas.drawText(title(partIndex), margin, headerY, headerPaint)

                    // Calculate layout for images
                    val imageCount = photoFiles.size
                    val imagesPerRow = if (imageCount <= 1) 1 else appendixLayout.columns
                    val rows = (imageCount + imagesPerRow - 1) / imagesPerRow
                    val imageWidth = ((contentWidth - appendixLayout.gap * (imagesPerRow - 1)) / imagesPerRow).toInt().coerceAtLeast(1)
                    val maxImageHeight = ((appendixLayout.availableHeight - appendixLayout.gap * (rows - 1)) / rows).toInt().coerceAtLeast(1)
                    val decodeSize = com.example.myapplication.stage6.photoAppendixDecodeSize(imageWidth, maxImageHeight, totalAppendixPhotos)

                    var currentY = headerY + margin
                    var currentX = margin.toFloat()
                    var imagesInRow = 0

                    photoFiles.forEach { fileName ->
                        exportContext.ensureActive()
                        val photoBytes = try {
                            if (photoAssets == null) photoBytesFor(context, photoSessionToken, fileName)
                            else {
                                val asset = photoAssets[fileName] ?: throw IOException("Required photo is not retained")
                                com.example.myapplication.stage9b.photoAssetBytesForValidation(asset).also { bytes ->
                                    check(com.example.myapplication.stage5.sha256Hex(bytes) == asset.descriptor.sha256) {
                                        "Retained photo content changed"
                                    }
                                }
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            throw IOException("Required photo bytes are unavailable: $fileName", error)
                        }
                        if (photoBytes == null) {
                            throw IOException("Required photo bytes are unavailable: $fileName")
                        }
                        val photoOwner = decodePhotoBitmapWithExif(
                            photoBytes = photoBytes,
                            viewportWidthPx = decodeSize.first,
                            viewportHeightPx = decodeSize.second
                        ) ?: throw IOException("Required photo could not be decoded within the bitmap budget: $fileName")
                        try {
                            val rotatedBitmap = photoOwner.value
                            val fitScale = minOf(
                                imageWidth.toDouble() / rotatedBitmap.width.toDouble(),
                                maxImageHeight.toDouble() / rotatedBitmap.height.toDouble()
                            )
                            if (!fitScale.isFinite() || fitScale <= 0.0 || imageWidth <= 0 || maxImageHeight <= 0) {
                                throw IOException("Photo export layout is invalid: $fileName")
                            }
                            val imgWidth = (rotatedBitmap.width.toDouble() * fitScale)
                                .toLong()
                                .coerceIn(1L, imageWidth.toLong())
                                .toInt()
                            val imgHeight = (rotatedBitmap.height.toDouble() * fitScale)
                                .toLong()
                                .coerceIn(1L, maxImageHeight.toLong())
                                .toInt()

                            // Draw directly into the PDF canvas. This preserves
                            // the existing relative annotation coordinates while
                            // avoiding a second full-sized scaled bitmap.
                            photoCanvas.drawBitmap(
                                rotatedBitmap,
                                null,
                                RectF(
                                    currentX,
                                    currentY,
                                    currentX + imgWidth,
                                    currentY + imgHeight
                                ),
                                null
                            )
                            exportContext.ensureActive()

                            AnnotationCanvasRendering.drawPhotoScene(photoCanvas,
                                PhotoAnnotationScene.items(pin, fileName),
                                currentX, currentY, imgWidth.toFloat(), imgHeight.toFloat(), exportContext::ensureActive)

                            imagesInRow++
                            if (imagesInRow >= imagesPerRow) {
                                imagesInRow = 0
                                currentX = margin.toFloat()
                                currentY += maxImageHeight + appendixLayout.gap
                            } else {
                                currentX += imageWidth + appendixLayout.gap
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            throw IOException("Required photo export failed: $fileName", e)
                            } finally {
                                photoOwner.close()
                            }
                    }

                } finally {
                    pdfDocument.finishPage(photoPage)
                }
                pageNumber++
            }
            return pageNumber
        }

        // Sub-drawing pages follow the sheet page: each crop on its own page,
        // rendered sharp from the source PDF (never stretched from the page
        // bitmap), with the crop's own markups drawn on it. Its photo pins'
        // photo pages follow directly, labelled with the crop they belong to,
        // so "sheet page, its sub-drawings and their photos" reads in order.
        // A crop with nothing inside still gets its page: it is the enlarged
        // field view. One crop bitmap at a time keeps memory flat no matter
        // how many crops or how large they are.
        var pdfPageNumber = 2
        val regionRenderer = PdfBitmapRenderer(context)
        // The verified source snapshot, not the live document Uri: every export
        // page (sheet and crops alike) renders from the same frozen bytes.
        val verifiedSourceUri = Uri.fromFile(verifiedSource)
        subDrawings.forEachIndexed { subIndex, sub ->
            exportContext.ensureActive()
            val cropPlan = BitmapBudgetPolicy.pdfRenderPlan(
                pageWidthPx = ((sub.right - sub.left) * pageWidth).toInt().coerceAtLeast(1),
                pageHeightPx = ((sub.bottom - sub.top) * pageHeight).toInt().coerceAtLeast(1),
                scaleFactor = 2,
                maxDimensionPx = SUB_DRAWING_EXPORT_MAX_DIMENSION_PX
            ) ?: throw IOException("PDF export sub-drawing crop exceeds the bitmap budget")
            val cropOwner = Stage7ResourceOwner<Bitmap>(::recycleBitmap)
            try {
                val cropBitmap = regionRenderer.renderPageRegionBitmap(
                    uri = verifiedSourceUri,
                    pageIndex = pageIndex,
                    left = sub.left,
                    top = sub.top,
                    right = sub.right,
                    bottom = sub.bottom,
                    onBitmapCreated = cropOwner::own,
                    viewportWidthPx = cropPlan.width,
                    viewportHeightPx = cropPlan.height,
                    // The plan's own dimensions are the target: no extra
                    // viewport quality multiplier on top of them.
                    viewportQualityMultiplier = 1.0
                ) ?: throw IOException("PDF export sub-drawing crop could not be rendered within the bitmap budget")
                val cropWidth = cropBitmap.width
                val cropHeight = cropBitmap.height
                val cropCanvas = Canvas(cropBitmap)
                drawSubDrawingMarkup(cropCanvas, sub, cropWidth, cropHeight, exportContext::ensureActive)

                val title = subDrawingPageTitle(exportSheet, sub)
                val titlePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = (0.03f * maxOf(cropWidth, cropHeight)).coerceAtLeast(12f)
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val titleY = margin + titlePaint.textSize
                cropCanvas.drawText(title, margin, titleY, titlePaint)

                val pageInfo = PdfDocument.PageInfo.Builder(cropWidth, cropHeight, pdfPageNumber).create()
                val subPage = pdfDocument.startPage(pageInfo)
                try {
                    exportContext.ensureActive()
                    subPage.canvas.drawBitmap(cropBitmap, 0f, 0f, null)
                } finally {
                    pdfDocument.finishPage(subPage)
                }
                pdfPageNumber++

                val subName = SubDrawingLabels.typedName(sub)
                sub.photos.forEachIndexed { pinIndex, pin ->
                    exportContext.ensureActive()
                    pdfPageNumber = startPhotoPages(
                        pin,
                        pinIndex + 1,
                        { partIndex -> photoAppendixTitle(exportSheet, subIndex + 1, subName, pinIndex + 1, partIndex) },
                        pdfPageNumber
                    )
                }
            } finally {
                cropOwner.close()
            }
        }

        // Add pages for each sheet-level pin's photos, after every sub-drawing page.
        photoPins.forEachIndexed { pinIndex, pin ->
            exportContext.ensureActive()
            pdfPageNumber = startPhotoPages(
                pin,
                pinIndex + 1,
                { partIndex -> photoAppendixTitle(exportSheet, null, null, pinIndex + 1, partIndex) },
                pdfPageNumber
            )
        }

        // Write PDF to output stream
        exportContext.ensureActive()
        val output = context.contentResolver.openOutputStream(outputUri)
            ?: return@withWorker false
        output.use { out ->
            exportContext.ensureActive()
            pdfDocument.writeTo(out)
        }
        true
        } finally {
            pdfDocument.close()
        }
        } finally {
            bitmapOwner.close()
        }
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } finally {
            pfd.close()
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        SafeDiagnostics.error(DiagnosticEvent.EXPORT_ACTIVITY, error = e)
        false
    }
}

/** Hard ceiling on one exported sub-drawing page's long edge, so many crops stay bounded. */
private const val SUB_DRAWING_EXPORT_MAX_DIMENSION_PX: Int = 4096
