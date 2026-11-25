package com.example.myapplication.ui

import android.graphics.RectF
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.myapplication.R

/** Search hits in reading order: by sheet, then top to bottom, then left to right. */
fun orderedSearchMatches(highlights: Map<Int, List<RectF>>): List<Pair<Int, RectF>> =
    highlights.entries.sortedBy { it.key }.flatMap { (page, rects) ->
        rects.sortedWith(compareBy<RectF>({ it.top }, { it.left })).map { page to it }
    }

/** The first match on [page] or after it (wrapping to the start), for a fresh result. */
fun firstMatchFrom(matches: List<Pair<Int, RectF>>, page: Int): Int =
    matches.indexOfFirst { it.first >= page }.takeIf { it >= 0 } ?: 0

/**
 * The viewer's search: a bar over the sheet with the word, a "This sheet / All sheets"
 * choice, the match count and previous/next arrows. It replaces the old search dialogs.
 */
@Composable
fun ViewerSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    allSheets: Boolean,
    onAllSheetsChange: (Boolean) -> Unit,
    searching: Boolean,
    searched: Boolean,
    matchIndex: Int,
    matchCount: Int,
    onSubmit: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(
        modifier = modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("sotaware.viewer.search-bar"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_term_label)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit() }),
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("sotaware.viewer.search-field")
            )
            FilterChip(
                selected = allSheets,
                onClick = { onAllSheetsChange(!allSheets) },
                label = { Text(stringResource(if (allSheets) R.string.search_scope_all else R.string.search_scope_sheet)) }
            )
            when {
                searching -> CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
                searched -> Text(
                    if (matchCount == 0) stringResource(R.string.search_no_matches)
                    else stringResource(R.string.search_match_position, matchIndex + 1, matchCount),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("sotaware.viewer.search-count")
                )
            }
            IconButton(onClick = onPrevious, enabled = matchCount > 1) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.search_previous))
            }
            IconButton(onClick = onNext, enabled = matchCount > 1) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.search_next))
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.search_close)) }
        }
    }
}
