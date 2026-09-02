package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.myapplication.stage3.DocumentSessionToken
import com.example.myapplication.stage7.OcrSessionResourceFactory
import com.example.myapplication.stage7.OcrSessionResourceGraph
import com.example.myapplication.stage7.OcrRecognitionTask
import com.example.myapplication.stage7.Stage7ResourceOwner
import com.example.myapplication.stage7.googleMlKitRecognitionTask
import com.example.myapplication.stage7.runOcrRecognitionTask
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** Opens one complete Android OCR resource graph for one full session token. */
class AndroidOcrSessionResourceFactory(
    private val context: Context,
    /** Injectable task adapter; JVM tests use [OcrRecognitionTask] fakes. */
    private val recognitionTaskFactory: (TextRecognizer, InputImage) -> OcrRecognitionTask<Text> =
        { recognizer, image ->
            googleMlKitRecognitionTask(recognizer.process(image))
        }
) : OcrSessionResourceFactory {
    override suspend fun open(token: DocumentSessionToken): OcrSessionResourceGraph {
        currentCoroutineContext().ensureActive()
        val uri = Uri.parse(token.sourceUri)
        var input: InputStream? = null
        var document: PDDocument? = null
        var renderer: PdfBitmapRenderer.Session? = null
        var recognizer: TextRecognizer? = null
        try {
            input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("PDF input stream unavailable")
            document = PDDocument.load(input)
            renderer = PdfBitmapRenderer(context).openSession(uri)
                ?: throw IOException("PDF renderer session unavailable")
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            currentCoroutineContext().ensureActive()
            return AndroidOcrSessionResourceGraph(
                document = requireNotNull(document),
                input = requireNotNull(input),
                renderer = requireNotNull(renderer),
                recognizer = requireNotNull(recognizer),
                recognitionTaskFactory = recognitionTaskFactory
            ).also {
                document = null
                input = null
                renderer = null
                recognizer = null
            }
        } catch (cancelled: CancellationException) {
            closeOpenedResources(
                recognizer = recognizer,
                renderer = renderer,
                document = document,
                input = input,
                primaryFailure = cancelled
            )
            throw cancelled
        } catch (error: Throwable) {
            closeOpenedResources(
                recognizer = recognizer,
                renderer = renderer,
                document = document,
                input = input,
                primaryFailure = error
            )
            throw error
        }
    }

    private fun closeOpenedResources(
        recognizer: TextRecognizer?,
        renderer: PdfBitmapRenderer.Session?,
        document: PDDocument?,
        input: InputStream?,
        primaryFailure: Throwable
    ) {
        listOf<Closeable?>(recognizer, renderer, document, input).forEach { resource ->
            if (resource == null) return@forEach
            try {
                resource.close()
            } catch (closeFailure: Throwable) {
                primaryFailure.addSuppressed(closeFailure)
            }
        }
    }
}

/** Android implementation of the shared PDFBox/PdfRenderer/ML Kit graph. */
private class AndroidOcrSessionResourceGraph(
    private val document: PDDocument,
    private val input: InputStream,
    private val renderer: PdfBitmapRenderer.Session,
    private val recognizer: TextRecognizer,
    private val recognitionTaskFactory: (TextRecognizer, InputImage) -> OcrRecognitionTask<Text>
) : OcrSessionResourceGraph {
    private val tag = "SOTA_OCR"

    override suspend fun pageCount(): Int {
        currentCoroutineContext().ensureActive()
        return document.numberOfPages
    }

    override suspend fun extractEmbeddedText(pageIndex: Int): List<OcrBox> {
        currentCoroutineContext().ensureActive()
        val boxes = ArrayList<OcrBox>()
        try {
            if (pageIndex < 0 || pageIndex >= document.numberOfPages) return emptyList()
            val page = document.getPage(pageIndex)
            val geometry = PdfPageGeometry(
                mediaBox = page.mediaBox.toPdfBox(),
                cropBox = page.cropBox.toPdfBox(),
                rotationDegrees = page.rotation
            )
            Log.d(
                tag,
                "PDFBox page=$pageIndex media=${geometry.mediaBox.width}x${geometry.mediaBox.height} " +
                    "crop=${geometry.visibleWidth}x${geometry.visibleHeight} " +
                    "rotation=${geometry.rotationDegrees}"
            )

            val wordPositions = ArrayList<Pair<String, PdfNormalizedRect>>()
            val stripper = object : PDFTextStripper() {
                private var currentWord = StringBuilder()
                private var wordLeft = Float.MAX_VALUE
                private var wordTop = Float.MAX_VALUE
                private var wordRight = 0f
                private var wordBottom = 0f
                private var currentWordGeometryInvalid = false
                private var lastFlowEnd = Float.NaN
                private var lastCrossStart = Float.NaN
                private var lastCrossExtent = 0f
                private var avgCharWidth = 0f
                private var charCount = 0

                override fun writeString(
                    text: String?,
                    textPositions: MutableList<TextPosition>?
                ) {
                    if (textPositions == null || textPositions.isEmpty()) return
                    for (pos in textPositions) {
                        val character = pos.unicode ?: continue
                        val positionGeometry = pdfBoxDisplayedTextPositionOrNull(pos, geometry)
                        if (positionGeometry == null) {
                            // A malformed nonblank position terminates and
                            // discards the in-progress word immediately. Do
                            // not let later valid positions inherit poisoned
                            // word, average-width, or grouping state.
                            if (character.isBlank()) {
                                saveCurrentWord(wordPositions, geometry)
                                clearLastPosition()
                            } else {
                                discardCurrentWordAndResetGrouping()
                            }
                            continue
                        }

                        if (character.isBlank()) {
                            saveCurrentWord(wordPositions, geometry)
                            clearLastPosition()
                            continue
                        }

                        // Update the running width only after the candidate
                        // has passed finite, bounded, in-page validation.
                        val nextCharCount = charCount + 1
                        avgCharWidth += (positionGeometry.advance - avgCharWidth) / nextCharCount
                        charCount = nextCharCount

                        val hasGap = if (lastFlowEnd.isFinite() && currentWord.isNotEmpty()) {
                            val flowGap = positionGeometry.flowSign *
                                (positionGeometry.flowStart - lastFlowEnd)
                            val crossShift = kotlin.math.abs(
                                positionGeometry.crossStart - lastCrossStart
                            )
                            flowGap > avgCharWidth * 1.5f ||
                                crossShift > maxOf(
                                    positionGeometry.crossExtent,
                                    lastCrossExtent
                                ) * 0.5f
                        } else {
                            false
                        }
                        if (hasGap) {
                            saveCurrentWord(wordPositions, geometry)
                        }

                        val charRect = positionGeometry.rectangle
                        if (currentWord.isEmpty()) {
                            wordLeft = charRect.left
                            wordTop = charRect.top
                            wordRight = charRect.right
                            wordBottom = charRect.bottom
                        } else if (!currentWordGeometryInvalid) {
                            wordLeft = minOf(wordLeft, charRect.left)
                            wordTop = minOf(wordTop, charRect.top)
                            wordRight = maxOf(wordRight, charRect.right)
                            wordBottom = maxOf(wordBottom, charRect.bottom)
                        }
                        currentWord.append(character)
                        if (!currentWordGeometryInvalid) {
                            lastFlowEnd = positionGeometry.flowEnd
                            lastCrossStart = positionGeometry.crossStart
                            lastCrossExtent = positionGeometry.crossExtent
                        } else {
                            clearLastPosition()
                        }
                    }
                }

                override fun endDocument(document: PDDocument?) {
                    saveCurrentWord(wordPositions, geometry)
                    super.endDocument(document)
                }

                private fun saveCurrentWord(
                    positions: MutableList<Pair<String, PdfNormalizedRect>>,
                    pageGeometry: PdfPageGeometry
                ) {
                    if (currentWord.isEmpty()) return
                    val word = currentWord.toString()
                    if (!currentWordGeometryInvalid) {
                        val left = minOf(wordLeft, wordRight)
                        val right = maxOf(wordLeft, wordRight)
                        val top = minOf(wordTop, wordBottom)
                        val bottom = maxOf(wordTop, wordBottom)
                        if (left.isFinite() && top.isFinite() &&
                            right.isFinite() && bottom.isFinite() &&
                            left < right && top < bottom
                        ) {
                            val rect = PdfCoordinateMapper.fromPdfBoxAlreadyDisplayedTopLeftRectOrNull(
                                displayedTopLeftRect = PdfTopLeftRect(
                                    left = left,
                                    top = top,
                                    right = right,
                                    bottom = bottom
                                ),
                                geometry = pageGeometry
                            )
                            if (rect != null) positions.add(word to rect)
                        }
                    }
                    currentWord.clear()
                    currentWordGeometryInvalid = false
                    wordLeft = Float.MAX_VALUE
                    wordTop = Float.MAX_VALUE
                    wordRight = 0f
                    wordBottom = 0f
                    clearLastPosition()
                }

                private fun clearLastPosition() {
                    lastFlowEnd = Float.NaN
                    lastCrossStart = Float.NaN
                    lastCrossExtent = 0f
                }

                private fun discardCurrentWordAndResetGrouping() {
                    currentWord.clear()
                    currentWordGeometryInvalid = false
                    wordLeft = Float.MAX_VALUE
                    wordTop = Float.MAX_VALUE
                    wordRight = 0f
                    wordBottom = 0f
                    avgCharWidth = 0f
                    charCount = 0
                    clearLastPosition()
                }
            }

            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(document)
            wordPositions.forEach { (word, rect) ->
                if (word.isNotBlank()) boxes.add(OcrBox(word, rect.toAndroidRectF()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(tag, "PDFBox extraction failed", error)
            // An ordinary PDFBox extraction failure selects the existing OCR
            // fallback; partial boxes never escape this failed operation.
            return emptyList()
        }
        currentCoroutineContext().ensureActive()
        return boxes
    }

    override suspend fun recognizePage(pageIndex: Int): List<OcrBox> {
        currentCoroutineContext().ensureActive()
        val bitmapOwner = Stage7ResourceOwner<Bitmap> { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        var primaryFailure: Throwable? = null
        return try {
            val bitmap = renderer.renderPageBitmap(
                pageIndex = pageIndex,
                scaleFactor = 4,
                onBitmapCreated = bitmapOwner::own
            ) ?: throw IOException("PDF page bitmap is unavailable")

            currentCoroutineContext().ensureActive()
            val bitmapWidth = bitmap.width
            val bitmapHeight = bitmap.height
            val result = try {
                runOcrRecognitionTask(
                    task = recognitionTaskFactory(
                        recognizer,
                        InputImage.fromBitmap(bitmap, 0)
                    ),
                    closeTransientOwners = bitmapOwner::close
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw IOException("OCR recognition failed", error)
            }
            currentCoroutineContext().ensureActive()
            val boxes = ArrayList<OcrBox>()
            for (block in result.textBlocks) {
                currentCoroutineContext().ensureActive()
                for (line in block.lines) {
                    currentCoroutineContext().ensureActive()
                    val lineText = line.text ?: continue
                    val lineBounds = line.boundingBox ?: continue
                    if (line.elements.isNotEmpty()) {
                        for (element in line.elements) {
                            currentCoroutineContext().ensureActive()
                            val text = element.text ?: continue
                            val bounds = element.boundingBox ?: continue
                            normalizedOcrBox(text, bounds, bitmapWidth, bitmapHeight)?.let { boxes += it }
                        }
                    } else {
                        val lineRect = PdfCoordinateMapper.normalizeBitmapRect(
                            lineBounds,
                            bitmapWidth,
                            bitmapHeight
                        ) ?: continue
                        val left = lineRect.left
                        val top = lineRect.top
                        val right = lineRect.right
                        val bottom = lineRect.bottom
                        val words = lineText.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val totalLength = lineText.length.coerceAtLeast(1).toFloat()
                        var searchIndex = 0
                        for (word in words) {
                            currentCoroutineContext().ensureActive()
                            val index = lineText.indexOf(word, searchIndex)
                            if (index < 0) continue
                            val startFraction = index / totalLength
                            val endFraction = (index + word.length) / totalLength
                            PdfCoordinateMapper.normalizedRectOrNull(
                                left = left + (right - left) * startFraction,
                                top = top,
                                right = left + (right - left) * endFraction,
                                bottom = bottom
                            )?.let { boxes += OcrBox(word, it.toAndroidRectF()) }
                            searchIndex = index + word.length
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            boxes
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            throw cancelled
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                bitmapOwner.close()
            } catch (closeFailure: Throwable) {
                if (primaryFailure != null) {
                    primaryFailure?.addSuppressed(closeFailure)
                } else {
                    throw closeFailure
                }
            }
        }
    }

    private fun normalizedOcrBox(
        text: String,
        bounds: android.graphics.Rect,
        width: Int,
        height: Int
    ): OcrBox? = PdfCoordinateMapper.normalizeBitmapRect(bounds, width, height)?.let {
        OcrBox(text, it.toAndroidRectF())
    }

    override fun close() {
        var firstFailure: Throwable? = null
        listOf<Closeable>(recognizer, renderer, document, input).forEach { resource ->
            try {
                resource.close()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                else firstFailure?.addSuppressed(error)
            }
        }
        firstFailure?.let { throw it }
    }
}

private const val PDFBOX_TEXT_GEOMETRY_LIMIT = 20_000_000f
private const val PDFBOX_TEXT_DIRECTION_TOLERANCE = 0.01f

/** A validated page-displayed TextPosition plus the axis used for grouping. */
private data class PdfBoxDisplayedTextPosition(
    val rectangle: PdfTopLeftRect,
    val advance: Float,
    val flowStart: Float,
    val flowEnd: Float,
    val crossStart: Float,
    val crossExtent: Float,
    val flowSign: Float
)

/**
 * PDFBox's LegacyPDFStreamEngine translates the TextPosition matrix by the
 * crop-box origin and stores getX()/getY() in the page-rotation-adjusted,
 * displayed top-left frame. The port can report zero from getWidth*() for a
 * rotated horizontal glyph, so use the constructor's individual width as the
 * text advance and rotate the extents into that already-displayed frame.
 */
private fun pdfBoxDisplayedTextPositionOrNull(
    position: TextPosition,
    geometry: PdfPageGeometry
): PdfBoxDisplayedTextPosition? {
    val matrix = position.getTextMatrix() ?: return null
    val matrixX = matrix.getTranslateX()
    val matrixY = matrix.getTranslateY()
    val endX = position.getEndX()
    val endY = position.getEndY()
    val height = position.getHeight()
    val pageRotation = position.getRotation()
    val textDirection = position.getDir()
    val pageWidth = position.getPageWidth()
    val pageHeight = position.getPageHeight()
    if (!listOf(
            matrixX,
            matrixY,
            endX,
            endY,
            height,
            textDirection,
            pageWidth,
            pageHeight
        ).all(::isBoundedPdfBoxTextValue) ||
        height <= 0f ||
        pageRotation != geometry.rotationDegrees ||
        pageWidth != geometry.visibleWidth ||
        pageHeight != geometry.visibleHeight
    ) {
        return null
    }

    val directionRotation = quarterTurnOrNull(textDirection) ?: return null
    val widths = position.getIndividualWidths() ?: return null
    if (widths.isEmpty()) return null
    var advance = 0f
    for (width in widths) {
        if (!isBoundedPdfBoxTextValue(width) || width < 0f) return null
        advance += width
        if (!isBoundedPdfBoxTextValue(advance)) return null
    }
    if (advance <= 0f) return null

    val x = position.getX()
    val y = position.getY()
    if (!isBoundedPdfBoxTextValue(x) || !isBoundedPdfBoxTextValue(y)) return null

    val displayedRotation = (pageRotation + directionRotation) % 360
    val left: Float
    val top: Float
    val right: Float
    val bottom: Float
    when (displayedRotation) {
        0 -> {
            left = x
            top = y
            right = x + advance
            bottom = y + height
        }
        90 -> {
            left = x
            top = y
            right = x + height
            bottom = y + advance
        }
        180 -> {
            left = x - advance
            top = y - height
            right = x
            bottom = y
        }
        270 -> {
            left = x - height
            top = y - advance
            right = x
            bottom = y
        }
        else -> return null
    }

    if (!listOf(left, top, right, bottom).all(::isBoundedPdfBoxTextValue) ||
        left < 0f || top < 0f ||
        right > geometry.displayedWidth || bottom > geometry.displayedHeight ||
        left >= right || top >= bottom
    ) {
        return null
    }

    val rectangle = try {
        PdfTopLeftRect(left = left, top = top, right = right, bottom = bottom)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val flowSign = if (displayedRotation == 0 || displayedRotation == 90) 1f else -1f
    val flowStart: Float
    val flowEnd: Float
    val crossStart: Float
    val crossExtent: Float
    when (displayedRotation) {
        0 -> {
            flowStart = rectangle.left
            flowEnd = rectangle.right
            crossStart = rectangle.top
            crossExtent = rectangle.bottom - rectangle.top
        }
        90 -> {
            flowStart = rectangle.top
            flowEnd = rectangle.bottom
            crossStart = rectangle.left
            crossExtent = rectangle.right - rectangle.left
        }
        180 -> {
            flowStart = rectangle.right
            flowEnd = rectangle.left
            crossStart = rectangle.bottom
            crossExtent = rectangle.bottom - rectangle.top
        }
        270 -> {
            flowStart = rectangle.bottom
            flowEnd = rectangle.top
            crossStart = rectangle.right
            crossExtent = rectangle.right - rectangle.left
        }
        else -> return null
    }
    if (!listOf(flowStart, flowEnd, crossStart, crossExtent).all(::isBoundedPdfBoxTextValue) ||
        crossExtent <= 0f
    ) {
        return null
    }
    return PdfBoxDisplayedTextPosition(
        rectangle = rectangle,
        advance = advance,
        flowStart = flowStart,
        flowEnd = flowEnd,
        crossStart = crossStart,
        crossExtent = crossExtent,
        flowSign = flowSign
    )
}

private fun quarterTurnOrNull(degrees: Float): Int? {
    if (!isBoundedPdfBoxTextValue(degrees)) return null
    val normalized = ((degrees % 360f) + 360f) % 360f
    val nearest = ((normalized / 90f).toInt() * 90) % 360
    val next = (nearest + 90) % 360
    return when {
        kotlin.math.abs(normalized - nearest) <= PDFBOX_TEXT_DIRECTION_TOLERANCE -> nearest
        kotlin.math.abs(normalized - next) <= PDFBOX_TEXT_DIRECTION_TOLERANCE -> next
        kotlin.math.abs(normalized - 360f) <= PDFBOX_TEXT_DIRECTION_TOLERANCE -> 0
        else -> null
    }
}

private fun isBoundedPdfBoxTextValue(value: Float): Boolean =
    value.isFinite() && kotlin.math.abs(value) <= PDFBOX_TEXT_GEOMETRY_LIMIT

private fun com.tom_roush.pdfbox.pdmodel.common.PDRectangle.toPdfBox(): PdfBox = PdfBox(
    left = lowerLeftX,
    bottom = lowerLeftY,
    right = upperRightX,
    top = upperRightY
)
