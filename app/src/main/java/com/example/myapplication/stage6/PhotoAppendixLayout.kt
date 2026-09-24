package com.example.myapplication.stage6

import kotlin.math.floor
import kotlin.math.sqrt

/** Physical point-space layout; appendix photos continue instead of shrinking into negative cells. */
internal data class PhotoAppendixLayout(val margin: Float, val gap: Float, val headerSize: Float,
    val contentWidth: Float, val availableHeight: Float, val columns: Int, val maxRows: Int) {
    val capacity: Int get() = columns * maxRows
    companion object {
        fun create(width: Int, height: Int): PhotoAppendixLayout {
            require(width > 0 && height > 0)
            val margin = minOf(20f, width / 10f, height / 10f)
            val header = minOf((height / 30f).coerceIn(18f, 36f), height / 5f)
            val gap = minOf(10f, width / 20f, height / 20f)
            val contentWidth = width - 2 * margin
            val available = height - 3 * margin - header
            require(contentWidth > 0f && available > 0f)
            val columns = if (contentWidth >= 2 * 144f + gap) 2 else 1
            val rows = floor((available + gap) / (144f + gap)).toInt().coerceIn(1, 3)
            return PhotoAppendixLayout(margin, gap, header, contentWidth, available, columns, rows)
        }
    }
}

/** Limit retained PDF raster work across the whole appendix, not only one photo. */
internal fun photoAppendixDecodeSize(width: Int, height: Int, totalPhotos: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && totalPhotos > 0)
    val pixelShare = 8_000_000.0 / totalPhotos
    val scale = minOf(1.0, sqrt(pixelShare / width / height))
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
