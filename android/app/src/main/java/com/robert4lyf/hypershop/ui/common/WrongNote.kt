package com.robert4lyf.hypershop.ui.common

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import timber.log.Timber

/**
 * W.4: "This is wrong" at the foot of a sheet. Asks what you expected, then saves the note on the phone; "Save and
 * share" also opens the share sheet. Nothing is sent otherwise.
 */
@Composable
fun WrongNote(onSave: (expected: String, share: Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("wrong")) {
        Icon(Icons.Rounded.Flag, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("This is wrong")
    }
    if (!open) return

    fun done(share: Boolean) {
        onSave(text, share)
        open = false
        text = ""
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("What did you expect?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Saved on this phone with the recipe line and how it was read. Share it from here or from Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    text,
                    { text = it },
                    placeholder = { Text("e.g. 1 jar of olives") },
                    modifier = Modifier.testTag("wrong-text"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { done(true) }, Modifier.testTag("wrong-share")) { Text("Save and share") } },
        dismissButton = { TextButton(onClick = { done(false) }, Modifier.testTag("wrong-save")) { Text("Save") } },
    )
}

/** The share sheet for a note's text: the only way one leaves the phone. */
fun shareNote(
    context: Context,
    text: String,
) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    // No app to share to, or a refusal from the system: the note is saved either way, so nothing to crash over.
    runCatching { context.startActivity(Intent.createChooser(send, "Share what was wrong")) }
        .onFailure { Timber.w(it, "share of wrong notes failed") }
}
