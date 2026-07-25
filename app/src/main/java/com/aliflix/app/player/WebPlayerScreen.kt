package com.aliflix.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.ui.theme.AliflixBlack
import com.aliflix.app.ui.theme.AliflixMuted
import com.aliflix.app.ui.theme.AliflixRed
import com.aliflix.app.ui.theme.AliflixSurfaceRaised

@Composable
fun WebPlayerScreen(
    selection: PlaybackSelection,
    visible: Boolean,
    controller: WebPlayerController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading by controller.loading.collectAsState()
    val error by controller.error.collectAsState()
    val webViewGeneration by controller.webViewGeneration.collectAsState()

    LaunchedEffect(visible) {
        controller.setVisible(visible)
    }

    BackHandler(enabled = visible) {
        if (!controller.handleBack()) onClose()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AliflixBlack),
    ) {
        key(selection.key, webViewGeneration) {
            AndroidView(
                factory = { controller.viewFor(selection) },
                update = { controller.setVisible(visible) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = loading,
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
                            .background(AliflixRed),
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
                        color = AliflixRed,
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
                Text(
                    text = error.orEmpty(),
                    color = Color.LightGray,
                )
                FilledIconButton(
                    onClick = controller::reload,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = AliflixRed,
                    ),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Retry player")
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 44.dp, start = 16.dp),
        ) {
            FilledIconButton(
                onClick = onClose,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.68f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Aliflix")
            }
        }

        if (!BuildConfig.IS_TV) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 44.dp, end = 16.dp),
            ) {
                FilledIconButton(
                    onClick = controller::openCastPicker,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.68f),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Rounded.Cast, contentDescription = "Cast screen")
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp),
        ) {
            androidx.compose.material3.Button(
                onClick = controller::showRamoflixServers,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
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
        }
    }
}
