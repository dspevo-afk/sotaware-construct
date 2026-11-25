package com.example.myapplication.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.myapplication.Point
import com.example.myapplication.stage8.ViewerTransform
import kotlin.math.roundToInt

/**
 * Measures the actual control inside its local viewer, including scaffold/rail insets.
 * With [placeAbove] the control is centred over [position] and sits a gap above it,
 * dropping below it only when there is no room above; it is always kept on screen.
 */
@Composable
internal fun ViewerFloatingControl(
    position: Offset,
    modifier: Modifier = Modifier,
    placeAbove: Boolean = false,
    content: @Composable () -> Unit
) {
    val gap = with(LocalDensity.current) { 20.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var measured by remember { mutableStateOf(IntSize.Zero) }
        val wanted = if (!placeAbove) Point(position.x, position.y) else {
            val above = position.y - measured.height - gap
            Point(position.x - measured.width / 2f, if (above >= 0f) above else position.y + gap)
        }
        val clamped = ViewerTransform.clampControl(
            wanted, measured.width.toFloat(), measured.height.toFloat(),
            constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()
        )
        Box(modifier
            .offset { IntOffset(clamped.x.roundToInt(), clamped.y.roundToInt()) }
            .sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
            .onSizeChanged { measured = it }) { content() }
    }
}
