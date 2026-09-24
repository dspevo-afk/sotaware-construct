package com.example.myapplication.stage8

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myapplication.Point
import com.example.myapplication.R
import com.example.myapplication.isIncomingCancellation

@Composable
internal fun PageCodeRegionSelector(
    transform: () -> ViewerTransform,
    onSelected: (PageCodeRegion) -> Unit,
    onCancel: () -> Unit
) {
    var start by remember { mutableStateOf<Point?>(null) }
    var end by remember { mutableStateOf<Point?>(null) }
    val currentSelected by rememberUpdatedState(onSelected)
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().testTag("page-code-region").pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val mapping = transform()
            start = mapping.toNormalized(down.position.x, down.position.y)
            end = start
            down.consume()
            var cancelled = false
            try {
                do {
                    val event = awaitPointerEvent()
                    if (event.isIncomingCancellation() || event.changes.size != 1) {
                        cancelled = true
                        break
                    }
                    val change = event.changes.single()
                    end = mapping.toNormalized(change.position.x, change.position.y)
                    change.consume()
                } while (event.changes.any { it.pressed })
                val a = start
                val b = end
                if (!cancelled && a != null && b != null &&
                    kotlin.math.abs(a.x - b.x) > .001f && kotlin.math.abs(a.y - b.y) > .001f) {
                    currentSelected(PageCodeRegion(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y)))
                }
            } finally { start = null; end = null }
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val a = start?.let { transform().toScreen(it) }
            val b = end?.let { transform().toScreen(it) }
            if (a != null && b != null) {
                val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val rectSize = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                drawRect(Color.Yellow.copy(alpha = .3f), topLeft, rectSize)
                drawRect(Color(0xFF00695C), topLeft, rectSize, style = Stroke(2.dp.toPx()))
            }
        }
        Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.page_codes_select_help))
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }
}
