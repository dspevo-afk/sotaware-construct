package com.example.myapplication

import android.content.Context
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.util.LinkedHashMap

/**
 * OCR index that first tries PDFBox embedded text extraction, then falls back to ML Kit OCR.
 */
class OcrIndex(private val context: Context) {
    private val TAG = "SOTA_OCR"

    companion object {
        // Static LRU cache shared across all OcrIndex instances
        // Increased size to 200 pages for full document caching
        private val cache = object : LinkedHashMap<String, PageOcr>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageOcr>?): Boolean {
                return size > 200
            }
        }
        
        // Track which documents have been fully cached
        private val fullyCachedDocs = mutableSetOf<String>()
        
        fun isDocumentCached(uri: Uri): Boolean {
            synchronized(fullyCachedDocs) {
                return fullyCachedDocs.contains(uri.toString())
            }
        }
        
        fun markDocumentCached(uri: Uri) {
            synchronized(fullyCachedDocs) {
                fullyCachedDocs.add(uri.toString())
            }
        }
    }

    private val renderer by lazy { PdfBitmapRenderer(context) }

    /**
     * Pre-cache OCR for all pages of a document.
     * Runs in the background and reports progress via callback.
     */
    suspend fun preCacheDocument(
        uri: Uri,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (isDocumentCached(uri)) {
            Log.d(TAG, "Document already cached: $uri")
            return@withContext
        }
        
        // Get page count
        val pageCount = try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get page count for pre-caching", e)
            return@withContext
        }
        
        Log.d(TAG, "Pre-caching OCR for $pageCount pages: $uri")
        
        for (i in 0 until pageCount) {
            try {
                getPageOcr(uri, i)
                onProgress?.invoke(i + 1, pageCount)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache page $i", e)
            }
        }
        
        markDocumentCached(uri)
        Log.d(TAG, "Finished pre-caching OCR for $uri")
    }

    suspend fun getPageOcr(uri: Uri, pageIndex: Int): PageOcr = withContext(Dispatchers.IO) {
        val key = uri.toString() + "_" + pageIndex
        synchronized(cache) { cache[key]?.let { return@withContext it } }

        val start = System.currentTimeMillis()
        
        // First try PDFBox embedded text extraction
        val pdfBoxBoxes = tryPdfBoxExtraction(uri, pageIndex)
        if (pdfBoxBoxes.size >= 10) {
            // PDFBox found enough text, use it
            val pageOcr = PageOcr(pageIndex, pdfBoxBoxes)
            synchronized(cache) { cache[key] = pageOcr }
            val took = System.currentTimeMillis() - start
            Log.d(TAG, "page=$pageIndex words=${pdfBoxBoxes.size} source=PDFBox took=${took}ms")
            val allText = pdfBoxBoxes.take(20).joinToString(" ") { it.text }
            Log.d(TAG, "page=$pageIndex sampleText=$allText")
            return@withContext pageOcr
        }
        
        // Fall back to OCR
        val bmp = try { renderer.renderPageBitmap(uri, pageIndex, 4) } catch (t: Throwable) { Log.e(TAG, "render failed", t); null }
        if (bmp == null) {
            val empty = PageOcr(pageIndex, emptyList())
            synchronized(cache) { cache[key] = empty }
            return@withContext empty
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bmp, 0)
        val result = try { recognizer.process(image).await() } catch (t: Throwable) { Log.e(TAG, "recognition failed", t); null }

        val boxes = ArrayList<OcrBox>()
        if (result != null) {
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val lineText = line.text ?: continue
                    val lineBb = line.boundingBox ?: continue
                    // If ML Kit provided element-level boxes (words), use them for precise highlighting.
                    if (line.elements.isNotEmpty()) {
                        for (elem in line.elements) {
                            val et = elem.text ?: continue
                            val ebb = elem.boundingBox ?: continue
                            val el = ebb.left.toFloat() / bmp.width.toFloat()
                            val etop = ebb.top.toFloat() / bmp.height.toFloat()
                            val er = ebb.right.toFloat() / bmp.width.toFloat()
                            val ebottom = ebb.bottom.toFloat() / bmp.height.toFloat()
                            val nl = el.coerceIn(0f, 1f)
                            val nt = etop.coerceIn(0f, 1f)
                            val nr = er.coerceIn(0f, 1f)
                            val nb = ebottom.coerceIn(0f, 1f)
                            boxes.add(OcrBox(et, RectF(minOf(nl, nr), minOf(nt, nb), maxOf(nl, nr), maxOf(nt, nb))))
                        }
                    } else {
                        // Fallback: approximate per-word boxes by splitting the line text
                        val bb = lineBb
                        val nl = (bb.left.toFloat() / bmp.width.toFloat()).coerceIn(0f, 1f)
                        val nt = (bb.top.toFloat() / bmp.height.toFloat()).coerceIn(0f, 1f)
                        val nr = (bb.right.toFloat() / bmp.width.toFloat()).coerceIn(0f, 1f)
                        val nb = (bb.bottom.toFloat() / bmp.height.toFloat()).coerceIn(0f, 1f)
                        val words = lineText.split(Regex("\\s+")).filter { it.isNotBlank() }
                        if (words.isEmpty()) continue
                        val textStr = lineText
                        val totalLen = textStr.length.coerceAtLeast(1).toFloat()
                        var searchIndex = 0
                        for (w in words) {
                            val idx = textStr.indexOf(w, searchIndex)
                            if (idx < 0) continue
                            val startFrac = idx.toFloat() / totalLen
                            val endFrac = (idx + w.length).toFloat() / totalLen
                            val wl = nl + (nr - nl) * startFrac
                            val wr = nl + (nr - nl) * endFrac
                            boxes.add(OcrBox(w, RectF(wl.coerceIn(0f,1f), nt, wr.coerceIn(0f,1f), nb)))
                            searchIndex = idx + w.length
                        }
                    }
                }
            }
        }

        // recycle bitmap to free memory
        try { bmp.recycle() } catch (_: Exception) {}

        val pageOcr = PageOcr(pageIndex, boxes)
        synchronized(cache) { cache[key] = pageOcr }
        val took = System.currentTimeMillis() - start
        Log.d(TAG, "page=$pageIndex words=${boxes.size} source=OCR bmp=${bmp.width}x${bmp.height} took=${took}ms")
        val allText = boxes.take(20).joinToString(" ") { it.text }
        Log.d(TAG, "page=$pageIndex sampleText=$allText")
        return@withContext pageOcr
    }

    /**
     * Try to extract text with bounding boxes using PDFBox.
     * Returns a list of OcrBox with normalized coordinates.
     */
    private fun tryPdfBoxExtraction(uri: Uri, pageIndex: Int): List<OcrBox> {
        val boxes = ArrayList<OcrBox>()
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return emptyList()
            inputStream.use { stream ->
                val doc = PDDocument.load(stream)
                doc.use { document ->
                    if (pageIndex >= document.numberOfPages) return emptyList()
                    
                    val page = document.getPage(pageIndex)
                    val pageWidth = page.mediaBox.width
                    val pageHeight = page.mediaBox.height
                    val rotation = page.rotation
                    Log.d(TAG, "PDFBox page=$pageIndex mediaBox=${pageWidth}x${pageHeight} rotation=$rotation")
                    
                    // Custom text stripper to get word positions
                    val wordPositions = ArrayList<Pair<String, RectF>>()
                    
                    val stripper = object : PDFTextStripper() {
                        private var currentWord = StringBuilder()
                        private var wordLeft = Float.MAX_VALUE
                        private var wordTop = Float.MAX_VALUE
                        private var wordRight = 0f
                        private var wordBottom = 0f
                        private var lastX = -1f
                        private var lastY = -1f
                        private var avgCharWidth = 0f
                        private var charCount = 0
                        
                        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                            if (textPositions == null || textPositions.isEmpty()) return
                            
                            for (pos in textPositions) {
                                val char = pos.unicode ?: continue
                                
                                // Track average character width to detect gaps
                                if (pos.width > 0) {
                                    avgCharWidth = (avgCharWidth * charCount + pos.width) / (charCount + 1)
                                    charCount++
                                }
                                
                                // Check for gap between this character and the previous one
                                val hasGap = if (lastX >= 0 && currentWord.isNotEmpty()) {
                                    val gap = pos.x - lastX
                                    // If gap is more than 1.5x average char width, it's a space
                                    gap > avgCharWidth * 1.5f || 
                                    // Or if there's a significant Y change (new line)
                                    kotlin.math.abs(pos.y - lastY) > pos.height * 0.5f
                                } else false
                                
                                if (char.isBlank() || hasGap) {
                                    // End of word
                                    if (currentWord.isNotEmpty()) {
                                        saveCurrentWord()
                                    }
                                }
                                
                                if (!char.isBlank()) {
                                    // Part of word
                                    if (currentWord.isEmpty()) {
                                        wordLeft = pos.x
                                        wordTop = pos.y - pos.height
                                        wordRight = pos.x + pos.width
                                        wordBottom = pos.y
                                    } else {
                                        wordRight = pos.x + pos.width
                                        wordTop = minOf(wordTop, pos.y - pos.height)
                                        wordBottom = maxOf(wordBottom, pos.y)
                                    }
                                    currentWord.append(char)
                                    lastX = pos.x + pos.width
                                    lastY = pos.y
                                }
                            }
                        }
                        
                        private fun saveCurrentWord() {
                            if (currentWord.isNotEmpty()) {
                                val word = currentWord.toString()
                                
                                // Log raw coordinates for debugging
                                if (word.equals("CONTRACTOR", ignoreCase = true)) {
                                    Log.d("SOTA_OCR", "RAW CONTRACTOR: left=$wordLeft top=$wordTop right=$wordRight bottom=$wordBottom pageW=$pageWidth pageH=$pageHeight rot=$rotation")
                                }
                                
                                // For rotated pages, PDFBox's TextPosition gives coordinates in the 
                                // rotated view space. The x/y from TextPosition are already transformed.
                                // But we normalized against the un-rotated mediaBox dimensions.
                                // For 270° rotation: the visual width is pageHeight, visual height is pageWidth
                                val (effectiveWidth, effectiveHeight) = when (rotation) {
                                    90, 270 -> Pair(pageHeight, pageWidth)
                                    else -> Pair(pageWidth, pageHeight)
                                }
                                
                                // Normalize to 0..1 in the visual/rendered space
                                val nl = (wordLeft / effectiveWidth).coerceIn(0f, 1f)
                                val nt = (wordTop / effectiveHeight).coerceIn(0f, 1f)
                                val nr = (wordRight / effectiveWidth).coerceIn(0f, 1f)
                                val nb = (wordBottom / effectiveHeight).coerceIn(0f, 1f)
                                
                                // Ensure proper ordering
                                val finalLeft = minOf(nl, nr)
                                val finalTop = minOf(nt, nb)
                                val finalRight = maxOf(nl, nr)
                                val finalBottom = maxOf(nt, nb)
                                
                                if (word.equals("CONTRACTOR", ignoreCase = true)) {
                                    Log.d("SOTA_OCR", "NORM CONTRACTOR: l=$finalLeft t=$finalTop r=$finalRight b=$finalBottom")
                                }
                                
                                wordPositions.add(Pair(word, RectF(finalLeft, finalTop, finalRight, finalBottom)))
                                currentWord.clear()
                                wordLeft = Float.MAX_VALUE
                                wordTop = Float.MAX_VALUE
                                wordRight = 0f
                                wordBottom = 0f
                            }
                        }
                        
                        override fun endDocument(document: PDDocument?) {
                            saveCurrentWord()
                            super.endDocument(document)
                        }
                    }
                    
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    stripper.getText(document)
                    
                    for ((word, rect) in wordPositions) {
                        if (word.isNotBlank()) {
                            boxes.add(OcrBox(word, rect))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "PDFBox extraction failed", t)
        }
        return boxes
    }

    // optional: expose cached PageOcr if available (non-blocking)
    fun getCachedPageOcr(uri: Uri, pageIndex: Int): PageOcr? {
        val key = uri.toString() + "_" + pageIndex
        synchronized(cache) { return cache[key] }
    }
}
