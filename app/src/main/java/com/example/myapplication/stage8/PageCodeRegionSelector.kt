package com.example.myapplication.stage8

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myapplication.Point
import com.example.myapplication.R
import com.example.myapplication.isIncomingCancellation
import com.example.myapplication.ui.theme.LocalViewerColors

/** Pure geometry of the page-code box editor, kept apart from Compose so it can be tested. */
internal object PageCodeBoxEdit {
    /** Smallest box side, as a fraction of the page. */
    const val MIN_SIDE = 0.005f

    sealed interface Grip {
        /** 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left. */
        data class Corner(val index: Int) : Grip
        data object Move : Grip
    }

    fun corners(r: PageCodeRegion): List<Point> = listOf(
        Point(r.left, r.top), Point(r.right, r.top), Point(r.right, r.bottom), Point(r.left, r.bottom)
    )

    /** Which part of [region] a finger at screen [position] grabs, or null to start a new box. */
    fun grip(region: PageCodeRegion, toScreen: (Point) -> Point, position: Offset, handleRadius: Float): Grip? {
        val nearest = corners(region).mapIndexed { i, c ->
            val s = toScreen(c)
            i to kotlin.math.hypot(s.x - position.x, s.y - position.y)
        }.minByOrNull { it.second }
        if (nearest != null && nearest.second <= handleRadius) return Grip.Corner(nearest.first)
        val tl = toScreen(Point(region.left, region.top))
        val br = toScreen(Point(region.right, region.bottom))
        val inside = position.x in minOf(tl.x, br.x)..maxOf(tl.x, br.x) && position.y in minOf(tl.y, br.y)..maxOf(tl.y, br.y)
        return if (inside) Grip.Move else null
    }

    /** The box spanned by two page points, or null when it is too small to read. */
    fun fromCorners(a: Point, b: Point): PageCodeRegion? {
        val l = a.x.coerceIn(0f, 1f); val r = b.x.coerceIn(0f, 1f)
        val t = a.y.coerceIn(0f, 1f); val btm = b.y.coerceIn(0f, 1f)
        val left = minOf(l, r); val right = maxOf(l, r); val top = minOf(t, btm); val bottom = maxOf(t, btm)
        if (right - left < MIN_SIDE || bottom - top < MIN_SIDE) return null
        return PageCodeRegion(left, top, right, bottom)
    }

    /** [region] with corner [index] dragged to [to]; the opposite corner stays put. */
    fun resize(region: PageCodeRegion, index: Int, to: Point): PageCodeRegion {
        val x = to.x.coerceIn(0f, 1f); val y = to.y.coerceIn(0f, 1f)
        var left = region.left; var top = region.top; var right = region.right; var bottom = region.bottom
        when (index) {
            0 -> { left = minOf(x, right - MIN_SIDE); top = minOf(y, bottom - MIN_SIDE) }
            1 -> { right = maxOf(x, left + MIN_SIDE); top = minOf(y, bottom - MIN_SIDE) }
            2 -> { right = maxOf(x, left + MIN_SIDE); bottom = maxOf(y, top + MIN_SIDE) }
            else -> { left = minOf(x, right - MIN_SIDE); bottom = maxOf(y, top + MIN_SIDE) }
        }
        return PageCodeRegion(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
            .takeIf { it.right - it.left >= MIN_SIDE * .99f && it.bottom - it.top >= MIN_SIDE * .99f } ?: region
    }

    /** [region] moved by a page-space delta, kept on the page. */
    fun move(region: PageCodeRegion, dx: Float, dy: Float): PageCodeRegion {
        val w = region.right - region.left
        val h = region.bottom - region.top
        val left = (region.left + dx).coerceIn(0f, 1f - w)
        val top = (region.top + dy).coerceIn(0f, 1f - h)
        return PageCodeRegion(left, top, (left + w).coerceAtMost(1f), (top + h).coerceAtMost(1f))
    }
}

/**
 * Draw a box around a sheet's page code. Two fingers pan and zoom the sheet at any
 * time (a box being drawn is dropped, never cut short into a wrong one). Once drawn,
 * the box can be moved or resized by its corner handles before "Use this box".
 */
@Composable
internal fun PageCodeRegionSelector(
    transform: () -> ViewerTransform,
    onViewport: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onSelected: (PageCodeRegion) -> Unit,
    onCancel: () -> Unit
) {
    var region by remember { mutableStateOf<PageCodeRegion?>(null) }
    var drawStart by remember { mutableStateOf<Point?>(null) }
    var drawEnd by remember { mutableStateOf<Point?>(null) }
    val currentViewport by rememberUpdatedState(onViewport)
    val colors = LocalViewerColors.current
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().testTag("page-code-region").pointerInput(Unit) {
        val handleRadius = 28.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val before = region
            val startMapping = transform()
            val grip = before?.let { PageCodeBoxEdit.grip(it, startMapping::toScreen, down.position, handleRadius) }
            val startN = startMapping.toNormalized(down.position.x, down.position.y)
            if (grip == null) { drawStart = startN; drawEnd = startN }
            var viewportGesture = false
            var cancelled = false
            try {
                do {
                    val event = awaitPointerEvent()
                    if (event.isIncomingCancellation()) { cancelled = true; break }
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2 && !viewportGesture) {
                        // A second finger: this gesture now moves the sheet. Drop the box being
                        // drawn and undo any edit, so the pinch never leaves a wrong box behind.
                        viewportGesture = true
                        drawStart = null; drawEnd = null
                        region = before
                    }
                    if (viewportGesture) {
                        if (pressed.size >= 2) {
                            currentViewport(event.calculateCentroid(), event.calculatePan(), event.calculateZoom())
                        }
                        event.changes.forEach { it.consume() }
                    } else {
                        val change = pressed.firstOrNull() ?: event.changes.first()
                        val p = transform().toNormalized(change.position.x, change.position.y)
                        when (grip) {
                            null -> drawEnd = p
                            is PageCodeBoxEdit.Grip.Corner -> region = PageCodeBoxEdit.resize(checkNotNull(before), grip.index, p)
                            PageCodeBoxEdit.Grip.Move -> region = PageCodeBoxEdit.move(checkNotNull(before), p.x - startN.x, p.y - startN.y)
                        }
                        change.consume()
                    }
                } while (event.changes.any { it.pressed })
                if (cancelled) region = before
                else if (!viewportGesture && grip == null) {
                    val a = drawStart; val b = drawEnd
                    val drawn = if (a != null && b != null) PageCodeBoxEdit.fromCorners(a, b) else null
                    if (drawn != null) region = drawn
                }
            } finally { drawStart = null; drawEnd = null }
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val mapping = transform()
            val draft = drawStart?.let { a -> drawEnd?.let { b -> a to b } }
            val shown: Pair<Point, Point>? = draft ?: region?.let { Point(it.left, it.top) to Point(it.right, it.bottom) }
            if (shown != null) {
                val a = mapping.toScreen(shown.first)
                val b = mapping.toScreen(shown.second)
                val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val rectSize = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                drawRect(colors.searchHit.copy(alpha = .3f), topLeft, rectSize)
                drawRect(colors.selection, topLeft, rectSize, style = Stroke(2.dp.toPx()))
                val committed = region
                if (draft == null && committed != null) {
                    val half = 9.dp.toPx()
                    PageCodeBoxEdit.corners(committed).forEach { c ->
                        val s = mapping.toScreen(c)
                        drawRect(colors.selection, Offset(s.x - half, s.y - half), Size(half * 2, half * 2))
                        drawRect(colors.toolbarContent, Offset(s.x - half / 2, s.y - half / 2), Size(half, half))
                    }
                }
            }
        }
        // Below the viewer's top controls, so neither covers the other.
        Surface(Modifier.align(Alignment.TopCenter).padding(start = 12.dp, end = 12.dp, top = 64.dp).widthIn(max = 640.dp),
            tonalElevation = 6.dp, shadowElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(if (region == null) R.string.page_codes_select_help else R.string.page_codes_adjust_help))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                    if (region != null) {
                        TextButton(onClick = { region = null }) { Text(stringResource(R.string.page_codes_redraw)) }
                        Button(onClick = { region?.let(onSelected) }, modifier = Modifier.testTag("page-code-confirm")) {
                            Text(stringResource(R.string.page_codes_use_box))
                        }
                    }
                }
            }
        }
    }
}
