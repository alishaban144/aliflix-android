package com.aliflix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aliflix.app.data.RamoflixConfig

@Composable
fun RamoflixUrlDialog(
    currentUrl: String,
    defaultUrl: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    val isCustomUrl = currentUrl.trimEnd('/') != defaultUrl.trimEnd('/')
    val normalizedUrl = RamoflixConfig.normalizeBaseUrl(url)
    val invalidUrl = url.isNotBlank() && normalizedUrl == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ramoflix URL") },
        text = {
            Column {
                Text(
                    "Playback always opens Ramoflix. Change this address only if its domain moves.",
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Website URL") },
                    placeholder = { Text(defaultUrl) },
                    singleLine = true,
                    isError = invalidUrl,
                    supportingText = if (invalidUrl) {
                        { Text("Enter a valid HTTPS website address.") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { normalizedUrl?.let(onSave) },
                enabled = normalizedUrl != null,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            if (isCustomUrl) {
                TextButton(onClick = onReset) {
                    Text("Reset")
                }
            }
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
