package com.example.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.R
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
                    text = stringResource(com.example.myapplication.R.string.hud_page, currentPage, totalPages),
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
                    text = currentScale ?: stringResource(com.example.myapplication.R.string.hud_no_scale),
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
 * Instruction banner for the current tool. It is a small pill that fades out a
 * few seconds after it appears, and comes back when the tool or step changes.
 */
@Composable
fun InstructionBanner(
    mode: ToolMode,
    hasFirstPoint: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (icon, text) = when (mode) {
        ToolMode.POLYLINE -> Icons.Default.Polyline to stringResource(R.string.polyline_instruction)
        ToolMode.MEASURE -> Icons.Default.TouchApp to stringResource(if (hasFirstPoint) R.string.hint_measure_second else R.string.hint_measure_first)
        ToolMode.SCALE -> (if (hasFirstPoint) Icons.Default.TouchApp else Icons.Default.Straighten) to
            stringResource(if (hasFirstPoint) R.string.hint_scale_second else R.string.hint_scale_first)
        ToolMode.NOTE -> Icons.Default.StickyNote2 to stringResource(R.string.hint_note)
        ToolMode.PHOTO -> Icons.Default.CameraAlt to stringResource(R.string.hint_photo)
        ToolMode.PEN -> Icons.Default.Create to stringResource(R.string.hint_pen)
        ToolMode.HIGHLIGHTER -> Icons.Default.Highlight to stringResource(R.string.hint_highlighter)
        ToolMode.SHAPE -> Icons.Default.Category to stringResource(R.string.hint_shape)
        ToolMode.SUB_DRAWING -> Icons.Default.PictureInPicture to stringResource(R.string.hint_sub_drawing)
        ToolMode.PAN -> Icons.Default.PanTool to stringResource(R.string.hint_pan)
    }
    if (mode == ToolMode.PAN) return
    var visible by remember(mode, hasFirstPoint) { mutableStateOf(true) }
    LaunchedEffect(mode, hasFirstPoint) {
        visible = true
        kotlinx.coroutines.delay(HINT_VISIBLE_MS)
        visible = false
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(600))
        ) {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                shape = RoundedCornerShape(50),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

/** How long a tool hint stays before it fades. */
const val HINT_VISIBLE_MS = 4000L
