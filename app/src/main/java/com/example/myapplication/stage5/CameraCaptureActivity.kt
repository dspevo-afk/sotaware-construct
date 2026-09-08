package com.example.myapplication.stage5

import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val CAMERA_CAPTURE_OPERATION_ID_EXTRA =
    "com.sotaware.construct.stage5.CAMERA_OPERATION_ID"
const val CAMERA_CAPTURE_OPERATION_STATUS_EXTRA =
    "com.sotaware.construct.stage5.CAMERA_OPERATION_STATUS"
const val CAMERA_CAPTURE_OPERATION_ERROR_EXTRA =
    "com.sotaware.construct.stage5.CAMERA_OPERATION_ERROR"

/**
 * App-owned trampoline for the external camera contract.  MainActivity only
 * starts this Activity with an operation id; the durable journal supplies the
 * capture path and all other identity.  The ActivityResult callback commits
 * the result to that journal before returning to the document owner.
 */
class CameraCaptureActivity : ComponentActivity() {
    private var operationId: String? = null
    private var launchDispatched = false

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        recordExternalResult(success)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        operationId = savedInstanceState?.getString(KEY_OPERATION_ID)
            ?: intent.getStringExtra(CAMERA_CAPTURE_OPERATION_ID_EXTRA)
        launchDispatched = savedInstanceState?.getBoolean(KEY_LAUNCH_DISPATCHED) == true

        val id = operationId
        if (id == null) {
            finishWithError()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val action = try {
                prepareStartup(id, savedInstanceState != null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                StartupAction.ReturnError
            }
            withContext(Dispatchers.Main.immediate) {
                dispatchStartup(action)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_OPERATION_ID, operationId)
        outState.putBoolean(KEY_LAUNCH_DISPATCHED, launchDispatched)
        super.onSaveInstanceState(outState)
    }

    private fun prepareStartup(
        id: String,
        restoredActivity: Boolean
    ): StartupAction {
        CameraCaptureOperationStore(filesDir).use { store ->
            val record = store.readOperation()
                ?: throw CameraCaptureOperationNotFoundException("camera operation is unavailable")
            if (record.operationId != id) {
                throw CameraCaptureOperationConflictException(
                    "camera operation id does not match the durable journal"
                )
            }
            return when (record.status) {
                CameraCaptureOperationStatus.PREPARED -> {
                    // Mark LAUNCHED before invoking the external Activity.  If
                    // launch itself fails, recovery retains this record and no
                    // later caller can accidentally create a new operation.
                    store.markLaunched(id)
                    StartupAction.Launch(store.captureFile(id))
                }
                CameraCaptureOperationStatus.LAUNCHED -> {
                    // A restored ActivityResult registry may still deliver the
                    // original result. Never launch a second camera for this
                    // operation. A cold re-entry leaves the record for the
                    // document recovery coordinator instead.
                    if (restoredActivity || launchDispatched) {
                        StartupAction.WaitForRestoredResult
                    } else {
                        StartupAction.ReturnPending
                    }
                }
                CameraCaptureOperationStatus.RESULT_AVAILABLE,
                CameraCaptureOperationStatus.RESULT_CANCELLED,
                CameraCaptureOperationStatus.PROCESSING,
                CameraCaptureOperationStatus.PUBLISHED,
                CameraCaptureOperationStatus.COMMITTED,
                CameraCaptureOperationStatus.DISCARDED -> StartupAction.ReturnRecord(record)
            }
        }
    }

    private fun dispatchStartup(action: StartupAction) {
        if (isFinishing) return
        when (action) {
            is StartupAction.Launch -> {
                val id = operationId ?: return finishWithError()
                launchDispatched = true
                val uri = try {
                    FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        action.captureFile
                    )
                } catch (_: Throwable) {
                    return finishWithError()
                }
                try {
                    takePictureLauncher.launch(uri)
                } catch (_: Throwable) {
                    // The durable LAUNCHED record remains for explicit
                    // recovery; do not mark it cancelled while the external
                    // ownership outcome is unknown.
                    finishWithError()
                }
            }
            StartupAction.WaitForRestoredResult -> Unit
            StartupAction.ReturnPending -> finishWithStatus(
                resultCode = RESULT_CANCELED,
                status = CameraCaptureOperationStatus.LAUNCHED
            )
            is StartupAction.ReturnRecord -> finishWithRecord(action.record)
            StartupAction.ReturnError -> finishWithError()
        }
    }

    private fun recordExternalResult(success: Boolean) {
        val id = operationId ?: return finishWithError()
        lifecycleScope.launch(Dispatchers.IO) {
            val record = try {
                CameraCaptureOperationStore(filesDir).use { store ->
                    // This call durably publishes RESULT_AVAILABLE or
                    // RESULT_CANCELLED before the Activity result is returned.
                    store.recordResult(id, success)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            withContext(Dispatchers.Main.immediate) {
                if (record == null) finishWithError() else finishWithRecord(record)
            }
        }
    }

    private fun finishWithRecord(record: CameraCaptureOperationRecord) {
        val resultCode = if (record.result == CameraCaptureResult.CANCELLED) {
            RESULT_CANCELED
        } else {
            RESULT_OK
        }
        finishWithStatus(resultCode, record.status)
    }

    private fun finishWithStatus(
        resultCode: Int,
        status: CameraCaptureOperationStatus
    ) {
        setResult(
            resultCode,
            Intent().apply {
                putExtra(CAMERA_CAPTURE_OPERATION_ID_EXTRA, operationId)
                putExtra(CAMERA_CAPTURE_OPERATION_STATUS_EXTRA, status.name)
            }
        )
        finish()
    }

    private fun finishWithError() {
        setResult(
            RESULT_CANCELED,
            Intent().apply {
                putExtra(CAMERA_CAPTURE_OPERATION_ID_EXTRA, operationId)
                putExtra(
                    CAMERA_CAPTURE_OPERATION_ERROR_EXTRA,
                    "camera operation remains available for recovery"
                )
            }
        )
        finish()
    }

    private sealed interface StartupAction {
        data class Launch(val captureFile: java.io.File) : StartupAction
        data class ReturnRecord(val record: CameraCaptureOperationRecord) : StartupAction
        data object WaitForRestoredResult : StartupAction
        data object ReturnPending : StartupAction
        data object ReturnError : StartupAction
    }

    companion object {
        private const val KEY_OPERATION_ID = "camera.operation.id"
        private const val KEY_LAUNCH_DISPATCHED = "camera.launch.dispatched"

        /** Builds the explicit, app-internal intent used by the document owner. */
        fun intentFor(context: Context, operationId: String): Intent {
            requireCameraUuid(operationId, "camera operation id")
            return Intent(context, CameraCaptureActivity::class.java).apply {
                putExtra(CAMERA_CAPTURE_OPERATION_ID_EXTRA, operationId)
            }
        }
    }
}
