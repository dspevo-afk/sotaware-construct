package com.example.myapplication.ui

import com.example.myapplication.ui.theme.LocalViewerColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.R
import com.example.myapplication.SubDrawing
import com.example.myapplication.SubDrawingLabels
import com.example.myapplication.SubDrawingPalette

/**
 * The screens a sub-drawing adds to the page viewer: the strip naming the open crop,
 * and the two name dialogs. They are pure presentation over callbacks - no state of
 * their own - so the viewer keeps one owner for crop state and this file owns how
 * crops are named and how the open crop is labelled.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
object SubDrawingOverlay {
    /**
     * The strip naming the open crop: its colour, its label ("104 (E1)"), Rename, and
     * Back to page. It sits at the top of the viewer and clips its label so a long room
     * name shrinks to fit instead of pushing the buttons under the page bubble.
     */
    @Composable
    fun OpenCropStrip(
        sub: SubDrawing,
        ordinal: Int,
        sheetLabel: String,
        onRename: () -> Unit,
        onClose: () -> Unit
    ) {
        val context = LocalContext.current
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.imeAnimationSource)
                    .padding(horizontal = 8.dp, vertical = 60.dp)
                    .shadow(8.dp)
                    .background(LocalViewerColors.current.overlayScrim, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("sotaware.pdf.sub-drawing-toolbar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(Color(SubDrawingPalette.colorArgb(sub.colorIndex)))
                )
                Text(
                    SubDrawingLabels.open(sub, sheetLabel, ordinal) { index ->
                        // Plain string lookup: the label builder is shared with tests and
                        // is not composable, so it takes its words as a callback.
                        context.getString(R.string.sub_drawing_item_numbered, index)
                    },
                    color = LocalViewerColors.current.accentOnDark,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                TextButton(
                    onClick = onRename,
                    modifier = Modifier.testTag("sotaware.pdf.sub-drawing-rename"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Edit, stringResource(R.string.sub_drawing_rename), Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sub_drawing_rename), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("sotaware.pdf.sub-drawing-close"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Close, stringResource(R.string.sub_drawing_close), Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sub_drawing_close), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    /**
     * Naming a crop the viewer just created: Save keeps it under the typed room name,
     * Cancel throws it away through [onCancel], so Undo never brings a discarded crop
     * back. Keyboard Done saves.
     */
    @Composable
    fun NameNewCropDialog(
        input: String,
        onInputChange: (String) -> Unit,
        onSave: () -> Unit,
        onCancel: () -> Unit
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.sub_drawing_name_title)) },
            text = { NameField(input, onInputChange, onSave) },
            confirmButton = {
                TextButton(
                    onClick = {
                        keyboard?.hide()
                        onSave()
                    },
                    modifier = Modifier.testTag("sotaware.pdf.sub-drawing-name-save")
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        keyboard?.hide()
                        onCancel()
                    },
                    modifier = Modifier.testTag("sotaware.pdf.sub-drawing-name-cancel")
                ) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    /**
     * Renaming the crop that is open. Cancel keeps the existing name - nothing here
     * discards the crop, and the hint says so. Keyboard Done saves.
     */
    @Composable
    fun RenameCropDialog(
        input: String,
        onInputChange: (String) -> Unit,
        onSave: () -> Unit,
        onCancel: () -> Unit
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.sub_drawing_rename)) },
            text = { NameField(input, onInputChange, onSave, R.string.sub_drawing_rename_hint) },
            confirmButton = {
                TextButton(
                    onClick = {
                        keyboard?.hide()
                        onSave()
                    },
                    modifier = Modifier.testTag("sotaware.pdf.sub-drawing-rename-save")
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        keyboard?.hide()
                        onCancel()
                    },
                    modifier = Modifier.testTag("sotaware.pdf.sub-drawing-rename-cancel")
                ) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    /**
     * The shared name entry: the field, its hint, and the buttons scroll, and the
     * dialog rises above the keyboard, because on a landscape phone the keyboard covers
     * the bottom of the screen so OK must be able to move up to stay reachable.
     */
    @Composable
    private fun NameField(
        input: String,
        onInputChange: (String) -> Unit,
        onDone: () -> Unit,
        hintRes: Int = R.string.sub_drawing_name_hint
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                singleLine = true,
                label = { Text(stringResource(R.string.sub_drawing_name_label)) },
                supportingText = { Text(stringResource(hintRes)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    onDone()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(if (hintRes == R.string.sub_drawing_rename_hint) "sotaware.pdf.sub-drawing-rename-field" else "sotaware.pdf.sub-drawing-name-field")
            )
        }
    }
}
