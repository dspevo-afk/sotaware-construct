package com.example.myapplication.stage9

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract

internal data class DriveAuthorizationResolutionRequest(
    val operationId: String,
    val intentSender: IntentSender
)

internal data class DriveAuthorizationResolutionResult(
    val operationId: String?,
    val resultCode: Int,
    val providerData: Intent?
)

/**
 * Routes Google consent through an app-owned activity so the result carries
 * the immutable operation id that launched it. Android's generic
 * StartIntentSenderForResult contract returns provider data only, so a single
 * restored launcher cannot otherwise distinguish an old result from a newer
 * account attempt after process recreation.
 */
internal class DriveAuthorizationResolutionContract :
    ActivityResultContract<DriveAuthorizationResolutionRequest, DriveAuthorizationResolutionResult>() {

    override fun createIntent(context: Context, input: DriveAuthorizationResolutionRequest): Intent {
        require(input.operationId.isNotBlank()) { "Drive authorization operation id is required" }
        return Intent(context, DriveAuthorizationResolutionActivity::class.java)
            .putExtra(EXTRA_OPERATION_ID, input.operationId)
            .putExtra(EXTRA_INTENT_SENDER, input.intentSender)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): DriveAuthorizationResolutionResult =
        DriveAuthorizationResolutionResult(
            operationId = intent?.getStringExtra(EXTRA_OPERATION_ID),
            resultCode = resultCode,
            providerData = intent?.parcelableExtra(EXTRA_PROVIDER_DATA, Intent::class.java)
        )
}

/** Non-exported trampoline that preserves the launching operation id. */
internal class DriveAuthorizationResolutionActivity : Activity() {
    private var operationId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        operationId = savedInstanceState?.getString(STATE_OPERATION_ID)
            ?: intent.getStringExtra(EXTRA_OPERATION_ID)

        val currentOperationId = operationId
        if (currentOperationId.isNullOrBlank()) {
            finishWithResult(RESULT_CANCELED, null)
            return
        }

        if (savedInstanceState == null) {
            val sender = intent.parcelableExtra(EXTRA_INTENT_SENDER, IntentSender::class.java)
            if (sender == null) {
                finishWithResult(RESULT_CANCELED, null)
                return
            }
            try {
                @Suppress("DEPRECATION")
                startIntentSenderForResult(sender, CONSENT_REQUEST_CODE, null, 0, 0, 0)
            } catch (_: IntentSender.SendIntentException) {
                finishWithResult(RESULT_CANCELED, null)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        operationId?.let { outState.putString(STATE_OPERATION_ID, it) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated by the Android framework; required to proxy an IntentSender result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == CONSENT_REQUEST_CODE) {
            finishWithResult(resultCode, data)
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun finishWithResult(resultCode: Int, providerData: Intent?) {
        val output = Intent().putExtra(EXTRA_OPERATION_ID, operationId)
        providerData?.let { output.putExtra(EXTRA_PROVIDER_DATA, it) }
        setResult(resultCode, output)
        finish()
    }
}

@Suppress("DEPRECATION")
private fun <T> Intent.parcelableExtra(name: String, type: Class<T>): T? where T : android.os.Parcelable =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, type)
    } else {
        getParcelableExtra(name) as? T
    }

private const val EXTRA_OPERATION_ID =
    "com.sotaware.construct.stage9.extra.AUTHORIZATION_OPERATION_ID"
private const val EXTRA_INTENT_SENDER =
    "com.sotaware.construct.stage9.extra.AUTHORIZATION_INTENT_SENDER"
private const val EXTRA_PROVIDER_DATA =
    "com.sotaware.construct.stage9.extra.AUTHORIZATION_PROVIDER_DATA"
private const val STATE_OPERATION_ID =
    "com.sotaware.construct.stage9.state.AUTHORIZATION_OPERATION_ID"
private const val CONSENT_REQUEST_CODE = 9013
