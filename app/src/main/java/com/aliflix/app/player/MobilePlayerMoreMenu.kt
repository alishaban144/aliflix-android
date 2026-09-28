package com.aliflix.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection

private val SheetBackground = Color(0xFF131620)
private val RowBackground = Color(0xFF1C202C)
private val AliflixPurple = Color(0xFF6E59D9)

private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** Level one of the streaming picker: a streaming source the user can drill into. */
internal data class StreamingSourceOption(
    val provider: PlaybackProviderId,
    val selected: Boolean = false,
    val detail: String = "",
)

/** Level two of the streaming picker for a source that is not the one currently playing. */
internal data class ProviderServerDiscovery(
    val provider: PlaybackProviderId? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val servers: List<MoviepireServerOption> = emptyList(),
)

/**
 * Compact More menu for secondary functions (Audio & Subtitles, Speed, Servers).
 *
 * The streaming picker is two-level: sources first (CineJoy, Moviepire, Ramoflix…),
 * then that source's servers. Drilling in never touches playback; playback only changes
 * once a server is picked.
 */
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
    onSelectProvider: (PlaybackProviderId) -> Unit = {},
    providerDiscovery: ProviderServerDiscovery = ProviderServerDiscovery(),
    onRetryProviderServers: () -> Unit = {},
    onSelectProviderServer: (MoviepireServerOption) -> Unit = {},
) {

    var rememberedSpeed by remember { mutableFloatStateOf(1.0f) }
    val activeSpeed = currentSpeed ?: rememberedSpeed
    var drilledProvider by remember { mutableStateOf<PlaybackProviderId?>(null) }
    val drilled = drilledProvider
    val activeProvider = selection.source.provider

    BackHandler(enabled = visible && drilled != null) { drilledProvider = null }

    AnimatedVisibility(visible = visible, enter = fadeIn() + slideInVertically { it / 10 }, exit = fadeOut() + slideOutVertically { it / 10 }) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(SheetBackground)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (drilled != null && providers.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { drilledProvider = null },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to streaming sources",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (drilled == null || providers.isEmpty()) "PLAYBACK SETTINGS" else "STREAMING SERVER",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (drilled != null && providers.isNotEmpty()) {
                            Text(
                                text = drilled.displayName,
                                color = AliflixPurple,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Audio & Subtitles option
                MoreOptionRow(
                    icon = Icons.Rounded.Subtitles,
                    title = "Audio & Subtitles",
                    subtitle = if (subtitlesActive) subtitleLanguage ?: "Active" else "Off",
                    onClick = {
                        onDismiss()
                        onOpenSubtitles()
                    },
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (onCast != null) {
                    MoreOptionRow(
                        icon = Icons.Rounded.Cast,
                        title = "Cast",
                        subtitle = if (castActive) "Manage device" else "Choose device",
                        onClick = {
                            onDismiss()
                            onCast()
                        },
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Playback Speed option
                Text(
                    text = "Playback Speed",
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlaybackSpeeds.forEach { speed ->
                        val isSelected = kotlin.math.abs(activeSpeed - speed) < 0.01f
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AliflixPurple else RowBackground)
                                .clickable {
                                    rememberedSpeed = speed
                                    onSelectSpeed(speed)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${speed}x",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }

                // Streaming sources -> that source's servers (two-level picker)
                if (providers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    if (drilled == null) {
                        Text(
                            text = "Streaming Source",
                            color = Color.White.copy(alpha = 0.70f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            providers.forEach { option ->
                                StreamingSourceRow(option = option) {
                                    drilledProvider = option.provider
                                    onSelectProvider(option.provider)
                                }
                            }
                        }
                    } else if (drilled == activeProvider) {
                        Text(
                            text = "Streaming Server",
                            color = Color.White.copy(alpha = 0.70f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                        if (servers.isEmpty()) {
                            ServerBusyRow(label = "Loading ${drilled.displayName} servers…")
                        } else {
                            StreamingServerRows(servers = servers) { server ->
                                onSelectServer(server)
                                onDismiss()
                            }
                        }
                    } else {
                        Text(
                            text = "${drilled.displayName} Servers",
                            color = Color.White.copy(alpha = 0.70f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                        val discoveryMatches = providerDiscovery.provider == drilled
                        when {
                            !discoveryMatches || providerDiscovery.loading ->
                                ServerBusyRow(label = "Finding ${drilled.displayName} servers…")
                            providerDiscovery.failed ->
                                ServerErrorRow(onRetry = onRetryProviderServers)
                            providerDiscovery.servers.isEmpty() ->
                                Text(
                                    text = "No servers reported for ${drilled.displayName} right now.",
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                                )
                            else ->
                                StreamingServerRows(servers = providerDiscovery.servers) { server ->
                                    onSelectProviderServer(server)
                                    onDismiss()
                                }
                        }
                    }
                } else if (servers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Streaming Server",
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                    StreamingServerRows(servers = servers) { server ->
                        onSelectServer(server)
                        onDismiss()
                    }
                } else if (selection.source.provider == PlaybackProviderId.RAMOFLIX) {
                    Spacer(modifier = Modifier.height(16.dp))
                    MoreOptionRow(
                        icon = Icons.Rounded.Dns,
                        title = "Ramoflix Servers",
                        subtitle = "Switch streaming host",
                        onClick = {
                            onDismiss()
                            onOpenProviderOptions()
                        },
                    )
                }

                if (onOpenWirelessDisplay != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    MoreOptionRow(
                        icon = Icons.Rounded.Cast,
                        title = "Wireless Display",
                        subtitle = "Screen mirroring",
                        onClick = {
                            onDismiss()
                            onOpenWirelessDisplay()
                        },
                    )
                }

                if (onStopPlayback != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    MoreOptionRow(
                        icon = Icons.Rounded.Stop,
                        title = "Stop Playback",
                        subtitle = "End session",
                        onClick = {
                            onDismiss()
                            onStopPlayback()
                        },
                    )
                }
            }
        }
    }
}

}

@Composable
private fun MoreOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RowBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = subtitle,
            color = AliflixPurple,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StreamingSourceRow(
    option: StreamingSourceOption,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (option.selected) AliflixPurple.copy(alpha = 0.20f) else RowBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Dns,
            contentDescription = null,
            tint = if (option.selected) AliflixPurple else Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = option.provider.displayName,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = if (option.selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (option.detail.isNotBlank()) {
            Text(
                text = option.detail,
                color = AliflixPurple,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (option.selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Playing now",
                tint = AliflixPurple,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StreamingServerRows(
    servers: List<MoviepireServerOption>,
    onSelect: (MoviepireServerOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        servers.forEach { server ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (server.selected) AliflixPurple.copy(alpha = 0.20f) else RowBackground)
                    .clickable { onSelect(server) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = server.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (server.selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (server.selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = AliflixPurple,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerBusyRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RowBackground)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = AliflixPurple,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.70f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ServerErrorRow(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RowBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            tint = AliflixPurple,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Couldn't list these servers.",
            color = Color.White.copy(alpha = 0.70f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Retry",
            color = AliflixPurple,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onRetry).padding(start = 8.dp),
        )
    }
}
