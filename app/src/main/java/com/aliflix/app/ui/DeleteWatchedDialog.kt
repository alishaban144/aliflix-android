package com.aliflix.app.ui

import com.aliflix.app.ui.common.*

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.aliflix.app.downloads.downloadAtmosphere
import com.aliflix.app.model.Media
import com.aliflix.app.ui.theme.*

@Composable internal fun DeleteWatchedDialog(media: Media, onDismiss: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        AliflixSheet() {
            Column(Modifier.downloadAtmosphere().padding(AliflixSpacing.Large), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(media.posterUrl, null, Modifier.size(48.dp, 72.dp).clip(AliflixCorners.Small), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Delete from watched?", style = MaterialTheme.typography.titleLarge)
                        Text(media.title, style = MaterialTheme.typography.bodyMedium, color = AliflixContentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = AliflixCorners.Card, border = null) { Text("Cancel") }
                    Button(onClick = onDelete, modifier = Modifier.weight(1f).height(48.dp), shape = AliflixCorners.Card, colors = ButtonDefaults.buttonColors(containerColor = AliflixError)) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Delete")
                    }
                }
            }
        }
    }
}
