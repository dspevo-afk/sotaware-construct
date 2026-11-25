package com.example.myapplication.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.LocalViewerColors

/** One button of the selection toolbar: an icon with its word under it. */
data class ToolbarAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    /** Spoken name when it should differ from [label] (e.g. "Delete shape"). */
    val contentDescription: String? = null,
    /** A colour of its own for the icon, e.g. a sub-drawing's crop colour. */
    val iconTint: Color? = null,
    val tag: String? = null
)

/**
 * The one selection toolbar every selected thing uses - sheet items, badges, the
 * overlap picker, selected text and photo markup: a dark rounded card of
 * icon-and-word buttons, placed above [anchor] and kept on screen.
 */
@Composable
internal fun SelectionToolbar(
    anchor: Offset,
    actions: List<ToolbarAction>,
    modifier: Modifier = Modifier,
    title: String? = null,
    placeAbove: Boolean = true
) {
    ViewerFloatingControl(anchor, modifier, placeAbove = placeAbove) {
        SelectionToolbarCard(actions, title = title)
    }
}

/** The toolbar card on its own, for strips pinned to an edge (polyline Cancel/Finish). */
@Composable
internal fun SelectionToolbarCard(actions: List<ToolbarAction>, modifier: Modifier = Modifier, title: String? = null) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = LocalViewerColors.current.toolbarContainer,
        contentColor = LocalViewerColors.current.toolbarContent,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(4.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { ToolbarButton(it) }
            }
        }
    }
}

@Composable
private fun ToolbarButton(action: ToolbarAction) {
    val colors = LocalViewerColors.current
    val color = if (action.destructive) colors.toolbarDestructive else colors.toolbarContent
    Column(
        modifier = Modifier
            .sizeIn(minWidth = if (ViewerUiPrefs.gloveMode) 64.dp else 56.dp, minHeight = if (ViewerUiPrefs.gloveMode) 60.dp else 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = action.enabled, role = Role.Button, onClickLabel = action.label, onClick = action.onClick)
            .alpha(if (action.enabled) 1f else 0.4f)
            .let { if (action.tag != null) it.testTag(action.tag) else it }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(action.icon, contentDescription = action.contentDescription, tint = action.iconTint ?: color, modifier = Modifier.size(ViewerUiPrefs.icon))
        Spacer(Modifier.height(2.dp))
        Text(action.label, color = color, textAlign = TextAlign.Center, maxLines = 1,
            style = if (ViewerUiPrefs.gloveMode) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall)
    }
}
