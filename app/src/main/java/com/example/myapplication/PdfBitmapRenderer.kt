package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable

/**
 * Renders a PDF page into a bitmap suitable for OCR and normalized-box calculations.
 * Keeps bitmap dimensions bounded to avoid OOM.
 */
class PdfBitmapRenderer(private val context: Context) : Closeable {
    fun renderPageBitmap(uri: Uri, pageIndex: Int, scaleFactor: Int = 2, maxDim: Int = 4096): Bitmap? {
        val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
        if (pfd == null) return null
        pfd.use { p ->
            PdfRenderer(p).use { renderer ->
                if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                val page = renderer.openPage(pageIndex)
                try {
                    var bmpW = page.width * scaleFactor
                    var bmpH = page.height * scaleFactor
                    // cap dimensions to avoid OOM
                    val scaleDown = maxOf(bmpW.toFloat() / maxDim, bmpH.toFloat() / maxDim, 1f)
                    if (scaleDown > 1f) {
                        bmpW = (bmpW / scaleDown).toInt()
                        bmpH = (bmpH / scaleDown).toInt()
                    }
                    val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                } finally { page.close() }
            }
        }
    }

    override fun close() { /* nothing to keep open */ }
}
