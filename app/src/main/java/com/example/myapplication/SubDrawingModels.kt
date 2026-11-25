package com.example.myapplication

import java.io.Serializable
import com.example.myapplication.stage8.AnnotationSize
import java.util.Collections
import java.util.UUID

/**
 * A cropped region of one blueprint page that opens like its own page.
 *
 * Coordinates are normalized against the displayed page (same frame as every
 * other page annotation), so a sub-drawing survives document reopens, bundle
 * export/import, and Drive sync through the canonical snapshot codecs. Its
 * nested markups use the sub-drawing's own surface: normalized [0,1] over the
 * crop, exactly like a PhotoPin's per-image markups.
 */
data class SubDrawing(
    val id: String = UUID.randomUUID().toString(),
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val colorIndex: Int = 0,
    // Room name typed when the crop is created ("104", "Kitchen"). Null means
    // unnamed: the page badge then shows only its sheet number. Absent in
    // drawings saved before names existed, which keep opening unchanged.
    val name: String? = null,
    val paths: List<DrawnPath> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    val notes: List<Note> = emptyList(),
    val shapes: List<Shape> = emptyList(),
    // Photo pins made inside the sub-drawing belong to it, with their own
    // per-image markups, exactly like page-level pins.
    val photos: List<PhotoPin> = emptyList(),
    // Explicit calibration for this sub-drawing only. When null, the parent page's
    // scale carries over as-is (see SubDrawingGeometry.inheritedScale), so measuring
    // inside the crop needs no recalibration.
    val scale: PageScale? = null,
    // Radius of this crop's page badge as a fraction of the page's width, so the
    // badge belongs to the drawing: it scales with zoom like a photo pin, keeps its
    // own size across pages and reopening, and a pinch resize changes only this one.
    // Null is the default the viewer derives from the zoom at creation time.
    val badgeRatio: Float? = null,
    // Where this crop's page badge sits, in normalized page coordinates. The badge
    // can be dragged away from its crop (the crop itself never moves); null means
    // the default, the centre of the crop. Both are set together or both are null.
    val badgeX: Float? = null,
    val badgeY: Float? = null
) : Serializable {
    val widthRatio: Float get() = right - left
    val heightRatio: Float get() = bottom - top

    /** Freeze every nested collection at the reducer/state boundary. */
    fun copySubDrawing() = copy(
        paths = Collections.unmodifiableList(paths.map { it.copyPath() }),
        measurements = Collections.unmodifiableList(measurements.map { it.copyMeasurement() }),
        notes = Collections.unmodifiableList(notes.map { it.copyNote() }),
        shapes = Collections.unmodifiableList(shapes.map { it.copyShape() }),
        photos = Collections.unmodifiableList(photos.map { it.copyPin() }),
        scale = scale?.copy()
    )
}

/**
 * Crop geometry, badge sizes, and inherited calibration for one sub-drawing.
 *
 * A crop magnifies the page - half its width is 2x - but its source points are its
 * own share of the page, so the page's points-per-foot carries over unchanged and
 * measuring inside a crop needs no recalibration. Badge sizes are stored as a
 * fraction of the page's width, which is what makes them scale with zoom.
 */
object SubDrawingGeometry {
    /** Smallest accepted crop edge, as a fraction of the page. */
    const val MIN_CROP_RATIO: Float = 0.02f

    /**
     * Default badge radius relative to the crop's own smaller edge, so a badge is
     * sized by the room it marks instead of covering it.
     */
    const val BADGE_TO_CROP_RATIO: Float = 0.18f

    /**
     * Badge radius as a fraction of the page's width. A badge belongs to the
     * drawing, so its size is stored in page space and scales with zoom exactly
     * like a photo pin does; these are the limits a pinch may move it between.
     */
    const val DEFAULT_BADGE_RATIO: Float = 0.03f
    const val MIN_BADGE_RATIO: Float = 0.006f
    const val MAX_BADGE_RATIO: Float = 0.25f

    /** Clamped badge radius for one crop; a missing size is the default. */
    fun badgeRatioOrDefault(ratio: Float?): Float =
        if (ratio != null && ratio.isFinite()) ratio.coerceIn(MIN_BADGE_RATIO, MAX_BADGE_RATIO) else DEFAULT_BADGE_RATIO

    /**
     * Badge radius for a crop created at [compositeScale] page magnification.
     * The viewer wants the badge to keep the on-screen size the user was looking
     * at when they released the drag: at zoom 1 that is [DEFAULT_BADGE_RATIO] of
     * the page width, and deep inside a small room (zoom 5) it is a fifth as much
     * page space - so a badge drawn zoomed in reads as a small room tag on the
     * sheet instead of a huge circle over the drawing.
     */
    fun badgeRatioForZoom(compositeScale: Float): Float {
        val zoom = if (compositeScale.isFinite()) compositeScale.coerceAtLeast(1f) else 1f
        return (DEFAULT_BADGE_RATIO / zoom).coerceIn(MIN_BADGE_RATIO, MAX_BADGE_RATIO)
    }

    fun isValidCrop(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
            right - left >= MIN_CROP_RATIO &&
            bottom - top >= MIN_CROP_RATIO

    /** Normalized crop rectangle of a drag between two normalized page points. */
    fun cropForDrag(a: Point, b: Point): PdfNormalizedRect? = PdfCoordinateMapper.normalizedRectOrNull(
        left = minOf(a.x, b.x),
        top = minOf(a.y, b.y),
        right = maxOf(a.x, b.x),
        bottom = maxOf(a.y, b.y)
    )?.takeIf { isValidCrop(it.left, it.top, it.right, it.bottom) }

    /** Center of a sub-drawing's area in normalized page coordinates. */
    fun center(sub: SubDrawing): Point =
        Point((sub.left + sub.right) / 2f, (sub.top + sub.bottom) / 2f)

    /** Where the page badge is drawn: its own saved position, or the crop centre. */
    fun badgeCenter(sub: SubDrawing): Point {
        val x = sub.badgeX; val y = sub.badgeY
        return if (x != null && y != null && isValidBadgePosition(x, y)) Point(x, y) else center(sub)
    }

    /** A badge position is a finite point on the page. */
    fun isValidBadgePosition(x: Float, y: Float): Boolean = x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f

    /** True when the crop is wide enough to carry its page's calibration. */
    private fun cropCarriesScale(cropWidthRatio: Float): Boolean =
        cropWidthRatio.isFinite() && cropWidthRatio >= MIN_CROP_RATIO

    /**
     * Badge radius for a crop with no saved size, as a fraction of the page's width.
     * A badge is a room tag on the sheet, so it scales with its own crop: a small
     * room gets a small badge and a large one a proportionally larger marker, and
     * nothing ever covers the drawing it names. The viewer overrides this at creation
     * with the size derived from the zoom the user was at when they drew the crop.
     */
    fun defaultBadgeRatio(sub: SubDrawing): Float {
        val smallerEdge = minOf(sub.widthRatio, sub.heightRatio)
        return (smallerEdge * BADGE_TO_CROP_RATIO).coerceIn(MIN_BADGE_RATIO, DEFAULT_BADGE_RATIO)
    }

    /**
     * Page scale expressed on the sub-drawing surface; null when page uncalibrated.
     *
     * The sub-drawing surface is measured in its own source points, which are the
     * crop's share of the page (see [cropSourceSize]). A point moved x on that
     * surface spans x * widthRatio page points, so the page's points-per-foot
     * carries over unchanged: a 10' box measures 10' inside the crop exactly as it
     * does on the page. Only the surface size changes with the crop, never the scale.
     */
    fun inheritedScale(pageScale: PageScale?, cropWidthRatio: Float): PageScale? {
        val pointsPerFoot = pageScale?.pointsPerFoot ?: return null
        if (!cropCarriesScale(cropWidthRatio)) return null
        if (!com.example.myapplication.stage8.isValidPageScale(pointsPerFoot)) return null
        return PageScale(pointsPerFoot)
    }

    /** Maps a normalized sub-drawing point to its normalized page point. */
    fun pagePoint(sub: SubDrawing, point: Point): Point = Point(
        x = sub.left + point.x * sub.widthRatio,
        y = sub.top + point.y * sub.heightRatio
    )

    /**
     * Aspect (height / width) of the sub-drawing's own surface in source space.
     * Mirrors the photo convention: normalized coordinates on a surface whose
     * width is 1 and whose height is the oriented crop aspect.
     */
    fun subSurfaceAspect(sub: SubDrawing): Float? {
        if (sub.widthRatio < MIN_CROP_RATIO || sub.heightRatio < MIN_CROP_RATIO) return null
        val aspect = sub.heightRatio / sub.widthRatio
        if (!aspect.isFinite() || aspect <= 0f) return null
        return aspect
    }

    /** Maps a normalized page point into the sub-drawing surface (unclamped). */
    fun subPoint(sub: SubDrawing, point: Point): Point {
        val w = sub.widthRatio.takeIf { it >= MIN_CROP_RATIO } ?: 1f
        val h = sub.heightRatio.takeIf { it >= MIN_CROP_RATIO } ?: 1f
        return Point(x = (point.x - sub.left) / w, y = (point.y - sub.top) / h)
    }

    /** Maps a normalized sub-drawing rectangle onto its normalized page rectangle. */
    fun pageRect(sub: SubDrawing, rect: PdfNormalizedRect): PdfNormalizedRect? = PdfCoordinateMapper.normalizedRectOrNull(
        left = sub.left + rect.left * sub.widthRatio,
        top = sub.top + rect.top * sub.heightRatio,
        right = sub.left + rect.right * sub.widthRatio,
        bottom = sub.top + rect.bottom * sub.heightRatio
    )

    /** The page pixel dimensions of the cropped region (source space). */
    fun cropSourceSize(pageWidthPx: Float, pageHeightPx: Float, sub: SubDrawing): AnnotationSize? {
        if (!pageWidthPx.isFinite() || !pageHeightPx.isFinite() ||
            pageWidthPx <= 0f || pageHeightPx <= 0f
        ) return null
        val width = pageWidthPx * sub.widthRatio
        val height = pageHeightPx * sub.heightRatio
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
        return AnnotationSize(width, height)
    }

    /**
     * Maps a normalized page rectangle into the sub-drawing surface for OCR and
     * highlight painting. Returns null when it has no visible intersection.
     */
    fun subRectFromPageRect(sub: SubDrawing, rect: PdfNormalizedRect): PdfNormalizedRect? {
        val left = maxOf(rect.left, sub.left)
        val top = maxOf(rect.top, sub.top)
        val right = minOf(rect.right, sub.right)
        val bottom = minOf(rect.bottom, sub.bottom)
        if (left >= right || top >= bottom) return null
        val w = sub.widthRatio.takeIf { it >= MIN_CROP_RATIO } ?: return null
        val h = sub.heightRatio.takeIf { it >= MIN_CROP_RATIO } ?: return null
        return PdfCoordinateMapper.normalizedRectOrNull(
            left = (left - sub.left) / w,
            top = (top - sub.top) / h,
            right = (right - sub.left) / w,
            bottom = (bottom - sub.top) / h
        )
    }
}

/**
 * Fixed palette for page-level sub-drawing highlighting. Neighbours on one
 * page are assigned the first colour that differs from their already-assigned
 * neighbours, so overlapping/adjacent crops are always distinguishable.
 */
object SubDrawingPalette {
    /** Clearly different hues; index 0 is the default for the first crop. */
    val colors: List<Long> = listOf(
        0xFFE91E63, // pink
        0xFF2196F3, // blue
        0xFFFFC107, // amber
        0xFF4CAF50, // green
        0xFF9C27B0, // purple
        0xFF00BCD4, // cyan
        0xFFFF5722, // deep orange
        0xFF3F51B5  // indigo
    )

    val size: Int get() = colors.size

    fun colorArgb(index: Int): Int {
        if (colors.isEmpty()) return 0xFFE91E63.toInt()
        val normalized = ((index % size) + size) % size
        return colors[normalized].toInt()
    }

    /**
     * Assigns the colour index for a new sub-drawing so it differs from every
     * already-assigned sub-drawing on the same page. Crops are room tags on a
     * sheet, so neighbours - overlapping or not - must read as distinct.
     */
    fun assignIndex(existing: List<SubDrawing>): Int {
        if (colors.isEmpty()) return 0
        val taken = existing.mapNotNull { sub ->
            val index = sub.colorIndex
            if (index in 0 until size) index else null
        }.toSet()
        for (candidate in 0 until size) {
            if (candidate !in taken) return candidate
        }
        // More crops on the page than colours: differ from the last one used.
        val last = taken.maxOrNull() ?: -1
        return (last + 1) % size
    }
}

/**
 * The words a sub-drawing is known by on screen. The name is shown exactly as
 * the user typed it - no "Room" prefix, no rewording - followed by the sheet
 * number as the sheet list shows it, so "104" on sheet E1 reads "104 (E1)".
 * An unnamed crop falls back to its number on the page. Plain (non-composable)
 * so the viewer, the picker, and tests all read the same rule.
 */
object SubDrawingLabels {
    /** Trimmed room name, or null when the crop has no usable name. */
    fun typedName(sub: SubDrawing): String? = sub.name?.trim()?.takeIf { it.isNotEmpty() }

    /** Label of the open sub-drawing: "104 (E1)", else "Sub-drawing 3". */
    fun open(sub: SubDrawing, sheetLabel: String, ordinal: Int, numbered: (Int) -> String): String {
        val name = typedName(sub) ?: return numbered(ordinal)
        if (sheetLabel.isBlank()) return name
        return "$name ($sheetLabel)"
    }

    /** Label of one crop in the overlap picker: its name, else its number. */
    fun picker(sub: SubDrawing, ordinal: Int, numbered: (Int) -> String): String =
        typedName(sub) ?: numbered(ordinal)
}
