package com.example.myapplication.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
                    .padding(top = 8.dp, bottom = 48.dp)  // Extra bottom padding for navigation bar
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToolMode.entries.forEach { mode ->
                    ToolRailButton(
                        icon = mode.icon,
                        label = mode.label,
                        isSelected = currentMode == mode,
                        onClick = { onModeSelected(mode) }
                    )
                }
                // Extra spacer at end to ensure last item is fully visible
                Spacer(Modifier.height(16.dp))
            }
        } else {
            // Portrait: Horizontal bar at bottom - no scroll needed, no undo/redo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToolMode.entries.forEach { mode ->
                    ToolRailButton(
                        icon = mode.icon,
                        label = mode.label,
                        isSelected = currentMode == mode,
                        onClick = { onModeSelected(mode) }
                    )
                }
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
        modifier = Modifier.size(48.dp),
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
