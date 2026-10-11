package com.aliflix.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.ui.theme.AliflixAccentSecondary

private val SheetBackground = Color(0xFF12141B)
private val RowBackground = Color.White.copy(alpha = .035f)
private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private enum class MorePage { HOME, SOURCES, SERVERS, SPEED }

internal data class StreamingSourceOption(
    val provider: PlaybackProvider,
    val selected: Boolean = false,
    val detail: String = "",
)

internal data class ProviderServerDiscovery(
    val provider: PlaybackProvider? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val servers: List<MoviepireServerOption> = emptyList(),
)

/** Browsing is read-only; only choosing a server changes playback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobilePlayerMoreSheet(
    visible: Boolean,
    selection: PlaybackSelection,
    servers: List<MoviepireServerOption>,
    subtitlesActive: Boolean,
    subtitleLanguage: String?,
    onOpenSubtitles: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectServer: (MoviepireServerOption) -> Unit,
    onOpenProviderOptions: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    currentSpeed: Float? = null,
    onStopPlayback: (() -> Unit)? = null,
    onOpenWirelessDisplay: (() -> Unit)? = null,
    castActive: Boolean = false,
    onCast: (() -> Unit)? = null,
    providers: List<StreamingSourceOption> = emptyList(),
    onSelectProvider: (PlaybackProvider) -> Unit = {},
    providerDiscovery: ProviderServerDiscovery = ProviderServerDiscovery(),
    onRetryProviderServers: () -> Unit = {},
    onSelectProviderServer: (MoviepireServerOption) -> Unit = {},
    audioBoost: Boolean = false,
    audioBoostAvailability: AudioBoostAvailability = AudioBoostAvailability.OFF,
    onAudioBoostChange: ((Boolean) -> Unit)? = null,
) {
    // Removing the sheet resets navigation and scroll positions on its next opening.
    if (!visible) return
    var page by remember(selection.key) { mutableStateOf(MorePage.HOME) }
    var drilledProvider by remember(selection.key) { mutableStateOf<PlaybackProvider?>(null) }
    var rememberedSpeed by remember { mutableFloatStateOf(currentSpeed ?: 1f) }
    val activeSpeed = currentSpeed ?: rememberedSpeed
    val activeProvider = selection.source.identity
    val sources = providers.ifEmpty { listOf(StreamingSourceOption(activeProvider, selected = true)) }
    val selectedServer = servers.firstOrNull { it.selected }?.label
    val goBack = {
        page = if (page == MorePage.SERVERS) MorePage.SOURCES else MorePage.HOME
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("player-more-sheet"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 560.dp,
        containerColor = SheetBackground,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        scrimColor = Color.Black.copy(alpha = .48f),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = page == MorePage.HOME),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = .20f)) },
    ) {
        BackHandler(enabled = page != MorePage.HOME) { goBack() }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (page != MorePage.HOME) {
                IconButton(onClick = goBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, when (page) {
                        MorePage.SERVERS -> "Back to streaming sources"
                        else -> "Back to More"
                    })
                }
            } else Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(when (page) {
                    MorePage.HOME -> "More"
                    MorePage.SOURCES -> "Streaming source"
                    MorePage.SERVERS -> drilledProvider?.displayName.orEmpty()
                    MorePage.SPEED -> "Playback speed"
                }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Close, "Close More")
            }
        }
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
            label = "more-navigation",
            modifier = Modifier.weight(1f, fill = false),
        ) { currentPage ->
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (currentPage) {
                    MorePage.HOME -> {
                        MoreOptionRow(Icons.Rounded.Subtitles, "Audio & subtitles",
                            if (subtitlesActive) subtitleLanguage ?: "On" else "Off",
                            onClick = { onDismiss(); onOpenSubtitles() })
                        MoreOptionRow(Icons.Rounded.Speed, "Playback speed", speedLabel(activeSpeed),
                            onClick = { page = MorePage.SPEED })
                        onAudioBoostChange?.let { update ->
                            val detail = when {
                                !audioBoost -> "Off"
                                castActive -> "Unavailable while casting"
                                audioBoostAvailability == AudioBoostAvailability.ACTIVE -> "On"
                                audioBoostAvailability == AudioBoostAvailability.UNAVAILABLE -> "Unavailable"
                                else -> "Preparing…"
                            }
                            Surface(color = RowBackground, shape = RoundedCornerShape(16.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                        .toggleable(audioBoost, role = Role.Switch, onValueChange = update)
                                        .testTag("player-audio-boost-switch")
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.GraphicEq, null, tint = AliflixAccentSecondary, modifier = Modifier.size(22.dp))
                                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                        Text("Audio Boost", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        Text(detail, color = Color.White.copy(alpha = .60f), fontSize = 12.sp)
                                    }
                                    Switch(checked = audioBoost, onCheckedChange = null,
                                        colors = SwitchDefaults.colors(checkedTrackColor = AliflixAccentSecondary))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        MoreOptionRow(Icons.Rounded.Dns, "Streaming source",
                            listOfNotNull(activeProvider.displayName, selectedServer?.takeUnless { it == activeProvider.displayName }).joinToString(" · "),
                            onClick = { page = MorePage.SOURCES })
                        onCast?.let { action ->
                            MoreOptionRow(Icons.Rounded.Cast, "Cast", if (castActive) "Connected" else "",
                                onClick = { onDismiss(); action() })
                        }
                        onOpenWirelessDisplay?.let { action ->
                            MoreOptionRow(Icons.Rounded.Cast, "Wireless display", "",
                                onClick = { onDismiss(); action() })
                        }
                        onStopPlayback?.let { action ->
                            Spacer(Modifier.height(8.dp))
                            MoreOptionRow(Icons.Rounded.Stop, "Stop playback", "",
                                onClick = { onDismiss(); action() }, showChevron = false)
                        }
                    }
                    MorePage.SOURCES -> {
                        sources.forEach { option ->
                            MoreOptionRow(Icons.Rounded.Dns, option.provider.displayName,
                                if (option.selected) "Playing now" else "",
                                selected = option.selected,
                                onClick = {
                                    drilledProvider = option.provider
                                    page = MorePage.SERVERS
                                    onSelectProvider(option.provider)
                                })
                        }
                    }
                    MorePage.SERVERS -> {
                        val provider = drilledProvider
                        val isCurrent = provider == activeProvider
                        val matches = providerDiscovery.provider == provider
                        val options = if (isCurrent) servers else providerDiscovery.servers
                        when {
                            !isCurrent && (!matches || providerDiscovery.loading) -> {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(20.dp), color = AliflixAccentSecondary, strokeWidth = 2.dp)
                                    Text("Finding servers…", modifier = Modifier.padding(start = 14.dp), fontSize = 14.sp)
                                }
                            }
                            !isCurrent && (providerDiscovery.failed || options.isEmpty()) -> {
                                Text("Servers unavailable",
                                    color = Color.White.copy(alpha = .70f), fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 12.dp))
                                MoreOptionRow(Icons.Rounded.Refresh, "Try again", "",
                                    onClick = onRetryProviderServers)
                            }
                            options.isNotEmpty() -> options.forEach { server ->
                                MoreOptionRow(Icons.Rounded.PlayCircle, server.label,
                                    if (isCurrent && server.selected) "Playing now" else "",
                                    selected = isCurrent && server.selected,
                                    showChevron = false,
                                    onClick = {
                                        if (!(isCurrent && server.selected)) {
                                            if (isCurrent) onSelectServer(server) else onSelectProviderServer(server)
                                        }
                                        onDismiss()
                                    })
                            }
                            isCurrent && activeProvider == PlaybackProviderId.RAMOFLIX ->
                                MoreOptionRow(Icons.Rounded.Dns, "Server options", "",
                                    onClick = { onDismiss(); onOpenProviderOptions() })
                            else -> Text("No servers available",
                                color = Color.White.copy(alpha = .70f), fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                    MorePage.SPEED -> PlaybackSpeeds.forEach { speed ->
                        val selected = kotlin.math.abs(activeSpeed - speed) < .01f
                        Surface(color = if (selected) AliflixAccentSecondary.copy(alpha = .10f) else RowBackground,
                            shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected, role = Role.RadioButton, onClick = {
                                    rememberedSpeed = speed
                                    onSelectSpeed(speed)
                                    page = MorePage.HOME
                                }).padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(speedLabel(speed), Modifier.weight(1f), fontSize = 15.sp)
                                if (selected) Icon(Icons.Rounded.Check, "Selected", tint = AliflixAccentSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun speedLabel(speed: Float): String = if (speed == 1f) "Normal" else "${speed}×"

@Composable
private fun MoreOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    showChevron: Boolean = true,
) {
    Surface(
        onClick = onClick,
        color = if (selected) AliflixAccentSecondary.copy(alpha = .10f) else RowBackground,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) AliflixAccentSecondary else Color.White.copy(alpha = .8f),
                modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) Text(subtitle,
                    color = if (selected) AliflixAccentSecondary else Color.White.copy(alpha = .60f),
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Icon(Icons.Rounded.Check, "Playing now", tint = AliflixAccentSecondary, modifier = Modifier.size(18.dp))
            if (showChevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                tint = Color.White.copy(alpha = .40f), modifier = Modifier.size(20.dp))
        }
    }
}
