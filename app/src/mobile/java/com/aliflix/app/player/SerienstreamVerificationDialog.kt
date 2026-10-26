package com.aliflix.app.player

import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aliflix.app.ui.common.AliflixCorners
import com.aliflix.app.ui.theme.*

/** The provider's real challenge stays visible and is completed by the viewer. */
@Composable
internal fun SerienstreamVerificationDialog(url: String, language: String, playUrl: String, onVerified: (String) -> Unit, dismiss: () -> Unit) {
    val latestVerified by rememberUpdatedState(onVerified)
    val latestDismiss by rememberUpdatedState(dismiss)
    var view by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { view?.stopLoading(); view?.destroy(); view = null } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.88f), shape = AliflixCorners.Chrome, color = AliflixBackgroundBase) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Verify Serienstream", Modifier.weight(1f), color = AliflixContentPrimary, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "Close verification", tint = AliflixContentPrimary) }
                }
                Text("Complete the provider's check to continue with this episode and audio language.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = AliflixContentSecondary, style = MaterialTheme.typography.bodySmall)
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                    SerienstreamBrowserSession.createView(context, url, language, playUrl,
                        { latestVerified(it) }, { view = null; latestDismiss() }).also { view = it }
                })
            }
        }
    }
}
