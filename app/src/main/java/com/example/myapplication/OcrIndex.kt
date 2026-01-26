package com.example.myapplication

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.util.LinkedHashMap

/**
 * OCR index that renders pages and runs ML Kit to produce normalized line boxes.
 */
class OcrIndex(private val context: Context) {
    private val TAG = "SOTA_OCR"

    // LRU cache: key = uriString + "_" + pageIndex
    private val cache = object : LinkedHashMap<String, PageOcr>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageOcr>?): Boolean {
            return size > 10
        }
    }

    private val renderer by lazy { PdfBitmapRenderer(context) }

    suspend fun getPageOcr(uri: Uri, pageIndex: Int): PageOcr = withContext(Dispatchers.IO) {
        val key = uri.toString() + "_" + pageIndex
        synchronized(cache) { cache[key]?.let { return@withContext it } }

        val start = System.currentTimeMillis()
        // Render at a modest scale (1x) for speed; word boxes are usually stable at this size.
        val bmp = try { renderer.renderPageBitmap(uri, pageIndex, 1) } catch (t: Throwable) { Log.e(TAG, "render failed", t); null }
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
                    // Prefer element-level boxes (usually words) for tighter highlights.
                    val elems = line.elements
                    if (elems != null && elems.isNotEmpty()) {
                        for (elem in elems) {
                            val text = elem.text ?: continue
                            val bb = elem.boundingBox ?: continue
                            val l = bb.left.toFloat() / bmp.width.toFloat()
                            val t = bb.top.toFloat() / bmp.height.toFloat()
                            val r = bb.right.toFloat() / bmp.width.toFloat()
                            val b = bb.bottom.toFloat() / bmp.height.toFloat()
                            val nl = l.coerceIn(0f, 1f)
                            val nt = t.coerceIn(0f, 1f)
                            val nr = r.coerceIn(0f, 1f)
                            val nb = b.coerceIn(0f, 1f)
                            val rect = RectF(minOf(nl, nr), minOf(nt, nb), maxOf(nl, nr), maxOf(nt, nb))
                            boxes.add(OcrBox(text, rect))
                        }
                    } else {
                        val text = line.text ?: continue
                        val bb = line.boundingBox ?: continue
                        val l = bb.left.toFloat() / bmp.width.toFloat()
                        val t = bb.top.toFloat() / bmp.height.toFloat()
                        val r = bb.right.toFloat() / bmp.width.toFloat()
                        val b = bb.bottom.toFloat() / bmp.height.toFloat()
                        val nl = l.coerceIn(0f, 1f)
                        val nt = t.coerceIn(0f, 1f)
                        val nr = r.coerceIn(0f, 1f)
                        val nb = b.coerceIn(0f, 1f)
                        val rect = RectF(minOf(nl, nr), minOf(nt, nb), maxOf(nl, nr), maxOf(nt, nb))
                        boxes.add(OcrBox(text, rect))
                    }
                }
            }
        }

        // recycle bitmap to free memory
        try { bmp.recycle() } catch (_: Exception) {}

        val pageOcr = PageOcr(pageIndex, boxes)
        synchronized(cache) { cache[key] = pageOcr }
        val took = System.currentTimeMillis() - start
        Log.d(TAG, "page=$pageIndex lines=${boxes.size} took=${took}ms")
        return@withContext pageOcr
    }

    // optional: expose cached PageOcr if available (non-blocking)
    fun getCachedPageOcr(uri: Uri, pageIndex: Int): PageOcr? {
        val key = uri.toString() + "_" + pageIndex
        synchronized(cache) { return cache[key] }
    }
}
