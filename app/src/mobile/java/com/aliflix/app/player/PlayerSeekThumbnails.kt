package com.aliflix.app.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.frame.FrameExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(UnstableApi::class)
internal class PlayerSeekThumbnails(private val context: Context, private val request: NativePlaybackRequest) {
    private val frames = LruCache<Long, Bitmap>(80)
    private val requests = Channel<Long>(Channel.CONFLATED)
    private val failures = LruCache<Long, Long>(32)
    private var revision by mutableIntStateOf(0)
    private var liveFrame: Pair<Long, Bitmap>? = null

    fun request(positionMs: Long) {
        val time = bucket(positionMs)
        if (frames.get(time) == null && (failures.get(time) ?: 0L) <= SystemClock.uptimeMillis()) {
            requests.trySend(time)
        }
    }

    fun frame(positionMs: Long): Bitmap? {
        val time = bucket(positionMs)
        val observedRevision = revision
        if (observedRevision == 0) return null
        return frames.get(time)
            ?: liveFrame?.takeIf { abs(it.first - positionMs) <= BUCKET_MS / 2 }?.second
            ?: frames.get(time - BUCKET_MS)
            ?: frames.get(time + BUCKET_MS)
    }

    suspend fun capture(surface: SurfaceView?, positionMs: Long) {
        if (surface == null || !surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) return
        val scale = minOf(256f / surface.width, 144f / surface.height)
        val bitmap = Bitmap.createBitmap((surface.width * scale).roundToInt().coerceAtLeast(1),
            (surface.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine<Boolean> { continuation ->
            try {
                PixelCopy.request(surface, bitmap, { status ->
                    if (continuation.isActive && status == PixelCopy.SUCCESS) continuation.resume(true)
                    else {
                        bitmap.recycle()
                        if (continuation.isActive) continuation.resume(false)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                bitmap.recycle()
                if (continuation.isActive) continuation.resume(false)
            }
        }
        if (copied) {
            liveFrame = positionMs to bitmap
            revision++
        }
    }

    suspend fun run() {
        val source = if (request.offlineDownloadId.isNotBlank()) {
            com.aliflix.app.downloads.OfflineDownloads.get(context).offlineFactory()
        } else StartupStreamCache.mediaFactory(context, request)
        val item = MediaItem.Builder().setUri(request.url).setMimeType(request.mimeType).build()
        var extractor: FrameExtractor? = null
        try {
            for (time in requests) {
                if (frames.get(time) != null || (failures.get(time) ?: 0L) > SystemClock.uptimeMillis()) continue
                try {
                    val decoder = extractor ?: FrameExtractor.Builder(context, item)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(source))
                        .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                        .setEffects(listOf(Presentation.createForHeight(144)))
                        .build().also { extractor = it }
                    val frame = withTimeoutOrNull(3_500) {
                        suspendCancellableCoroutine<FrameExtractor.Frame> { continuation ->
                            val future = decoder.getFrame(time)
                            continuation.invokeOnCancellation { future.cancel(true) }
                            future.addListener({
                                if (continuation.isActive) {
                                    try { continuation.resume(future.get()) }
                                    catch (error: Exception) { continuation.resumeWithException(error) }
                                }
                            }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
                        }
                    }
                    if (frame == null) {
                        decoder.close()
                        extractor = null
                        failures.put(time, SystemClock.uptimeMillis() + 5_000)
                    } else {
                        frames.put(time, frame.bitmap)
                        revision++
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.d("AliflixPreview", "Frame unavailable: ${error.javaClass.simpleName}")
                    extractor?.close()
                    extractor = null
                    failures.put(time, SystemClock.uptimeMillis() + 5_000)
                }
            }
        } finally {
            extractor?.close()
        }
    }

    private fun bucket(positionMs: Long) = positionMs.coerceAtLeast(0) / BUCKET_MS * BUCKET_MS

    private companion object { const val BUCKET_MS = 2_000L }
}

@Composable
internal fun PlayerSeekThumbnail(
    frames: PlayerSeekThumbnails?,
    positionMs: Long,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
) {
    LaunchedEffect(frames, positionMs / 2_000) {
        frames?.request(positionMs)
        while (frames != null) {
            delay(5_000)
            frames.request(positionMs)
        }
    }
    val bitmap = frames?.frame(positionMs)
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.aspectRatio(16f / 9f).clip(shape).background(Color(0xFF171923))
        .border(1.dp, Color.White.copy(alpha = .18f), shape), contentAlignment = Alignment.BottomCenter) {
        if (bitmap != null) Image(remember(bitmap) { bitmap.asImageBitmap() }, null,
            Modifier.fillMaxSize().testTag("seek-thumbnail-frame"), contentScale = ContentScale.Fit)
        if (showTime) Text(formatPlaybackTime(positionMs / 1000.0),
            Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = .78f)).padding(horizontal = 7.dp, vertical = 3.dp),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
