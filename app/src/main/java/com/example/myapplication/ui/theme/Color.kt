package com.example.myapplication.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The colour themes offered in Settings. [SLATE] is the default. */
enum class AppTheme(val prefValue: String) {
    /** Professional slate grey with one blue accent. */
    SLATE("slate"),
    /** Black and white with a yellow accent, for bright sunlight. */
    HIGH_CONTRAST("high_contrast"),
    /** Light grey surfaces, for the office. */
    LIGHT("light");

    companion object {
        fun fromPref(value: String?): AppTheme = entries.firstOrNull { it.prefValue == value } ?: SLATE
    }
}

/**
 * Colours the viewer draws over a drawing. They come from the theme so nothing in the
 * renderer or toolbars hard-codes a colour; [selection] must stay clearly visible on a
 * white or busy drawing in every theme.
 */
@Immutable
data class ViewerColors(
    /** Halo and handles of the selected item on the drawing. */
    val selection: Color,
    /** Accent text on a dark overlay (sub-drawing title, placement hints). */
    val accentOnDark: Color,
    /** Search hit highlight. */
    val searchHit: Color,
    /** Highlight over text found by search. */
    val textHit: Color,
    /** Two-point picks (scale, sub-drawing corners). */
    val pointPick: Color,
    /** A photo pin's disc. */
    val photoPin: Color,
    /** The small number badge on a photo pin. */
    val photoPinBadge: Color,
    /** The selection toolbar card: always dark so it reads over any drawing. */
    val toolbarContainer: Color,
    val toolbarContent: Color,
    val toolbarDestructive: Color,
    /** Dark backing behind overlay bars on the drawing. */
    val overlayScrim: Color
)

internal val SlateViewerColors = ViewerColors(
    selection = Color(0xFF00C2FF),
    accentOnDark = Color(0xFF7CC8F5),
    searchHit = Color(0xFFFFE14D),
    textHit = Color(0xFFFFA726),
    pointPick = Color(0xFFE91E63),
    photoPin = Color(0xFF2F7FC1),
    photoPinBadge = Color(0xFFD93A3A),
    toolbarContainer = Color(0xFF262C33),
    toolbarContent = Color(0xFFE8ECF0),
    toolbarDestructive = Color(0xFFFF8A80),
    overlayScrim = Color(0xE6161A1F)
)

internal val HighContrastViewerColors = ViewerColors(
    selection = Color(0xFFFF00D4),
    accentOnDark = Color(0xFFFFE600),
    searchHit = Color(0xFFFFE600),
    textHit = Color(0xFFFF8C00),
    pointPick = Color(0xFFFF00D4),
    photoPin = Color(0xFF0050FF),
    photoPinBadge = Color(0xFFE00000),
    toolbarContainer = Color(0xFF000000),
    toolbarContent = Color(0xFFFFFFFF),
    toolbarDestructive = Color(0xFFFF6E6E),
    overlayScrim = Color(0xF2000000)
)

internal val LightViewerColors = ViewerColors(
    selection = Color(0xFF0077FF),
    accentOnDark = Color(0xFF8CC8FF),
    searchHit = Color(0xFFFFE14D),
    textHit = Color(0xFFFF9800),
    pointPick = Color(0xFFD81B60),
    photoPin = Color(0xFF1F5FA8),
    photoPinBadge = Color(0xFFD32F2F),
    toolbarContainer = Color(0xFF2B3038),
    toolbarContent = Color(0xFFF2F4F7),
    toolbarDestructive = Color(0xFFFF8A80),
    overlayScrim = Color(0xE6202429)
)

// Slate (default, dark)
internal val SlateBackground = Color(0xFF14181D)
internal val SlateSurface = Color(0xFF1B2026)
internal val SlateSurfaceVariant = Color(0xFF2A3139)
internal val SlateOnSurface = Color(0xFFE3E7EC)
internal val SlateOnSurfaceVariant = Color(0xFFA9B3BE)
internal val SlateAccent = Color(0xFF5AAEE6)
internal val SlateOnAccent = Color(0xFF062338)
internal val SlateAccentContainer = Color(0xFF1F4A6B)
internal val SlateOnAccentContainer = Color(0xFFD3EAFB)
internal val SlateOutline = Color(0xFF4A535D)

// High contrast (dark, for sunlight)
internal val ContrastAccent = Color(0xFFFFE600)
internal val ContrastOnAccent = Color(0xFF000000)

// Light (office)
internal val LightBackground = Color(0xFFF4F6F8)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightSurfaceVariant = Color(0xFFE3E8ED)
internal val LightOnSurface = Color(0xFF1A1F24)
internal val LightOnSurfaceVariant = Color(0xFF4F5A65)
internal val LightAccent = Color(0xFF1F5FA8)
internal val LightAccentContainer = Color(0xFFD6E6F8)
internal val LightOnAccentContainer = Color(0xFF0B2A4D)
internal val LightOutline = Color(0xFF8B96A1)
