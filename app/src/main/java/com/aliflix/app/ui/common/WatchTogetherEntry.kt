package com.aliflix.app.ui.common

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.ui.unit.dp
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.player.nativeJson

@Composable internal fun WatchTogetherEntry(selection: PlaybackSelection) {
    if (BuildConfig.IS_TV) return
    val context = LocalContext.current
    TextButton(onClick = {
        val preferences = com.aliflix.app.data.PlaybackProviderRepository(context).preferences.value
        val prepared = selection.copy(source = preferences.sourceFor(selection.media))
        context.startActivity(Intent().setClassName(context, "com.aliflix.app.party.WatchPartyActivity").putExtra("selection", prepared.nativeJson()))
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Rounded.Groups, null); Spacer(Modifier.width(10.dp)); Text("Watch Together")
    }
}
