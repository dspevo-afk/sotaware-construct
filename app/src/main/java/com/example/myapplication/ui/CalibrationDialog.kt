package com.example.myapplication.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.myapplication.R
import com.example.myapplication.stage8.CalibrationError
import com.example.myapplication.stage8.CalibrationInput
import com.example.myapplication.stage8.CalibrationScaleResult
import com.example.myapplication.stage8.calculatePageScale
import com.example.myapplication.stage8.parseCalibrationInput

/** Validation is completed before the owning document receives any mutation. */
@Composable
internal fun CalibrationDialog(
    input: String,
    pixelDistance: Float?,
    onInputChange: (String) -> Unit,
    onScaleDefined: (pixels: Float, feet: Float) -> Boolean,
    onDismiss: () -> Unit,
    onAccepted: () -> Unit
) {
    var error by remember(input, pixelDistance) { mutableStateOf<CalibrationError?>(null) }
    var staleOwner by remember(input, pixelDistance) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scale_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.scale_dialog_distance_prompt))
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text(stringResource(R.string.scale_dialog_distance_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null || staleOwner,
                    singleLine = true
                )
                error?.let { failure ->
                    Text(stringResource(calibrationErrorResource(failure)),
                        color = MaterialTheme.colorScheme.error)
                }
                if (staleOwner) {
                    Text(stringResource(R.string.calibration_error_session),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when (val parsed = parseCalibrationInput(input)) {
                    is CalibrationInput.Rejected -> error = parsed.error
                    is CalibrationInput.Accepted -> {
                        val pixels = pixelDistance
                        if (pixels == null) {
                            error = CalibrationError.DEGENERATE_PIXEL_DISTANCE
                        } else {
                            when (val result = calculatePageScale(pixels, parsed.feet)) {
                                is CalibrationScaleResult.Rejected -> error = result.error
                                is CalibrationScaleResult.Accepted -> {
                                    if (onScaleDefined(pixels, parsed.feet)) onAccepted()
                                    else staleOwner = true
                                }
                            }
                        }
                    }
                }
            }) { Text(stringResource(R.string.scale_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.clear_page_cancel)) }
        }
    )
}

private fun calibrationErrorResource(error: CalibrationError): Int = when (error) {
    CalibrationError.EMPTY_INPUT -> R.string.calibration_error_empty
    CalibrationError.INPUT_TOO_LONG -> R.string.calibration_error_too_long
    CalibrationError.INVALID_FORMAT -> R.string.calibration_error_format
    CalibrationError.NON_FINITE -> R.string.calibration_error_non_finite
    CalibrationError.SUBNORMAL -> R.string.calibration_error_too_small
    CalibrationError.NON_POSITIVE -> R.string.calibration_error_non_positive
    CalibrationError.OUT_OF_RANGE -> R.string.calibration_error_out_of_range
    CalibrationError.DEGENERATE_PIXEL_DISTANCE -> R.string.calibration_error_pixel_distance
    CalibrationError.INVALID_PAGE_SCALE -> R.string.calibration_error_scale
}
