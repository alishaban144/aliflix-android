package com.aliflix.app.party

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import com.aliflix.app.ui.common.AliflixMotion
import com.aliflix.app.ui.common.AliflixSurfaceDefaults
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.player.*
import com.aliflix.app.ui.common.rememberMobileMotionEnabled
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class WatchPartyActivity : ComponentActivity() {
    private val client by lazy { WatchPartyClient.get(this) }
    private var selected by mutableStateOf<PlaybackSelection?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)); accept(intent)
        setContent { AliflixMobileTheme { PartyRoom(client, selected, onClose = ::finish, onRetryJoin = { accept(intent) }, onWatch = {
            val content = client.state.value.content ?: return@PartyRoom
            val preferences = PlaybackProviderRepository(this).preferences.value
            val mapped = PartyProtocol.selection(content, preferences)
            val local = NativePlaybackService.activeRequest?.selectionJson?.let { runCatching { nativeSelection(it) }.getOrNull() }
                ?.takeIf { it.media.key == mapped.media.key } ?: selected?.takeIf { it.media.key == mapped.media.key }
            val target = selected?.takeIf { PartyProtocol.matches(content, it) } ?: mapped.copy(
                media = local?.media ?: mapped.media,
                source = local?.source?.takeIf { it.identity.isAvailableFor(mapped.media) } ?: mapped.source,
                availableEpisodes = local?.availableEpisodes.orEmpty())
            client.status("waiting")
            launchNativeSelection(this, target, preferences.preferredSubtitleLanguage.code, preferences.autoDisplaySubtitles)
            finish()
        }) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); accept(intent) }
    private fun accept(intent: Intent) {
        selected = intent.getStringExtra("selection")?.let { runCatching { nativeSelection(it) }.getOrNull() }
        val uri = intent.data ?: return
        if (uri.scheme != "https" || uri.host != android.net.Uri.parse(WatchPartyClient.BASE).host ||
            !uri.path.orEmpty().matches(Regex("/party/[a-f0-9]{32}")) || uri.query != null) return
        val room = uri.lastPathSegment ?: return
        if (client.state.value.room == room && client.state.value.active) return
        lifecycleScope.launch { client.join(room, uri.fragment ?: intent.getStringExtra("partyInvite").orEmpty()) }
    }
}

@Composable internal fun PartyIndicator(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by remember(context) { WatchPartyClient.get(context) }.state.collectAsState()
    if (!state.active) return
    Surface(onClick = onClick, color = AliflixSurfacePrimary.copy(alpha = .92f), shape = RoundedCornerShape(16.dp), modifier = modifier.heightIn(min = 48.dp).testTag("party-indicator")) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (state.phase == "reconnecting") Icons.Rounded.Sync else Icons.Rounded.Groups, "Watch Together", tint = AliflixAccentSecondary, modifier = Modifier.size(20.dp))
            Text(if (state.phase == "reconnecting") "Reconnecting" else "${state.members.size}", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PartyRoom(client: WatchPartyClient, selection: PlaybackSelection?, onClose: () -> Unit, onRetryJoin: () -> Unit, onWatch: () -> Unit) {
    val state by client.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motion = rememberMobileMotionEnabled()
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    var confirm by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf<String?>(null) }
    val content = state.content
    val title = content?.optString("title") ?: selection?.media?.title.orEmpty()
    val poster = content?.optString("poster")?.takeIf { it.startsWith('/') }?.let { "https://image.tmdb.org/t/p/w500$it" } ?: selection?.media?.posterUrl
    var ambient by remember(poster) { mutableStateOf(AliflixAccentSecondary) }
    val posterRequest = remember(context, poster) { coil.request.ImageRequest.Builder(context).data(poster).allowHardware(false).build() }
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { events ->
        try {
            events.collect { backProgress = it.progress }
            if (permissions != null) permissions = null else if (confirm) confirm = false else onClose()
        } finally { backProgress = 0f }
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val scale = if (motion) 1f - .035f * backProgress else 1f
        scaleX = scale; scaleY = scale
    }.background(AliflixBackgroundBase).testTag("party-room")) {
        Box(Modifier.fillMaxWidth().height(420.dp).background(Brush.verticalGradient(listOf(ambient.copy(alpha = .20f), Color.Transparent))))
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Watch Together", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (state.active) IconButton(onClick = { confirm = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Logout, "Leave room") }
            }
            if (poster != null || title.isNotBlank()) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AsyncImage(posterRequest, null, onSuccess = { result ->
                    val bitmap = (result.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null) scope.launch(Dispatchers.Default) {
                        var red = 0L; var green = 0L; var blue = 0L; var count = 0L
                        for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 16)) for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 16)) {
                            val pixel = bitmap.getPixel(x, y)
                            red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel); blue += android.graphics.Color.blue(pixel); count++
                        }
                        if (count > 0) withContext(Dispatchers.Main) { ambient = androidx.compose.ui.graphics.lerp(Color(android.graphics.Color.rgb((red/count).toInt(), (green/count).toInt(), (blue/count).toInt())), AliflixAccentSecondary, .35f) }
                    }
                }, contentScale = ContentScale.Crop, modifier = Modifier.width(if (landscape) 88.dp else 144.dp).height(if (landscape) 128.dp else 208.dp).clip(RoundedCornerShape(24.dp)))
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (content?.optString("type") == "tv" || selection?.media?.type == com.aliflix.app.model.MediaType.TV)
                    Text("S${content?.optInt("season") ?: selection?.seasonNumber ?: 1} · E${content?.optInt("episode") ?: selection?.episodeNumber ?: 1}", color = AliflixAccentSecondary)
            }
            AnimatedContent(targetState = when { confirm -> "confirm"; permissions != null -> "permissions"; state.active && content != null -> "room"; else -> "create" },
                transitionSpec = { if (motion) (fadeIn() + scaleIn(initialScale = .96f, animationSpec = spring(dampingRatio = .85f))) togetherWith fadeOut() else fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap()) }, label = "party-stage") { page ->
                Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (page) {
                        "confirm" -> {
                            Text(if (state.host) "Leave or end room?" else "Leave room?", style = MaterialTheme.typography.titleLarge)
                            PartyAction("Leave", Icons.Rounded.Logout) { client.leave(); onClose() }
                            if (state.host) PartyAction("End for everyone", Icons.Rounded.Close) { client.leave(end = true); onClose() }
                            TextButton(onClick = { confirm = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stay") }
                        }
                        "permissions" -> {
                            val member = state.members.firstOrNull { it.optString("id") == permissions }
                            Text(member?.optString("name").orEmpty(), style = MaterialTheme.typography.titleLarge)
                            listOf("play" to "Play", "pause" to "Pause", "seek" to "Seek").forEach { (key, label) ->
                                val checked = member?.optJSONObject("rights")?.optBoolean(key) == true
                                PartyToggle(label, checked) { enabled -> client.command("permission") { put("member", permissions); put("rights", JSONObject(member?.optJSONObject("rights").toString()).put(key, enabled)) } }
                            }
                            PartyAction("Make host", Icons.Rounded.VerifiedUser) { client.command("transfer") { put("member", permissions) }; permissions = null }
                            TextButton(onClick = { permissions = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
                        }
                        "room" -> {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (state.phase == "reconnecting") "Reconnecting" else "Private room", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("${state.members.size}/8", color = AliflixAccentSecondary)
                            }
                            state.members.forEachIndexed { index, member ->
                                Surface(color = AliflixSurfaceDefaults.color(), shape = RoundedCornerShape(20.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Box(Modifier.size(44.dp).background(AliflixAccentSecondary.copy(alpha = .12f + index * .025f), CircleShape), contentAlignment = Alignment.Center) {
                                            Text(member.optString("name", "G").take(1).uppercase(), color = AliflixAccentSecondary, fontWeight = FontWeight.Bold)
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(member.optString("name") + if (member.optString("id") == state.self) " · You" else "", fontWeight = FontWeight.Medium)
                                            Text(if (member.optString("id") == state.data?.optString("host")) "Host · ${member.optString("status").replace('-', ' ')}" else member.optString("status").replace('-', ' '), color = AliflixContentSecondary, fontSize = 12.sp)
                                        }
                                        if (state.host && member.optString("id") != state.self) IconButton(onClick = { permissions = member.optString("id") }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Tune, "Manage ${member.optString("name")}") }
                                    }
                                }
                            }
                            if (state.host) PartyToggle("Everyone controls", state.data?.optBoolean("everyone") == true) { value -> client.command("policy") { put("everyone", value) } }
                            PartyAction("Invite", Icons.Rounded.IosShare) {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, client.link()), "Invite"))
                            }
                            if (state.host) TextButton(onClick = { client.command("invite") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Renew invitation") }
                            if (state.phase == "reconnecting") PartyAction("Retry", Icons.Rounded.Sync) { client.reconnectNow() }
                            PartyAction("Ready", Icons.Rounded.PlayArrow, enabled = state.phase != "reconnecting", onClick = onWatch)
                        }
                        else -> {
                            val busy = state.phase in listOf("creating", "joining", "reconnecting")
                            if (selection != null && (state.error != null || state.phase.isNotBlank())) Text(state.label, color = AliflixAccentSecondary, modifier = Modifier.fillMaxWidth().padding(8.dp))
                            if (selection != null) PartyAction(if (busy) state.label else "Create room", Icons.Rounded.Groups, enabled = !busy, busy = busy) { scope.launch { client.create(selection, NativePlaybackService.positionForParty(selection) ?: 0) } }
                            else if (!busy) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Icon(if (state.phase == "failed") Icons.Rounded.CloudOff else Icons.Rounded.LinkOff, null, Modifier.size(64.dp), tint = AliflixAccentSecondary)
                                    Text(state.label, style = MaterialTheme.typography.headlineSmall, color = AliflixAccentSecondary)
                                    if (state.phase == "failed") PartyAction("Retry", Icons.Rounded.Refresh, onClick = onRetryJoin)
                                    else PartyAction("Close", Icons.Rounded.Close, onClick = onClose)
                                }
                            }
                            else if (busy) Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = AliflixAccentSecondary, strokeWidth = 2.dp) }
                        }
                    }
                    if (page != "create" && state.error != null) Text(state.error.orEmpty(), color = AliflixAccentSecondary)
                }
            }
        }
    }
}

@Composable private fun PartyAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = rememberMobileMotionEnabled()
    val scale by animateFloatAsState(if (pressed && motion) .975f else 1f, AliflixMotion.press(), label = "party-press")
    Button(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer { scaleX = scale; scaleY = scale }, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentSecondary, contentColor = AliflixBackgroundBase)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(icon, null, Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp)); Text(label, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun PartyToggle(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Surface(onClick = { update(!checked) }, shape = RoundedCornerShape(18.dp), color = AliflixSurfacePrimary) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); com.aliflix.app.ui.common.AliflixLiquidToggle(checked, null)
        }
    }
}
