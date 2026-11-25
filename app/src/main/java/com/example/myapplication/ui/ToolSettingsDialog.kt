package com.example.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Close
import com.example.myapplication.R
import com.example.myapplication.ShapeType
import com.example.myapplication.ToolMode
import com.example.myapplication.stage8.DrawingToolStyle

/** Item appearance (the toolbar's Colour): edit, then Save or Cancel. */
@Composable
fun ToolSettingsDialog(mode: ToolMode, initial: DrawingToolStyle, saving: Boolean,
    error: Boolean, onSave: (DrawingToolStyle) -> Unit, onDismiss: () -> Unit, saveEnabled: Boolean = true) {
    var style by remember(mode, initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.tool_settings_title, toolModeLabel(mode))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolStyleEditor(mode, style, enabled = !saving) { style = it }
                if (error) Text(stringResource(R.string.tool_settings_save_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(style) }, enabled = !saving && saveEnabled) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.clear_page_cancel)) } })
}

/**
 * A tool's settings as a small panel beside the rail: every change applies at once
 * (no Save or Cancel). [error] shows when the last change could not be saved.
 */
@Composable
fun ToolSettingsPanel(mode: ToolMode, style: DrawingToolStyle, error: Boolean, enabled: Boolean,
    onChange: (DrawingToolStyle) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 340.dp).testTag("sotaware.tool-settings-panel"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(12.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.tool_settings_title, toolModeLabel(mode)), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(androidx.compose.material.icons.Icons.Default.Close, stringResource(R.string.tool_options_close))
                }
            }
            ToolStyleEditor(mode, style, enabled = enabled, onChange = onChange)
            if (error) Text(stringResource(R.string.tool_settings_save_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Colour, width, text size and shape controls for one tool or item. */
@Composable
fun ToolStyleEditor(mode: ToolMode, style: DrawingToolStyle, enabled: Boolean, onChange: (DrawingToolStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (mode != ToolMode.PAN && mode != ToolMode.PHOTO && mode != ToolMode.SCALE) {
            Text(stringResource(R.string.tool_setting_color))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(R.string.color_red to 0xffff0000.toInt(), R.string.color_yellow to 0xffffff00.toInt(),
                    R.string.color_blue to 0xff1565c0.toInt(), R.string.color_green to 0xff00a040.toInt(),
                    R.string.color_black to 0xff000000.toInt()).forEach { (label, argb) ->
                    val description = stringResource(label)
                    Box(Modifier.size(48.dp).padding(5.dp).background(Color(argb), CircleShape)
                        .border(if (style.colorArgb == argb) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .semantics { contentDescription = description; selected = style.colorArgb == argb }
                        .clickable(enabled = enabled) { onChange(style.copy(colorArgb = argb)) })
                }
            }
            if (mode != ToolMode.NOTE) {
                val widthLabel = stringResource(R.string.tool_setting_width)
                StrokePreview(style)
                Slider(value = style.width, onValueChange = { onChange(style.copy(width = it)) },
                    valueRange = .001f.. .04f, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = widthLabel })
            }
        }
        if (mode == ToolMode.NOTE) {
            Text(stringResource(R.string.tool_setting_text_size))
            Slider(value = style.fontSize, onValueChange = { onChange(style.copy(fontSize = it)) },
                valueRange = .01f.. .08f, enabled = enabled)
            Row { Checkbox(checked = style.bold, enabled = enabled, onCheckedChange = { onChange(style.copy(bold = it)) }); Text(stringResource(R.string.annotation_bold)) }
        }
        if (mode == ToolMode.SHAPE) {
            ShapeType.entries.forEach { shape ->
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(style.copy(shape = shape)) }) {
                    RadioButton(selected = shape == style.shape, enabled = enabled, onClick = { onChange(style.copy(shape = shape)) })
                    Text(stringResource(when (shape) {
                        ShapeType.RECTANGLE -> R.string.shape_rectangle
                        ShapeType.CIRCLE -> R.string.shape_circle
                        ShapeType.ARROW -> R.string.shape_arrow
                        ShapeType.CLOUD -> R.string.shape_cloud
                    }))
                }
            }
            Row { Checkbox(checked = style.filled, enabled = enabled, onCheckedChange = { onChange(style.copy(filled = it)) }); Text(stringResource(R.string.tool_setting_filled)) }
        }
    }
}

/** The stroke width as a number and a line drawn at that width in the chosen colour. */
@Composable
fun StrokePreview(style: DrawingToolStyle) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tool_setting_width_value, strokeWidthNumber(style.width)))
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(28.dp)) {
            val stroke = (style.width * 600f).coerceIn(1f, 24f) * density
            drawLine(Color(style.colorArgb), androidx.compose.ui.geometry.Offset(8f, size.height / 2f),
                androidx.compose.ui.geometry.Offset(size.width - 8f, size.height / 2f), stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Width ratio 0.001..0.04 shown as a whole number 1..40. */
fun strokeWidthNumber(width: Float): Int = (width * 1000f).let { if (it.isFinite()) kotlin.math.round(it).toInt().coerceAtLeast(1) else 1 }
