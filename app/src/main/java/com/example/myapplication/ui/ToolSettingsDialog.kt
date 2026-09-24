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
import com.example.myapplication.R
import com.example.myapplication.ShapeType
import com.example.myapplication.ToolMode
import com.example.myapplication.stage8.DrawingToolStyle

@Composable
fun ToolSettingsDialog(mode: ToolMode, initial: DrawingToolStyle, saving: Boolean,
    error: Boolean, onSave: (DrawingToolStyle) -> Unit, onDismiss: () -> Unit, saveEnabled: Boolean = true) {
    var style by remember(mode, initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.tool_settings_title, toolModeLabel(mode))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                .clickable(enabled = !saving) { style = style.copy(colorArgb = argb) })
                        }
                    }
                    if (mode != ToolMode.NOTE) {
                    val widthLabel = stringResource(R.string.tool_setting_width)
                    Text(widthLabel)
                    Slider(value = style.width, onValueChange = { style = style.copy(width = it) },
                        valueRange = .001f.. .04f, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = widthLabel })
                    }
                }
                if (mode == ToolMode.NOTE) {
                    Text(stringResource(R.string.tool_setting_text_size))
                    Slider(value = style.fontSize, onValueChange = { style = style.copy(fontSize = it) },
                        valueRange = .01f.. .08f, enabled = !saving)
                    Row { Checkbox(checked = style.bold, enabled = !saving, onCheckedChange = { style = style.copy(bold = it) }); Text(stringResource(R.string.annotation_bold)) }
                }
                if (mode == ToolMode.SHAPE) {
                    ShapeType.entries.forEach { shape ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { style = style.copy(shape = shape) }) {
                            RadioButton(selected = shape == style.shape, enabled = !saving, onClick = { style = style.copy(shape = shape) })
                            Text(stringResource(when (shape) {
                                ShapeType.RECTANGLE -> R.string.shape_rectangle
                                ShapeType.CIRCLE -> R.string.shape_circle
                                ShapeType.ARROW -> R.string.shape_arrow
                                ShapeType.CLOUD -> R.string.shape_cloud
                            }))
                        }
                    }
                    Row { Checkbox(checked = style.filled, enabled = !saving, onCheckedChange = { style = style.copy(filled = it) }); Text(stringResource(R.string.tool_setting_filled)) }
                }
                if (error) Text(stringResource(R.string.tool_settings_save_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(style) }, enabled = !saving && saveEnabled) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.clear_page_cancel)) } })
}
