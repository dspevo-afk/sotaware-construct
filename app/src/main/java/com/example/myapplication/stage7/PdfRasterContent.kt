package com.example.myapplication.stage7

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/**
 * Conservative, bounded raster admission. Unknown/complex content selects OCR,
 * never embedded-only indexing. BI byte detection may over-admit OCR in text
 * or comments; that is preferable to silently skipping inline image labels.
 */
internal fun hasPossibleRasterContent(page: PDPage, ensureActive: () -> Unit): Boolean {
    val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    var remainingBytes = 1024 * 1024
    var objects = 0
    val buffer = ByteArray(8192)
    fun inspect(resources: PDResources?, open: () -> InputStream, depth: Int): Boolean {
        ensureActive()
        if (depth > 16 || ++objects > 256) return true
        if (resources != null) {
            if (!visited.add(resources.cosObject)) return true
            if (resources.cosObject.containsKey(COSName.getPDFName("Pattern")) ||
                resources.cosObject.containsKey(COSName.getPDFName("ExtGState"))) return true
            for (name in resources.xObjectNames) {
                ensureActive()
                if (++objects > 256) return true
                when (val item = resources.getXObject(name)) {
                    is PDImageXObject -> return true
                    is PDFormXObject -> if (inspect(item.resources ?: resources, { item.contents }, depth + 1)) return true
                }
            }
        }
        open().use { input ->
            var previous = -1
            var zeroReads = 0
            while (true) {
                ensureActive()
                if (remainingBytes <= 0) return true
                val read = input.read(buffer, 0, minOf(buffer.size, remainingBytes))
                if (read < 0) break
                if (read == 0) {
                    if (++zeroReads > 16) return true
                    continue
                }
                zeroReads = 0
                remainingBytes -= read
                for (i in 0 until read) {
                    val next = buffer[i].toInt() and 0xff
                    if (previous == 'B'.code && next == 'I'.code) return true
                    previous = next
                }
            }
        }
        return false
    }
    return try {
        if (page.cosObject.containsKey(COSName.getPDFName("Annots"))) true
        else inspect(page.resources, { page.contents }, 0)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }
}
