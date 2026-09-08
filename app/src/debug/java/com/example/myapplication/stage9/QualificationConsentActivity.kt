package com.example.myapplication.stage9

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Synthetic IntentSender target compiled only into debug/test artifacts. */
class QualificationConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(
            intent.getIntExtra(EXTRA_RESULT_CODE, RESULT_OK),
            Intent().putExtra(EXTRA_PROVIDER_MARKER, intent.getStringExtra(EXTRA_PROVIDER_MARKER))
        )
        finish()
    }

    companion object {
        const val EXTRA_RESULT_CODE =
            "com.sotaware.construct.test.stage9.extra.RESULT_CODE"
        const val EXTRA_PROVIDER_MARKER =
            "com.sotaware.construct.test.stage9.extra.PROVIDER_MARKER"
    }
}

/** Minimal debug-only host for proving ViewModel ownership across recreation. */
class QualificationViewModelActivity : ComponentActivity()
