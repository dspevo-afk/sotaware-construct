package com.example.myapplication.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.example.myapplication.ToolMode

/**
 * Unified tool selection rail for the blueprint app.
 * Displays vertically on tablets (left side) or horizontally on phones (bottom).
 */
@Composable
fun ToolRail(
    currentMode: ToolMode,
    onModeSelected: (ToolMode) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearPage: () -> Unit,
    isVertical: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        if (isVertical) {
            // Landscape: Vertical rail on the left - scrollable to fit all tools
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(64.dp)
                    // Landscape viewer content is edge-to-edge. Consume the
                    // complete safe drawing inset so the vertical rail clears
                    // status bars, navigation bars, and display cutouts.
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 8.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToolMode.entries.forEach { mode ->
                    ToolRailButton(
                        icon = mode.icon,
                        label = toolModeLabel(mode),
                        isSelected = currentMode == mode,
                        isToolSelection = true,
                        onClick = { onModeSelected(mode) }
                    )
                }
                ToolRailButton(Icons.Default.Undo, stringResource(com.example.myapplication.R.string.viewer_undo), false, canUndo, isToolSelection = false, onClick = onUndo)
                ToolRailButton(Icons.Default.Redo, stringResource(com.example.myapplication.R.string.viewer_redo), false, canRedo, isToolSelection = false, onClick = onRedo)
                ToolRailButton(Icons.Default.DeleteSweep, stringResource(com.example.myapplication.R.string.tool_clear_page), false, true, isToolSelection = false, onClick = onClearPage)
                // Extra spacer at end to ensure last item is fully visible
                Spacer(Modifier.height(16.dp))
            }
        } else {
            // Portrait: Horizontal bar at bottom - no scroll needed, no undo/redo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToolMode.entries.forEach { mode ->
                    ToolRailButton(
                        icon = mode.icon,
                        label = toolModeLabel(mode),
                        isSelected = currentMode == mode,
                        isToolSelection = true,
                        onClick = { onModeSelected(mode) }
                    )
                }
                ToolRailButton(Icons.Default.Undo, stringResource(com.example.myapplication.R.string.viewer_undo), false, canUndo, isToolSelection = false, onClick = onUndo)
                ToolRailButton(Icons.Default.Redo, stringResource(com.example.myapplication.R.string.viewer_redo), false, canRedo, isToolSelection = false, onClick = onRedo)
                ToolRailButton(Icons.Default.DeleteSweep, stringResource(com.example.myapplication.R.string.tool_clear_page), false, true, isToolSelection = false, onClick = onClearPage)
            }
        }
    }
}

@Composable
private fun ToolRailButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    enabled: Boolean = true,
    isToolSelection: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) 
            MaterialTheme.colorScheme.primaryContainer 
        else 
            Color.Transparent,
        label = "containerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            tint != null -> tint
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "contentColor"
    )
    
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics {
                selected = isSelected
                role = if (isToolSelection) Role.RadioButton else Role.Button
            },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
internal fun toolModeLabel(mode: ToolMode): String = when (mode) {
    ToolMode.PAN -> stringResource(com.example.myapplication.R.string.tool_pan)
    ToolMode.MEASURE -> stringResource(com.example.myapplication.R.string.tool_measure)
    ToolMode.SCALE -> stringResource(com.example.myapplication.R.string.tool_calibrate)
    ToolMode.PEN -> stringResource(com.example.myapplication.R.string.tool_pen)
    ToolMode.HIGHLIGHTER -> stringResource(com.example.myapplication.R.string.tool_highlighter)
    ToolMode.NOTE -> stringResource(com.example.myapplication.R.string.tool_note)
    ToolMode.PHOTO -> stringResource(com.example.myapplication.R.string.tool_photo)
    ToolMode.SHAPE -> stringResource(com.example.myapplication.R.string.tool_shape)
}
