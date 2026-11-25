package com.example.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The theme chosen in Settings; the host loads it from preferences and keeps it current. */
object AppThemeState {
    var theme by mutableStateOf(AppTheme.SLATE)
}

/** Viewer drawing colours of the current theme. */
val LocalViewerColors = staticCompositionLocalOf { SlateViewerColors }

private val SlateColorScheme = darkColorScheme(
    primary = SlateAccent,
    onPrimary = SlateOnAccent,
    primaryContainer = SlateAccentContainer,
    onPrimaryContainer = SlateOnAccentContainer,
    secondary = SlateAccent,
    onSecondary = SlateOnAccent,
    secondaryContainer = SlateSurfaceVariant,
    onSecondaryContainer = SlateOnSurface,
    tertiary = SlateAccent,
    onTertiary = SlateOnAccent,
    background = SlateBackground,
    onBackground = SlateOnSurface,
    surface = SlateSurface,
    onSurface = SlateOnSurface,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = SlateOnSurfaceVariant,
    surfaceContainerLowest = SlateBackground,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurface,
    surfaceContainerHigh = SlateSurfaceVariant,
    surfaceContainerHighest = SlateSurfaceVariant,
    inverseSurface = SlateOnSurface,
    inverseOnSurface = SlateSurface,
    outline = SlateOutline,
    outlineVariant = SlateSurfaceVariant
)

private val HighContrastColorScheme = darkColorScheme(
    primary = ContrastAccent,
    onPrimary = ContrastOnAccent,
    primaryContainer = ContrastAccent,
    onPrimaryContainer = ContrastOnAccent,
    secondary = ContrastAccent,
    onSecondary = ContrastOnAccent,
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color.White,
    tertiary = ContrastAccent,
    onTertiary = ContrastOnAccent,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color.White,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = Color(0xFF262626),
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    outline = Color.White,
    outlineVariant = Color(0xFF8C8C8C)
)

private val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    primaryContainer = LightAccentContainer,
    onPrimaryContainer = LightOnAccentContainer,
    secondary = LightAccent,
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    tertiary = LightAccent,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightBackground,
    surfaceContainerHigh = LightSurfaceVariant,
    surfaceContainerHighest = LightSurfaceVariant,
    inverseSurface = Color(0xFF2B3038),
    inverseOnSurface = Color(0xFFF2F4F7),
    outline = LightOutline,
    outlineVariant = LightSurfaceVariant
)

@Composable
fun MyApplicationTheme(
    theme: AppTheme = AppThemeState.theme,
    content: @Composable () -> Unit
) {
    val (scheme, viewer) = when (theme) {
        AppTheme.SLATE -> SlateColorScheme to SlateViewerColors
        AppTheme.HIGH_CONTRAST -> HighContrastColorScheme to HighContrastViewerColors
        AppTheme.LIGHT -> LightColorScheme to LightViewerColors
    }
    CompositionLocalProvider(LocalViewerColors provides viewer) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
