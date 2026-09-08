package com.example.myapplication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Minimal top app bar for the PDF viewer screen.
 * Shows the primary navigation/search actions and keeps secondary actions in an
 * accessible overflow menu so the bar remains usable on narrow phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerTopBar(
    currentPage: Int,
    totalPages: Int,
    pdfName: String,
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
    val controlLayout = ViewerControlLayoutPolicy.forWidth(maxWidth.value.toInt(), landscape = false)
    TopAppBar(
        modifier = modifier,
        title = {
            // Empty title to maximize space for buttons
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(com.example.myapplication.R.string.viewer_back)
                )
            }
        },
        actions = {
            if (controlLayout.overflowActions.isEmpty()) {
                DirectViewerAction(onUndo, canUndo, Icons.AutoMirrored.Filled.Undo, stringResource(com.example.myapplication.R.string.viewer_undo))
                DirectViewerAction(onRedo, canRedo, Icons.AutoMirrored.Filled.Redo, stringResource(com.example.myapplication.R.string.viewer_redo))
                DirectViewerAction(onPreviousPage, currentPage > 0, Icons.AutoMirrored.Filled.ArrowBack, stringResource(com.example.myapplication.R.string.viewer_previous_page))
                DirectViewerAction(onNextPage, currentPage < totalPages - 1, Icons.AutoMirrored.Filled.ArrowForward, stringResource(com.example.myapplication.R.string.viewer_next_page))
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, stringResource(com.example.myapplication.R.string.viewer_search))
                }
                DirectViewerAction(onScreenshot, true, Icons.Default.Screenshot, stringResource(com.example.myapplication.R.string.viewer_screenshot))
                DirectViewerAction(onMenu, true, Icons.Default.Menu, stringResource(com.example.myapplication.R.string.viewer_menu))
            } else IconButton(onClick = onSearch) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(com.example.myapplication.R.string.viewer_search)
                )
            }
            if (controlLayout.overflowActions.isNotEmpty()) {
            Box {
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(com.example.myapplication.R.string.viewer_more_actions))
                }
                DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                    ViewerMenuItem(stringResource(com.example.myapplication.R.string.viewer_undo), onUndo, canUndo) { overflowExpanded = false }
                    ViewerMenuItem(stringResource(com.example.myapplication.R.string.viewer_redo), onRedo, canRedo) { overflowExpanded = false }
                    ViewerMenuItem(stringResource(com.example.myapplication.R.string.viewer_previous_page), onPreviousPage, currentPage > 0) { overflowExpanded = false }
                    ViewerMenuItem(stringResource(com.example.myapplication.R.string.viewer_next_page), onNextPage, currentPage < totalPages - 1) { overflowExpanded = false }
                    ViewerMenuItem(stringResource(com.example.myapplication.R.string.viewer_screenshot), onScreenshot) { overflowExpanded = false }
                    ViewerMenuItem(stringResource(com.example.myapplication.R.string.viewer_menu), onMenu) { overflowExpanded = false }
                }
            }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        )
    )
    }
}

@Composable
private fun DirectViewerAction(onClick: () -> Unit, enabled: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
}

@Composable
private fun ViewerMenuItem(label: String, onClick: () -> Unit, enabled: Boolean = true, close: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = { onClick(); close() }, enabled = enabled)
}

/**
 * Compact page navigation controls for the HUD or toolbar.
 */
@Composable
fun PageNavigator(
    currentPage: Int,
    totalPages: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
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
