package com.aliflix.app.ui.discover

import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Pixel-faithful Ask Aliflix loading orb backed by the supplied animation.
 *
 * Put the optimized animation at:
 * app/src/main/res/raw/ask_aliflix_loader.mp4
 */
@Composable
fun AskAliflixLoadingOrb(
    @RawRes videoResId: Int,
    modifier: Modifier = Modifier,
    diameter: Dp = 148.dp,
) {
    val videoViewRef = remember { mutableStateOf<VideoView?>(null) }

    AndroidView(
        modifier = modifier.size(diameter),
        factory = { context ->
            VideoView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setVideoURI(
                    Uri.parse("android.resource://${context.packageName}/$videoResId")
                )
                setOnPreparedListener { player ->
                    player.isLooping = true
                    player.setVolume(0f, 0f)
                    player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    start()
                }
                videoViewRef.value = this
            }
        },
        update = { view ->
            // VideoView safely remembers start() requested before preparation.
            if (!view.isPlaying) view.start()
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            videoViewRef.value?.stopPlayback()
            videoViewRef.value = null
        }
    }
}
