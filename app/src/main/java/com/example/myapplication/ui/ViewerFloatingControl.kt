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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.myapplication.Point
import com.example.myapplication.stage8.ViewerTransform
import kotlin.math.roundToInt

/** Measures the actual control inside its local viewer, including scaffold/rail insets. */
@Composable
internal fun ViewerFloatingControl(position: Offset, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var measured by remember { mutableStateOf(IntSize.Zero) }
        val clamped = ViewerTransform.clampControl(
            Point(position.x, position.y), measured.width.toFloat(), measured.height.toFloat(),
            constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()
        )
        Box(modifier
            .offset { IntOffset(clamped.x.roundToInt(), clamped.y.roundToInt()) }
            .sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
            .onSizeChanged { measured = it }) { content() }
    }
}
