package com.example.myapplication.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.R
import com.example.myapplication.ui.theme.AppTheme

/** Settings section that picks one of the colour themes; the change applies at once. */
@Composable
fun ThemeChooser(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.setting_theme), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.padding(start = 40.dp).selectableGroup()) {
            AppTheme.entries.forEach { theme ->
                val (title, help) = when (theme) {
                    AppTheme.SLATE -> R.string.theme_slate to R.string.theme_slate_help
                    AppTheme.HIGH_CONTRAST -> R.string.theme_high_contrast to R.string.theme_high_contrast_help
                    AppTheme.LIGHT -> R.string.theme_light to R.string.theme_light_help
                }
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = theme == selected, role = Role.RadioButton, onClick = { onSelect(theme) })
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = theme == selected, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
