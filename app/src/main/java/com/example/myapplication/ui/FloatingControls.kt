package com.example.myapplication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Floating action buttons for landscape mode.
 * These replace the top bar to maximize canvas space.
 */
@Composable
fun FloatingViewerControls(
    currentPage: Int,
    totalPages: Int,
    onBack: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSearch: () -> Unit,
    onScreenshot: () -> Unit,
    onMenu: () -> Unit,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    BoxWithConstraints {
    val controlLayout = ViewerControlLayoutPolicy.forWidth(maxWidth.value.toInt(), landscape = true)
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The viewer is edge-to-edge in landscape; keep the control strip
            // outside status/navigation bars and display cutouts.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Back button
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(com.example.myapplication.R.string.viewer_back)
                )
            }
        }
        
        // Center: Page navigation
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                IconButton(
                    onClick = onPreviousPage,
                    enabled = currentPage > 0,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(com.example.myapplication.R.string.viewer_previous_page),
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Text(
                    text = "${currentPage + 1}/$totalPages",
                    style = MaterialTheme.typography.labelLarge
                )
                
                IconButton(
                    onClick = onNextPage,
                    enabled = currentPage < totalPages - 1,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(com.example.myapplication.R.string.viewer_next_page),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        // Secondary actions stay in one compact, accessible overflow menu.
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp
        ) {
            if (controlLayout.overflowActions.isEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingDirectAction(onUndo, canUndo, Icons.AutoMirrored.Filled.Undo, stringResource(com.example.myapplication.R.string.viewer_undo))
                    FloatingDirectAction(onRedo, canRedo, Icons.AutoMirrored.Filled.Redo, stringResource(com.example.myapplication.R.string.viewer_redo))
                    FloatingDirectAction(onSearch, true, Icons.Default.Search, stringResource(com.example.myapplication.R.string.viewer_search))
                    FloatingDirectAction(onScreenshot, true, Icons.Default.Screenshot, stringResource(com.example.myapplication.R.string.viewer_screenshot))
                    FloatingDirectAction(onMenu, true, Icons.Default.Menu, stringResource(com.example.myapplication.R.string.viewer_menu))
                }
            } else Box {
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(com.example.myapplication.R.string.viewer_more_actions))
                }
                DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                    FloatingMenuItem(stringResource(com.example.myapplication.R.string.viewer_undo), onUndo, canUndo) { overflowExpanded = false }
                    FloatingMenuItem(stringResource(com.example.myapplication.R.string.viewer_redo), onRedo, canRedo) { overflowExpanded = false }
                    FloatingMenuItem(stringResource(com.example.myapplication.R.string.viewer_search), onSearch) { overflowExpanded = false }
                    FloatingMenuItem(stringResource(com.example.myapplication.R.string.viewer_screenshot), onScreenshot) { overflowExpanded = false }
                    FloatingMenuItem(stringResource(com.example.myapplication.R.string.viewer_menu), onMenu) { overflowExpanded = false }
                }
            }
        }
    }
}
}

@Composable
private fun FloatingDirectAction(onClick: () -> Unit, enabled: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun FloatingMenuItem(label: String, onClick: () -> Unit, enabled: Boolean = true, close: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = { onClick(); close() }, enabled = enabled)
}
