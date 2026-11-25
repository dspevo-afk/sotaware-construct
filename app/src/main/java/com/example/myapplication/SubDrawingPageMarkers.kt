package com.example.myapplication

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How a page of sub-drawings looks and what answers to a touch.
 *
 * The page shows one marker set at a time: the crop rectangles in the crop's own
 * palette colour, or the round [SubDrawingBadge] markers - never both. This owner
 * paints all of that (rectangles, badges, the live creation preview) and answers
 * the two pointer questions with exactly the geometry it paints, so a finger
 * always lands on the marker the user is looking at:
 *
 * - [badgeAt]: the crop this page point holds, used by the select-by-hold gesture.
 * - [hitItems]: every crop under a tap, which joins the shared overlap picker.
 *
 * A badge's radius is the crop's own saved size as a fraction of the page width
 * times the displayed page width, so it grows and shrinks with zoom like a photo
 * pin; a crop with no saved size takes [SubDrawingGeometry.defaultBadgeRatio].
 *
 * Plain (non-composable) except the [DrawScope] painters, so the viewer's canvas,
 * its gesture loop, and tests all read the same rules.
 */
object SubDrawingPageMarkers {
    /** Extra reach, in screen pixels, added to a badge's own radius when hit-testing. */
    const val BADGE_HIT_SLOP_PX: Float = 8f

    /** Creation-preview fill and outline: the dragged rectangle before it becomes a crop. */
    private val DRAFT_COLOR: Color = Color(0xFF2196F3)
    private const val DRAFT_FILL_ALPHA = 0.18f
    private const val DRAFT_STROKE_WIDTH = 3f
    private val DRAFT_DASH = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)

    /** Selected markers take the same cyan highlight every other selected annotation uses. */
    private val SELECTED_COLOR: Color = Color.Cyan

    /**
     * The rectangle a Sub-drawing drag covers right now, as normalized page
     * coordinates: the box between where the finger landed and where it is. Creation
     * publishes nothing from this - [SubDrawingGeometry.cropForDrag] decides at release
     * what becomes a crop - it is only what the canvas previews while dragging.
     */
    fun dragRect(start: Point, end: Point): PdfNormalizedRect? = PdfCoordinateMapper.normalizedRectOrNull(
        left = minOf(start.x, end.x),
        top = minOf(start.y, end.y),
        right = maxOf(start.x, end.x),
        bottom = maxOf(start.y, end.y)
    )

    /** Radius in screen pixels of one crop's badge at [compositeScale] page magnification. */
    fun badgeRadiusPx(sub: SubDrawing, pageWidthPx: Float, compositeScale: Float): Float =
        (sub.badgeRatio ?: SubDrawingGeometry.defaultBadgeRatio(sub)) * pageWidthPx * compositeScale

    /** Paints every crop rectangle on the page, selected one ringed in cyan. */
    fun DrawScope.drawCropRectangles(
        subDrawings: List<SubDrawing>,
        toScreen: (Point) -> Offset,
        selectedId: String?
    ) {
        subDrawings.forEach { sub ->
            val (topLeft, size) = cropCorners(sub, toScreen)
            val color = Color(SubDrawingPalette.colorArgb(sub.colorIndex))
            drawRect(color = color.copy(alpha = 0.15f), topLeft = topLeft, size = size)
            drawRect(
                color = if (sub.id == selectedId) SELECTED_COLOR else color,
                topLeft = topLeft,
                size = size,
                style = Stroke(width = if (sub.id == selectedId) 4f else 2f)
            )
        }
    }

    /**
     * Paints every crop's badge. [ratioOf] resolves the radius to draw for one crop so
     * a live pinch draft paints the size being dragged while every other badge, and
     * pointer-up, keeps using the stored radius.
     */
    fun DrawScope.drawBadges(
        subDrawings: List<SubDrawing>,
        toScreen: (Point) -> Offset,
        pageWidthPx: Float,
        compositeScale: Float,
        sheetLabel: String,
        ratioOf: (SubDrawing) -> Float?,
        selectedId: String?,
        centerOf: (SubDrawing) -> Point = SubDrawingGeometry::badgeCenter
    ) {
        subDrawings.forEach { sub ->
            val ratio = ratioOf(sub) ?: SubDrawingGeometry.defaultBadgeRatio(sub)
            with(SubDrawingBadge) {
                drawOn(
                    center = toScreen(centerOf(sub)),
                    radiusPx = ratio * pageWidthPx * compositeScale,
                    topText = sheetLabel,
                    bottomText = SubDrawingLabels.typedName(sub).orEmpty(),
                    selected = sub.id == selectedId
                )
            }
        }
    }

    /** Paints the dashed rectangle the Sub-drawing tool drags before it is published. */
    fun DrawScope.drawCreationDraft(rect: PdfNormalizedRect, toScreen: (Point) -> Offset) {
        val (topLeft, size) = rectCorners(rect, toScreen)
        drawRect(color = DRAFT_COLOR.copy(alpha = DRAFT_FILL_ALPHA), topLeft = topLeft, size = size)
        drawRect(
            color = DRAFT_COLOR,
            topLeft = topLeft,
            size = size,
            style = Stroke(width = DRAFT_STROKE_WIDTH, pathEffect = DRAFT_DASH)
        )
    }

    /**
     * The crop a page point lands on: its badge within the drawn radius plus
     * [BADGE_HIT_SLOP_PX], or its whole area when the page shows rectangles.
     * The long-press gesture measures the distance in the page's own width, so
     * the badge reads as a circle on the sheet at any page aspect.
     */
    fun badgeAt(
        subDrawings: List<SubDrawing>,
        pagePoint: Point,
        pageWidthPx: Float,
        compositeScale: Float,
        showRectangles: Boolean
    ): SubDrawing? = subDrawings.firstOrNull { sub ->
        onBadge(sub, pagePoint, pageWidthPx, pageWidthPx, compositeScale) ||
            (showRectangles && insideCrop(sub, pagePoint))
    }

    /**
     * Every crop a tap touches, in list order, for the shared overlap picker. A tap
     * measures the badge in the displayed bitmap's width and height, matching the
     * painting, so the badge answers as the ellipse the page actually shows.
     */
    fun hitItems(
        subDrawings: List<SubDrawing>,
        pagePoint: Point,
        pageWidthPx: Float,
        pageHeightPx: Float,
        compositeScale: Float,
        showRectangles: Boolean
    ): List<SubDrawing> = subDrawings.filter { sub ->
        onBadge(sub, pagePoint, pageWidthPx, pageHeightPx, compositeScale) ||
            (showRectangles && insideCrop(sub, pagePoint))
    }

    private fun onBadge(
        sub: SubDrawing,
        pagePoint: Point,
        widthPx: Float,
        heightPx: Float,
        compositeScale: Float
    ): Boolean {
        val center = SubDrawingGeometry.badgeCenter(sub)
        val dx = (pagePoint.x - center.x) * widthPx
        val dy = (pagePoint.y - center.y) * heightPx
        return sqrt(dx * dx + dy * dy) <=
            badgeRadiusPx(sub, widthPx, compositeScale) + BADGE_HIT_SLOP_PX / compositeScale
    }

    private fun insideCrop(sub: SubDrawing, pagePoint: Point): Boolean =
        pagePoint.x >= sub.left && pagePoint.x <= sub.right &&
            pagePoint.y >= sub.top && pagePoint.y <= sub.bottom

    private fun cropCorners(sub: SubDrawing, toScreen: (Point) -> Offset): Pair<Offset, Size> =
        rectCorners(PdfNormalizedRect(sub.left, sub.top, sub.right, sub.bottom), toScreen)

    private fun rectCorners(rect: PdfNormalizedRect, toScreen: (Point) -> Offset): Pair<Offset, Size> {
        val topLeft = toScreen(Point(rect.left, rect.top))
        val bottomRight = toScreen(Point(rect.right, rect.bottom))
        return Offset(minOf(topLeft.x, bottomRight.x), minOf(topLeft.y, bottomRight.y)) to
            Size(abs(bottomRight.x - topLeft.x), abs(bottomRight.y - topLeft.y))
    }
}
