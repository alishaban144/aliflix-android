package com.aliflix.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.Episode
import com.aliflix.app.ui.theme.AliflixBlack
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixMuted
import com.aliflix.app.ui.theme.AliflixRed
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceRaised

@Composable
fun WebPlayerScreen(
    selection: PlaybackSelection,
    visible: Boolean,
    controller: WebPlayerController,
    onClose: () -> Unit,
    onSelectEpisode: (Episode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val loading by controller.loading.collectAsState()
    val error by controller.error.collectAsState()
    val webViewGeneration by controller.webViewGeneration.collectAsState()
    val moviepireServers by controller.moviepireServers.collectAsState()
    val switchingMoviepireServer by controller.switchingMoviepireServer.collectAsState()
    val playing by controller.playing.collectAsState()
    val nativePhoneMoviepire = selection.source.provider.usesMoviepire && !BuildConfig.IS_TV
    val playerAccent = if (nativePhoneMoviepire) AliflixAccentPrimary else AliflixRed
    val resumableProgress = remember(selection.key) {
        controller.savedProgressFor(selection)?.takeIf { it.resumeEligible }
    }
    var resumeDecisionMade by remember(selection.key) {
        mutableStateOf(resumableProgress == null)
    }
    var serverMenuExpanded by remember(selection.key) { mutableStateOf(false) }
    var episodeMenuExpanded by remember(selection.key) { mutableStateOf(false) }
    var controlsVisible by remember(selection.key) { mutableStateOf(true) }

    LaunchedEffect(selection.key) {
        controller.prepareSelection(selection)
    }

    LaunchedEffect(visible, resumeDecisionMade) {
        controller.setVisible(visible && resumeDecisionMade)
    }

    LaunchedEffect(
        nativePhoneMoviepire,
        playing,
        loading,
        error,
        controlsVisible,
        serverMenuExpanded,
        episodeMenuExpanded,
        switchingMoviepireServer,
    ) {
        if (
            nativePhoneMoviepire &&
            controlsVisible &&
            !loading &&
            error == null &&
            !serverMenuExpanded &&
            !episodeMenuExpanded &&
            !switchingMoviepireServer
        ) {
            delay(if (playing) 3_200L else 5_200L)
            controlsVisible = false
        }
    }

    BackHandler(enabled = visible) {
        if (!controller.handleBack()) onClose()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AliflixBlack),
    ) {
        if (resumeDecisionMade) {
            key(selection.key, webViewGeneration) {
                AndroidView(
                    factory = { controller.viewFor(selection) },
                    update = { controller.setVisible(visible) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "Resume from ${formatPlaybackTime(resumableProgress!!.positionSeconds)}",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            controller.requestResume(resumableProgress)
                            resumeDecisionMade = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = playerAccent),
                    ) {
                        Text("Resume")
                    }
                    Button(
                        onClick = {
                            controller.startOver(selection)
                            resumeDecisionMade = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AliflixSurfaceRaised,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Start over")
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = loading && resumeDecisionMade,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AliflixBlack),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(playerAccent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "A",
                            color = Color.White,
                            fontSize = 29.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    Text(
                        text = "Preparing playback",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = selection.episodeTitle ?: selection.media.title,
                        color = AliflixMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 250.dp),
                    )
                    CircularProgressIndicator(
                        color = playerAccent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }

        if (error != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AliflixSurfaceRaised)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Player unavailable",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(text = error.orEmpty(), color = Color.LightGray)
                FilledIconButton(
                    onClick = controller::reload,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = playerAccent),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Retry player")
                }
            }
        }

        if (nativePhoneMoviepire) {
            if (!controlsVisible && resumeDecisionMade && error == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { controlsVisible = true },
                        ),
                )
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                NativeMoviepireTopBar(
                    selection = selection,
                    servers = moviepireServers,
                    episodes = selection.availableEpisodes,
                    switchingServer = switchingMoviepireServer,
                    currentSeasonNumber = selection.seasonNumber,
                    currentEpisodeNumber = selection.episodeNumber,
                    serverMenuExpanded = serverMenuExpanded,
                    episodeMenuExpanded = episodeMenuExpanded,
                    onServerMenuExpandedChange = { serverMenuExpanded = it },
                    onEpisodeMenuExpandedChange = { episodeMenuExpanded = it },
                    onSelectServer = { server ->
                        controlsVisible = true
                        controller.selectMoviepireServer(server)
                    },
                    onSelectEpisode = onSelectEpisode,
                    onClose = onClose,
                    onFullscreen = {
                        controlsVisible = false
                        controller.requestMoviepireFullscreen()
                    },
                    onCast = controller::openCastPicker,
                )
            }
        } else {
            ExistingPlayerTopControls(selection, controller, onClose)
        }
    }
}

@Composable
private fun NativeMoviepireTopBar(
    selection: PlaybackSelection,
    servers: List<MoviepireServerOption>,
    episodes: List<Episode>,
    switchingServer: Boolean,
    currentSeasonNumber: Int?,
    currentEpisodeNumber: Int?,
    serverMenuExpanded: Boolean,
    episodeMenuExpanded: Boolean,
    onServerMenuExpandedChange: (Boolean) -> Unit,
    onEpisodeMenuExpandedChange: (Boolean) -> Unit,
    onSelectServer: (MoviepireServerOption) -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
    onCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedServer = servers.firstOrNull(MoviepireServerOption::selected)
    val episodeContext = if (currentSeasonNumber != null && currentEpisodeNumber != null) {
        "S${currentSeasonNumber.toString().padStart(2, '0')}  •  " +
            "E${currentEpisodeNumber.toString().padStart(2, '0')}"
    } else {
        null
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        AliflixBlack.copy(alpha = 0.98f),
                        AliflixBlack.copy(alpha = 0.86f),
                        AliflixBlack.copy(alpha = 0.42f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(top = 38.dp, start = 14.dp, end = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayerIconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Aliflix")
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = selection.media.title,
                    color = AliflixContentPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondaryLine = listOfNotNull(
                    episodeContext,
                    selection.episodeTitle?.takeIf(String::isNotBlank),
                ).joinToString("  •  ")
                if (secondaryLine.isNotBlank()) {
                    Text(
                        text = secondaryLine,
                        color = AliflixContentSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PlayerIconButton(onClick = onFullscreen) {
                Icon(Icons.Rounded.Fullscreen, contentDescription = "Play fullscreen")
            }
            PlayerIconButton(onClick = onCast) {
                Icon(Icons.Rounded.Cast, contentDescription = "Cast screen")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box {
                Button(
                    onClick = { onServerMenuExpandedChange(true) },
                    enabled = servers.isNotEmpty() && !switchingServer,
                    modifier = Modifier.heightIn(min = 44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AliflixSurfaceElevated.copy(alpha = 0.94f),
                        contentColor = AliflixContentPrimary,
                        disabledContainerColor = AliflixSurfaceElevated.copy(alpha = 0.78f),
                        disabledContentColor = AliflixContentSecondary,
                    ),
                    border = BorderStroke(1.dp, AliflixBorderSubtle),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (switchingServer) {
                        CircularProgressIndicator(
                            color = AliflixAccentSecondary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        text = if (switchingServer) {
                            "Switching server"
                        } else {
                            selectedServer?.label ?: "Choose server"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    if (!switchingServer) {
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = serverMenuExpanded,
                    onDismissRequest = { onServerMenuExpandedChange(false) },
                ) {
                    servers.forEach { server ->
                        DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = server.label,
                                        color = if (server.selected) {
                                            AliflixAccentSecondary
                                        } else {
                                            AliflixContentPrimary
                                        },
                                        fontWeight = if (server.selected) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Medium
                                        },
                                    )
                                    if (server.selected) {
                                        Text(
                                            text = "Currently playing",
                                            color = AliflixContentSecondary,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                            },
                            enabled = !server.selected && !switchingServer,
                            onClick = {
                                onServerMenuExpandedChange(false)
                                onSelectServer(server)
                            },
                        )
                    }
                }
            }
            if (episodes.isNotEmpty()) {
                Spacer(Modifier.size(10.dp))
                Box {
                    Button(
                        onClick = { onEpisodeMenuExpandedChange(true) },
                        modifier = Modifier.heightIn(min = 44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AliflixAccentPrimary.copy(alpha = 0.92f),
                            contentColor = Color.White,
                        ),
                        border = BorderStroke(1.dp, AliflixAccentSecondary.copy(alpha = 0.34f)),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = episodeContext ?: "Episodes",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = episodeMenuExpanded,
                        onDismissRequest = { onEpisodeMenuExpandedChange(false) },
                    ) {
                        episodes.forEach { episode ->
                            val current = episode.seasonNumber == currentSeasonNumber &&
                                episode.number == currentEpisodeNumber
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "S${episode.seasonNumber.toString().padStart(2, '0')}  •  " +
                                                "E${episode.number.toString().padStart(2, '0')}",
                                            color = if (current) {
                                                AliflixAccentSecondary
                                            } else {
                                                AliflixContentSecondary
                                            },
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = episode.title,
                                            color = AliflixContentPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (current) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Medium
                                            },
                                        )
                                    }
                                },
                                enabled = !current,
                                onClick = {
                                    onEpisodeMenuExpandedChange(false)
                                    onSelectEpisode(episode)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ExistingPlayerTopControls(
    selection: PlaybackSelection,
    controller: WebPlayerController,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = 44.dp, start = 16.dp),
    ) {
        PlayerIconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Aliflix")
        }
    }

    if (!BuildConfig.IS_TV) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 44.dp, end = 16.dp),
        ) {
            PlayerIconButton(onClick = controller::openCastPicker) {
                Icon(Icons.Rounded.Cast, contentDescription = "Cast screen")
            }
        }
    }

    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 44.dp),
    ) {
        if (selection.source.provider == PlaybackProviderId.RAMOFLIX) {
            Button(
                onClick = controller::showProviderOptions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                ),
                shape = CircleShape,
            ) {
                Text(
                    text = "Ramoflix · Servers",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Text(
                text = selection.source.provider.displayName,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.68f),
            contentColor = Color.White,
        ),
        content = content,
    )
}

private fun formatPlaybackTime(seconds: Double): String {
    val totalSeconds = seconds.toLong().coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val remainingSeconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%d:%02d".format(minutes, remainingSeconds)
    }
}
