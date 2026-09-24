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
    val shapes: List<Shape>
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
                paths, measurements, notes, photoPins, shapes, photoSessionToken,
                stage7Worker, photoAssets)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        SafeDiagnostics.error(DiagnosticEvent.EXPORT_ACTIVITY, error = error)
        false
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
        val totalAppendixPhotos = photoPins.sumOf { it.imageFileNames.size }.coerceAtLeast(1)

                // Add pages for each pin's photos
        var pdfPageNumber = 2
        photoPins.forEachIndexed { pinIndex, pin ->
            exportContext.ensureActive()
            if (pin.imageFileNames.isNotEmpty()) {
                pin.imageFileNames.chunked(appendixLayout.capacity).forEachIndexed { partIndex, photoFiles ->
                val pageInfo = PdfDocument.PageInfo.Builder(photoPageWidth, photoPageHeight, pdfPageNumber).create()
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
                val continuation = if (partIndex == 0) "" else " (continued ${partIndex + 1})"
                photoCanvas.drawText("Pin ${pinIndex + 1} - Photos$continuation", margin, headerY, headerPaint)

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
                                    com.example.myapplication.stage8.PhotoAnnotationScene.items(pin, fileName),
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
                pdfPageNumber++
                }
            } else {
                Unit
            }
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
