package com.example.myapplication.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.myapplication.ToolMode

/**
 * Context-sensitive tool options panel.
 * Shows different controls based on the current tool mode.
 * Appears as a bottom sheet on phone or side panel on tablet.
 */
@Composable
fun ToolOptionsSheet(
    currentMode: ToolMode,
    isVisible: Boolean,
    isTablet: Boolean,
    currentScale: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isTablet) {
        // Tablet: Side panel (always visible when in a mode that has options)
        AnimatedVisibility(
            visible = isVisible && currentMode != ToolMode.PAN,
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            Surface(
                modifier = modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                ToolOptionsContent(
                    currentMode = currentMode,
                    currentScale = currentScale,
                    onDismiss = onDismiss
                )
            }
        }
    } else {
        // Phone: Bottom sheet style
        AnimatedVisibility(
            visible = isVisible && currentMode != ToolMode.PAN,
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            Surface(
                modifier = modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .heightIn(max = 420.dp)
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant)
                            .clearAndSetSemantics { }
                        )
                    }
                    ToolOptionsContent(
                        currentMode = currentMode,
                        currentScale = currentScale,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolOptionsContent(
    currentMode: ToolMode,
    currentScale: String?,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .heightIn(max = 600.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = toolModeLabel(currentMode), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(com.example.myapplication.R.string.tool_options_close))
            }
        }
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        
        when (currentMode) {
            ToolMode.MEASURE -> MeasureOptions(currentScale)
            ToolMode.SCALE -> ScaleOptions()
            ToolMode.PEN -> DrawOptions(isHighlighter = false)
            ToolMode.HIGHLIGHTER -> DrawOptions(isHighlighter = true)
            ToolMode.NOTE -> NoteOptions()
            ToolMode.PHOTO -> PhotoOptions()
            ToolMode.SHAPE -> ShapeOptions()
            ToolMode.PAN -> { /* No options for pan mode */ }
        }
    }
}

@Composable
private fun MeasureOptions(currentScale: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Instruction banner
        InstructionBanner(
            text = stringResource(com.example.myapplication.R.string.measure_instruction),
            icon = Icons.Default.TouchApp
        )
        
        Spacer(Modifier.height(8.dp))
        
        // Current scale display
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(com.example.myapplication.R.string.scale_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    currentScale ?: stringResource(com.example.myapplication.R.string.scale_not_calibrated),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (currentScale != null) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
        
        Text(
            stringResource(com.example.myapplication.R.string.measure_tip),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScaleOptions() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InstructionBanner(
            text = stringResource(com.example.myapplication.R.string.scale_instruction),
            icon = Icons.Default.Straighten
        )
        
        Text(
            stringResource(com.example.myapplication.R.string.scale_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DrawOptions(isHighlighter: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InstructionBanner(
            text = stringResource(if (isHighlighter) com.example.myapplication.R.string.highlighter_instruction else com.example.myapplication.R.string.pen_instruction),
            icon = if (isHighlighter) Icons.Default.Highlight else Icons.Default.Create
        )
        
        // Color indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(com.example.myapplication.R.string.color_label),
                style = MaterialTheme.typography.labelMedium
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isHighlighter) Color.Yellow else Color.Red)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
        
        // Stroke info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(com.example.myapplication.R.string.stroke_label),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                stringResource(if (isHighlighter) com.example.myapplication.R.string.highlighter_stroke else com.example.myapplication.R.string.pen_stroke),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoteOptions() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InstructionBanner(
            text = stringResource(com.example.myapplication.R.string.note_instruction),
            icon = Icons.Default.StickyNote2
        )
        
        Text(
            stringResource(com.example.myapplication.R.string.note_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PhotoOptions() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InstructionBanner(
            text = stringResource(com.example.myapplication.R.string.photo_instruction),
            icon = Icons.Default.CameraAlt
        )
        
        Text(
            stringResource(com.example.myapplication.R.string.photo_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ShapeOptions() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InstructionBanner(
            text = stringResource(com.example.myapplication.R.string.shape_instruction),
            icon = Icons.Default.Category
        )
        
        Text(
            stringResource(com.example.myapplication.R.string.shape_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InstructionBanner(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
