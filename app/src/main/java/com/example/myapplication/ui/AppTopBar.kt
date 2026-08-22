package com.example.myapplication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Minimal top app bar for the PDF viewer screen.
 * Shows: back button, undo/redo, page navigation, search, screenshot, menu.
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
    TopAppBar(
        modifier = modifier,
        title = {
            // Empty title to maximize space for buttons
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            // Undo/Redo
            IconButton(
                onClick = onUndo,
                enabled = canUndo
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo"
                )
            }
            IconButton(
                onClick = onRedo,
                enabled = canRedo
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Redo"
                )
            }
            
            // Page navigation
            IconButton(
                onClick = onPreviousPage,
                enabled = currentPage > 0
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Previous Page"
                )
            }
            IconButton(
                onClick = onNextPage,
                enabled = currentPage < totalPages - 1
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next Page"
                )
            }
            
            // Search
            IconButton(onClick = onSearch) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search"
                )
            }
            
            // Screenshot
            IconButton(onClick = onScreenshot) {
                Icon(
                    imageVector = Icons.Default.Screenshot,
                    contentDescription = "Screenshot"
                )
            }
            
            // Menu
            IconButton(onClick = onMenu) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        )
    )
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
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Previous",
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
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Next",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
