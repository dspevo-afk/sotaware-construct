package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.myapplication.stage7.BitmapBudgetPolicy
import kotlinx.coroutines.CancellationException
import java.io.Closeable

/**
 * Renders a PDF page into a bitmap suitable for OCR and normalized-box calculations.
 * Keeps bitmap dimensions bounded to avoid OOM.
 */
class PdfBitmapRenderer(private val context: Context) : Closeable {
    /**
     * A renderer owned by one OCR session. The descriptor and PdfRenderer stay
     * open across page calls; callers must serialize page access and close the
     * returned session when the full document session ends.
     */
    interface Session : Closeable {
        fun renderPageBitmap(
            pageIndex: Int,
            scaleFactor: Int = 4,
            maxDim: Int = 8192,
            onBitmapCreated: ((Bitmap) -> Unit)? = null,
            viewportWidthPx: Int? = null,
            viewportHeightPx: Int? = null,
            viewportQualityMultiplier: Double = BitmapBudgetPolicy.VIEWPORT_QUALITY_MULTIPLIER
        ): Bitmap?
    }

    /** Opens one descriptor/PdfRenderer pair for a token-scoped OCR graph. */
    fun openSession(uri: Uri): Session? {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw error
        }
        if (pfd == null) return null
        return try {
            AndroidSession(pfd, PdfRenderer(pfd))
        } catch (error: Throwable) {
            try {
                pfd.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    fun renderPageBitmap(
        uri: Uri,
        pageIndex: Int,
        scaleFactor: Int = 4,
        maxDim: Int = 8192,
        onBitmapCreated: ((Bitmap) -> Unit)? = null,
        viewportWidthPx: Int? = null,
        viewportHeightPx: Int? = null,
        viewportQualityMultiplier: Double = BitmapBudgetPolicy.VIEWPORT_QUALITY_MULTIPLIER
    ): Bitmap? {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            null
        }
        if (pfd == null) return null
        pfd.use { p ->
            PdfRenderer(p).use { renderer ->
                if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                val page = renderer.openPage(pageIndex)
                try {
                    val plan = BitmapBudgetPolicy.pdfRenderPlan(
                        pageWidthPx = page.width,
                        pageHeightPx = page.height,
                        scaleFactor = scaleFactor,
                        viewportWidthPx = viewportWidthPx,
                        viewportHeightPx = viewportHeightPx,
                        viewportQualityMultiplier = viewportQualityMultiplier,
                        maxDimensionPx = maxDim
                    ) ?: return null
                    // The policy plan is the allocation boundary. Do not
                    // recreate its dimensions with Int arithmetic here.
                    val bmp = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
                    try {
                        val actual = if (bmp.config == Bitmap.Config.ARGB_8888) {
                            BitmapBudgetPolicy.actualAllocationPlan(
                                widthPx = bmp.width,
                                heightPx = bmp.height,
                                actualAllocationBytes = actualBitmapAllocationBytes(bmp)
                            )
                        } else {
                            null
                        }
                        if (actual == null || bmp.width != plan.width || bmp.height != plan.height) {
                            if (!bmp.isRecycled) bmp.recycle()
                            return null
                        }
                        // Callers that retain the result register ownership at
                        // the allocation boundary, before any cancellation
                        // checkpoint or rendering work can observe it.
                        onBitmapCreated?.invoke(bmp)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bmp
                    } catch (cancelled: CancellationException) {
                        if (!bmp.isRecycled) bmp.recycle()
                        throw cancelled
                    } catch (error: Throwable) {
                        if (!bmp.isRecycled) bmp.recycle()
                        throw error
                    }
                } finally {
                    page.close()
                }
            }
        }
    }

    override fun close() { /* nothing to keep open */ }

    private class AndroidSession(
        private val descriptor: ParcelFileDescriptor,
        private val renderer: PdfRenderer
    ) : Session {
        private val lock = Any()
        private var closed = false

        override fun renderPageBitmap(
            pageIndex: Int,
            scaleFactor: Int,
            maxDim: Int,
            onBitmapCreated: ((Bitmap) -> Unit)?,
            viewportWidthPx: Int?,
            viewportHeightPx: Int?,
            viewportQualityMultiplier: Double
        ): Bitmap? {
            synchronized(lock) {
                check(!closed) { "PDF renderer session is closed" }
            }
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            var primaryFailure: Throwable? = null
            try {
                val plan = BitmapBudgetPolicy.pdfRenderPlan(
                    pageWidthPx = page.width,
                    pageHeightPx = page.height,
                    scaleFactor = scaleFactor,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    viewportQualityMultiplier = viewportQualityMultiplier,
                    maxDimensionPx = maxDim
                ) ?: return null
                val bmp = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
                try {
                    val actual = if (bmp.config == Bitmap.Config.ARGB_8888) {
                        BitmapBudgetPolicy.actualAllocationPlan(
                            widthPx = bmp.width,
                            heightPx = bmp.height,
                            actualAllocationBytes = actualBitmapAllocationBytes(bmp)
                        )
                    } else {
                        null
                    }
                    if (actual == null || bmp.width != plan.width || bmp.height != plan.height) {
                        if (!bmp.isRecycled) bmp.recycle()
                        return null
                    }
                    onBitmapCreated?.invoke(bmp)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                } catch (error: Throwable) {
                    primaryFailure = error
                    if (!bmp.isRecycled) bmp.recycle()
                    throw error
                }
            } catch (error: Throwable) {
                if (primaryFailure == null) primaryFailure = error
                throw error
            } finally {
                try {
                    page.close()
                } catch (closeError: Throwable) {
                    if (primaryFailure != null) {
                        primaryFailure?.addSuppressed(closeError)
                    } else {
                        throw closeError
                    }
                }
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
            }
            var firstFailure: Throwable? = null
            try {
                renderer.close()
            } catch (error: Throwable) {
                firstFailure = error
            }
            try {
                descriptor.close()
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                else firstFailure?.addSuppressed(error)
            }
            firstFailure?.let { throw it }
        }
    }
}
