package com.example.myapplication.stage9b
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.myapplication.Note
import com.example.myapplication.Shape
import com.example.myapplication.ShapeType
import java.util.LinkedHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
/**
 * The one Canvas renderer for ratio-only notes and shapes.
 *
 * Unit contract: x/y are normalized center anchors in the visible surface;
 * shape width/height and stroke are ratios of surface width/height and the
 * larger surface dimension respectively; note font size is a ratio of surface
 * height. Text is shaped in a fixed 1000-unit virtual width with a 900-unit
 * wrapping width, then mapped to the requested surface by one uniform scale.
 * left/top translate that surface-local result into the caller's Canvas.
 */
object AnnotationCanvasRendering {
    private const val CANONICAL_WIDTH = 1000f
    private const val CANONICAL_WRAP_WIDTH = 900
    private const val MAX_TEXT_CHARS = 64 * 1024
    private const val MAX_CANONICAL_FONT_SIZE = 4096f
    private const val MAX_GEOMETRY_DIMENSION = 10_000_000f
    private const val MAX_LAYOUT_CACHE_ENTRIES = 64
    private const val MAX_LAYOUT_CACHE_ESTIMATE = 1_048_576
    private data class LayoutKey(
        val text: String,
        val bold: Boolean,
        val fontSizeBits: Int
    )
    private data class LayoutMetrics(
        val layout: StaticLayout,
        val visualLeft: Float,
        val visualWidth: Float,
        val visualHeight: Float,
        val estimate: Int
    )
    private val layoutCache = object : LinkedHashMap<LayoutKey, LayoutMetrics>(16, .75f, true) {}
    private var layoutCacheEstimate = 0

    /** Draw a centered, rotated note into a translated surface-local rectangle. */
    fun drawNote(
        canvas: Canvas,
        note: Note,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        colorArgb: Int
    ) {
        val surface = surface(width, height) ?: return
        if (!left.isFinite() || !top.isFinite()) return
        val metrics = noteLayout(note, surface) ?: return
        val centerX = normalized(note.x) * CANONICAL_WIDTH
        val centerY = normalized(note.y) * surface.canonicalHeight
        val rotation = note.rotation.takeIf { it.isFinite() } ?: return
        val scale = surface.scale
        canvas.save()
        try {
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            canvas.translate(centerX, centerY)
            canvas.rotate(rotation)
            canvas.translate(-metrics.visualWidth / 2f - metrics.visualLeft, -metrics.visualHeight / 2f)
            synchronized(layoutCache) {
                val paint = metrics.layout.paint
                val priorColor = paint.color
                paint.color = colorArgb
                try {
                    metrics.layout.draw(canvas)
                } finally {
                    paint.color = priorColor
                }
            }
        } finally {
            canvas.restore()
        }
    }

    /** Unrotated surface-local pixel bounds, centered on the note anchor. */
    fun noteBounds(note: Note, width: Float, height: Float): RectF {
        val surface = surface(width, height) ?: return RectF()
        val metrics = noteLayout(note, surface) ?: return RectF()
        val centerX = normalized(note.x) * width
        val centerY = normalized(note.y) * height
        val halfWidth = metrics.visualWidth * surface.scale / 2f
        val halfHeight = metrics.visualHeight * surface.scale / 2f
        return RectF(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
    }

    fun containsNote(
        note: Note,
        pointX: Float,
        pointY: Float,
        width: Float,
        height: Float,
        padding: Float = 0f
    ): Boolean {
        if (!pointX.isFinite() || !pointY.isFinite() || !padding.isFinite() || padding < 0f) return false
        val surface = surface(width, height) ?: return false
        val metrics = noteLayout(note, surface) ?: return false
        val rotation = note.rotation.takeIf { it.isFinite() } ?: return false
        return rotatedContains(
            pointX = pointX,
            pointY = pointY,
            centerX = normalized(note.x) * width,
            centerY = normalized(note.y) * height,
            width = metrics.visualWidth * surface.scale,
            height = metrics.visualHeight * surface.scale,
            rotationDegrees = rotation,
            padding = padding
        )
    }
    /** Draw a ratio-sized shape into a translated surface-local rectangle. */
    fun drawShape(canvas: Canvas, shape: Shape, left: Float, top: Float, width: Float, height: Float) {
        val surface = surface(width, height) ?: return
        if (!left.isFinite() || !top.isFinite()) return
        val geometry = shapeGeometry(shape, surface) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = shape.colorArgb
            style = if (shape.isFilled && shape.type != ShapeType.ARROW) {
                Paint.Style.FILL_AND_STROKE
            } else {
                Paint.Style.STROKE
            }
            strokeWidth = geometry.strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isDither = true
        }
        canvas.save()
        try {
            canvas.translate(left, top)
            canvas.translate(geometry.centerX, geometry.centerY)
            canvas.rotate(geometry.rotation)
            when (shape.type) {
                ShapeType.RECTANGLE -> canvas.drawRect(
                    -geometry.width / 2f,
                    -geometry.height / 2f,
                    geometry.width / 2f,
                    geometry.height / 2f,
                    paint
                )
                ShapeType.CIRCLE -> canvas.drawOval(
                    -geometry.width / 2f,
                    -geometry.height / 2f,
                    geometry.width / 2f,
                    geometry.height / 2f,
                    paint
                )
                ShapeType.ARROW -> {
                    if (geometry.strokeWidth > 0f) {
                        val halfWidth = geometry.width / 2f
                        val arrowHeadLength = min(halfWidth * .3f, geometry.strokeWidth * 10f)
                        val path = Path().apply {
                            moveTo(-halfWidth, 0f)
                            lineTo(halfWidth, 0f)
                            moveTo(halfWidth, 0f)
                            lineTo(halfWidth - arrowHeadLength, -geometry.height / 2f)
                            moveTo(halfWidth, 0f)
                            lineTo(halfWidth - arrowHeadLength, geometry.height / 2f)
                        }
                        canvas.drawPath(path, paint)
                    }
                }
                ShapeType.CLOUD -> canvas.drawPath(cloudPath(geometry.width, geometry.height), paint)
            }
        } finally {
            canvas.restore()
        }
    }

    fun containsShape(
        shape: Shape,
        pointX: Float,
        pointY: Float,
        width: Float,
        height: Float,
        padding: Float = 0f
    ): Boolean {
        if (!pointX.isFinite() || !pointY.isFinite() || !padding.isFinite() || padding < 0f) return false
        val surface = surface(width, height) ?: return false
        val geometry = shapeGeometry(shape, surface) ?: return false
        return rotatedContains(
            pointX = pointX,
            pointY = pointY,
            centerX = geometry.centerX,
            centerY = geometry.centerY,
            width = geometry.width,
            height = geometry.height,
            rotationDegrees = geometry.rotation,
            padding = padding
        )
    }
    private data class Surface(
        val width: Float,
        val height: Float,
        val scale: Float,
        val canonicalHeight: Float
    )
    private data class ShapeGeometry(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val strokeWidth: Float,
        val rotation: Float
    )
    private fun surface(width: Float, height: Float): Surface? {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f ||
            width > MAX_GEOMETRY_DIMENSION || height > MAX_GEOMETRY_DIMENSION
        ) return null
        val canonicalHeight = CANONICAL_WIDTH * (height / width)
        val scale = width / CANONICAL_WIDTH
        if (!canonicalHeight.isFinite() || canonicalHeight <= 0f || !scale.isFinite() || scale <= 0f) return null
        return Surface(width, height, scale, canonicalHeight)
    }

    private fun shapeGeometry(shape: Shape, surface: Surface): ShapeGeometry? {
        val x = shape.x.takeIf { it.isFinite() } ?: return null
        val y = shape.y.takeIf { it.isFinite() } ?: return null
        val rotation = shape.rotation.takeIf { it.isFinite() } ?: return null
        val widthRatio = shape.widthRatio.takeIf { it.isFinite() && it > 0f } ?: return null
        val heightRatio = shape.heightRatio.takeIf { it.isFinite() && it > 0f } ?: return null
        val strokeRatio = shape.strokeWidthRatio.takeIf { it.isFinite() && it >= 0f } ?: return null
        val actualWidth = widthRatio * surface.width
        val actualHeight = heightRatio * surface.height
        val strokeWidth = strokeRatio * max(surface.width, surface.height)
        if (!actualWidth.isFinite() || !actualHeight.isFinite() || !strokeWidth.isFinite() ||
            actualWidth > MAX_GEOMETRY_DIMENSION || actualHeight > MAX_GEOMETRY_DIMENSION ||
            strokeWidth > MAX_GEOMETRY_DIMENSION
        ) return null
        if (!shape.isFilled && strokeWidth <= 0f) return null
        return ShapeGeometry(
            centerX = normalized(x) * surface.width,
            centerY = normalized(y) * surface.height,
            width = actualWidth,
            height = actualHeight,
            strokeWidth = strokeWidth,
            rotation = rotation
        )
    }

    private fun noteLayout(note: Note, surface: Surface): LayoutMetrics? {
        if (note.text.isEmpty() || note.text.length > MAX_TEXT_CHARS) return null
        if (!note.x.isFinite() || !note.y.isFinite()) return null
        val ratio = note.fontSizeRatio.takeIf { it.isFinite() && it > 0f } ?: return null
        val fontSize = ratio * surface.canonicalHeight
        if (!fontSize.isFinite() || fontSize <= 0f || fontSize > MAX_CANONICAL_FONT_SIZE) return null
        val key = LayoutKey(note.text, note.isBold, fontSize.toBits())
        synchronized(layoutCache) {
            layoutCache[key]?.let { return it }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = fontSize
                typeface = if (note.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isSubpixelText = true
                isLinearText = true
            }
            val layout = StaticLayout.Builder.obtain(note.text, 0, note.text.length, paint, CANONICAL_WRAP_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            // ALIGN_NORMAL places RTL lines at the right of the wrap box.
            // Center the union of actual line extents, not the 900-unit layout
            // origin, so drawing and hit-testing share the same visible anchor.
            var visualLeft = Float.POSITIVE_INFINITY
            var visualRight = Float.NEGATIVE_INFINITY
            for (line in 0 until layout.lineCount) {
                visualLeft = min(visualLeft, layout.getLineLeft(line))
                visualRight = max(visualRight, layout.getLineRight(line))
            }
            if (!visualLeft.isFinite() || !visualRight.isFinite()) return null
            val metrics = LayoutMetrics(
                layout = layout,
                visualLeft = visualLeft,
                visualWidth = (visualRight - visualLeft).coerceAtLeast(0f),
                visualHeight = layout.height.toFloat(),
                estimate = note.text.length * 2 + layout.lineCount * 8
            )
            if (metrics.estimate <= MAX_LAYOUT_CACHE_ESTIMATE) {
                while (layoutCache.size >= MAX_LAYOUT_CACHE_ENTRIES ||
                    layoutCacheEstimate + metrics.estimate > MAX_LAYOUT_CACHE_ESTIMATE
                ) {
                    val eldest = layoutCache.entries.iterator()
                    if (!eldest.hasNext()) break
                    layoutCacheEstimate -= eldest.next().value.estimate
                    eldest.remove()
                }
                layoutCache[key] = metrics
                layoutCacheEstimate += metrics.estimate
            }
            return metrics
        }
    }

    private fun normalized(value: Float): Float = value.coerceIn(0f, 1f)

    private fun rotatedContains(
        pointX: Float,
        pointY: Float,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        rotationDegrees: Float,
        padding: Float
    ): Boolean {
        if (!listOf(pointX, pointY, centerX, centerY, width, height, rotationDegrees, padding).all { it.isFinite() }) return false
        if (width <= 0f || height <= 0f || padding < 0f) return false
        val radians = Math.toRadians((-rotationDegrees).toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val dx = pointX - centerX
        val dy = pointY - centerY
        val localX = dx * c - dy * s
        val localY = dx * s + dy * c
        return kotlin.math.abs(localX) <= width / 2f + padding &&
            kotlin.math.abs(localY) <= height / 2f + padding
    }

    private fun cloudPath(width: Float, height: Float): Path {
        val path = Path()
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val bumps = 12
        for (i in 0 until bumps) {
            val angle = i.toFloat() / bumps * 2f * Math.PI.toFloat()
            val nextAngle = (i + 1).toFloat() / bumps * 2f * Math.PI.toFloat()
            val r1 = 1f + if (i % 2 == 0) .15f else 0f
            val r2 = 1f + if ((i + 1) % 2 == 0) .15f else 0f
            val x2 = halfWidth * r2 * cos(nextAngle)
            val y2 = halfHeight * r2 * sin(nextAngle)
            if (i == 0) path.moveTo(halfWidth * r1 * cos(angle), halfHeight * r1 * sin(angle))
            val middleAngle = (angle + nextAngle) / 2f
            path.quadTo(
                halfWidth * 1.25f * cos(middleAngle),
                halfHeight * 1.25f * sin(middleAngle),
                x2,
                y2
            )
        }
        path.close()
        return path
    }
}
