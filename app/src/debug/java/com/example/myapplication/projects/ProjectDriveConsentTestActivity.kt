package com.example.myapplication.projects

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider

/** Target-process, debug-only host for exercising the production consent saver. */
class ProjectDriveConsentTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = ViewModelProvider(this)[ProjectDriveConsentOwner::class.java]
        setContent { ProjectDriveConsentStateHarness(owner.nonce) }
    }
}

@Composable
private fun ProjectDriveConsentStateHarness(ownerNonce: String) {
    val initial = PendingProjectDriveConsent(
        operationId = "synthetic-operation",
        generation = 3L,
        accountSubject = "synthetic-project-account",
        ownerNonce = ownerNonce,
        startingBackupIdentitySubject = "synthetic-backup-account"
    )
    var pending by rememberSaveable(stateSaver = pendingProjectDriveConsentSaver) {
        mutableStateOf<PendingProjectDriveConsent?>(null)
    }
    MaterialTheme {
        Column {
            Button(onClick = { pending = initial }) { Text("Save pending consent") }
            Text(
                pending?.let {
                    "${it.operationId}/${it.ownerNonce}/${it.startingBackupIdentitySubject}"
                } ?: "No pending consent"
            )
        }
    }
}
