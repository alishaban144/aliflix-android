package com.aliflix.app.player

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.party.PartyProtocol
import com.aliflix.app.party.WatchPartyClient
import com.aliflix.app.ui.common.rememberMobileMotionEnabled
import com.aliflix.app.ui.theme.*
import java.util.WeakHashMap

private val roomPrompts = WeakHashMap<Activity, ComposeView>()

/** All entry points share one consent boundary before replacing a party's player. */
internal fun requestPlaybackRoomConsent(activity: Activity, selection: PlaybackSelection, proceed: () -> Unit) {
    val client = WatchPartyClient.get(activity)
    val state = client.state.value
    if (!state.active || PartyProtocol.matches(state.content, selection)) { proceed(); return }
    if (activity.isFinishing || activity.isDestroyed || roomPrompts.containsKey(activity)) return
    val room = state.room
    val root = activity.window.decorView as ViewGroup
    val view = ComposeView(activity)
    var consumed = false
    fun close() { if (consumed) return; consumed = true; roomPrompts.remove(activity); root.removeView(view); view.disposeComposition() }
    view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    roomPrompts[activity] = view
    view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: android.view.View) = Unit
        override fun onViewDetachedFromWindow(v: android.view.View) { roomPrompts.remove(activity) }
    })
    root.addView(view, ViewGroup.LayoutParams(1, 1))
    view.setContent { AliflixMobileTheme {
        RoomPlaybackConsent(selection, onStay = { close(); if (activity is NativePlayerActivity && activity.playbackUiState.playbackSelection == null) activity.finish() }, onLeave = {
            if (!consumed) {
                close()
                if (client.state.value.active && client.state.value.room != room)
                    requestPlaybackRoomConsent(activity, selection, proceed)
                else { client.leave(); proceed() }
            }
        })
    } }
}

@Composable internal fun RoomPlaybackConsent(selection: PlaybackSelection, onStay: () -> Unit, onLeave: () -> Unit) {
    val motion = rememberMobileMotionEnabled()
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(120).dp
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(if (entered || !motion) 1f else .94f, spring(dampingRatio = .86f), label = "room-consent")
    Dialog(onDismissRequest = onStay, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Brush.verticalGradient(listOf(AliflixAccentPrimary.copy(alpha = .20f), AliflixSurfacePrimary)), RoundedCornerShape(30.dp))
            .background(AliflixSurfacePrimary.copy(alpha = .94f), RoundedCornerShape(30.dp))
            .heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            .padding(24.dp).testTag("leave-party-consent"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Icon(Icons.Rounded.Groups, null, Modifier.size(32.dp), tint = AliflixAccentSecondary)
            Text("Leave Watch Together?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(selection.media.title, style = MaterialTheme.typography.titleMedium, color = AliflixContentSecondary)
            if (selection.seasonNumber != null && selection.episodeNumber != null)
                Text("S${selection.seasonNumber} · E${selection.episodeNumber}", color = AliflixAccentSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onStay, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Stay") }
                Button(onClick = onLeave, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1.5f).heightIn(min = 52.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Leave & play")
                }
            }
        }
    }
}
