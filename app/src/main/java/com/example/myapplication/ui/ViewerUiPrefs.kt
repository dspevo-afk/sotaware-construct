package com.example.myapplication.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Viewer-wide display preferences that many small composables read. The host
 * (MainActivity) loads them from settings and keeps them current; reading them
 * from Compose recomposes on change.
 */
object ViewerUiPrefs {
    /** Glove mode: bigger tool rail and toolbar targets. */
    var gloveMode by mutableStateOf(false)

    /** Touch target of a tool rail button: 48dp, or 56dp in glove mode. */
    val railButton: Dp get() = if (gloveMode) 56.dp else 48.dp
    /** Thickness of the tool rail itself. */
    val railThickness: Dp get() = if (gloveMode) 72.dp else 64.dp
    /** Icon size in the rail and toolbars. */
    val icon: Dp get() = if (gloveMode) 26.dp else 22.dp
}
