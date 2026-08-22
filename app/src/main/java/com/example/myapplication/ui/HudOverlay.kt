package com.example.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ToolMode

/**
 * Small, semi-transparent HUD overlay showing:
 * - Current page / total pages
 * - Scale status
 * - Current tool mode
 * 
 * Positioned in the bottom-left corner of the canvas,
 * does not block drawing area.
 */
@Composable
fun HudOverlay(
    currentPage: Int,
    totalPages: Int,
    currentScale: String?,
    currentMode: ToolMode,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Page indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Page $currentPage / $totalPages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // Scale indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SquareFoot,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (currentScale != null) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
                Text(
                    text = currentScale ?: "No scale",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (currentScale != null) FontWeight.Medium else FontWeight.Normal,
                    color = if (currentScale != null) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Tool mode indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = currentMode.icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = currentMode.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Instruction banner that appears at the top of the canvas
 * for modes that require user guidance (Measure, Scale).
 */
@Composable
fun InstructionBanner(
    mode: ToolMode,
    hasFirstPoint: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (icon, text) = when (mode) {
        ToolMode.MEASURE -> {
            if (hasFirstPoint) {
                Icons.Default.TouchApp to "Tap second point to complete measurement"
            } else {
                Icons.Default.TouchApp to "Tap first point to start measuring"
            }
        }
        ToolMode.SCALE -> {
            if (hasFirstPoint) {
                Icons.Default.TouchApp to "Tap second point, then enter known distance"
            } else {
                Icons.Default.Straighten to "Tap two points of a known distance to calibrate"
            }
        }
        ToolMode.NOTE -> Icons.Default.StickyNote2 to "Tap anywhere to place a note"
        ToolMode.PHOTO -> Icons.Default.CameraAlt to "Tap anywhere to place a photo pin"
        ToolMode.PEN -> Icons.Default.Create to "Draw on the page"
        ToolMode.HIGHLIGHTER -> Icons.Default.Highlight to "Draw to highlight areas"
        ToolMode.SHAPE -> Icons.Default.Category to "Tap to place a shape"
        ToolMode.PAN -> Icons.Default.PanTool to "Pan and zoom the page"
    }
    
    // Only show for specific modes
    if (mode == ToolMode.PAN) return
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
