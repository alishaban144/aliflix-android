package com.aliflix.app.player

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.frame.FrameExtractor
import com.aliflix.app.talk.TalkSceneFrame
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

internal fun cachedTalkVideoFrame(cache:Map<Long,TalkSceneFrame>,millis:Long,ceiling:Double):TalkSceneFrame? =
    cache[millis]?.takeIf{it.seconds<=ceiling}?:cache.entries.filter{abs(it.key-millis)<=100&&it.value.seconds<=ceiling}
        .minByOrNull{abs(it.key-millis)}?.value

/** Independently decodes requested earlier moments. Never seeks or resumes the viewer's
 * player. Source credentials stay inside the native data source, never in model context. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlayerTalkVideo(private val context: Context) {
    private val mutex = Mutex()
    private var source = ""
    private var extractingAnchor=Double.NaN
    fun extractingAt(requestId:String,anchor:Double)=source==requestId&&kotlin.math.abs(extractingAnchor-anchor)<.1
    private val cached = linkedMapOf<Long, TalkSceneFrame>()

    suspend fun frames(requestId: String, request: NativePlaybackRequest, seconds: List<Double>,
        ceiling: Double,onFrame:(TalkSceneFrame)->Unit={},stillCurrent: () -> Boolean): List<TalkSceneFrame> = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (source != requestId) { source = requestId; cached.clear() }
            // Preserve caller priority: a timeout must not discard the nearby reverse shot.
            val positions = seconds.filter { it.isFinite() && it in 0.0..ceiling }.distinct().take(8)
            if (!stillCurrent()) return@withLock emptyList()
            val retained=positions.mapNotNull{cachedTalkVideoFrame(cached,(it*1000).toLong(),ceiling)}
            if(retained.size==positions.size)return@withLock retained.distinctBy{it.seconds}
            val output = mutableListOf<TalkSceneFrame>()
            extractingAnchor=ceiling
            try { withTimeoutOrNull(12_000) {
                val http = DefaultHttpDataSource.Factory().setUserAgent(request.userAgent)
                    .setConnectTimeoutMs(4000).setReadTimeoutMs(4000)
                    .setDefaultRequestProperties(buildMap {
                        put("Referer", request.referer)
                        val origin = java.net.URI(request.referer)
                        if (origin.scheme != null && origin.rawAuthority != null) put("Origin", "${origin.scheme}://${origin.rawAuthority}")
                    })
                val item = MediaItem.Builder().setUri(request.url).setMimeType(request.mimeType).build()
                val scoped = ResolvingDataSource.Factory(http) { request.resolveStreamSpec(it) }
                val fragments = DataSource.Factory {
                    val upstream = scoped.createDataSource()
                    if (request.referer == CineJoyNativeCatalog.REFERER) CineJoyFragmentDataSource(upstream, request.url) else upstream
                }
                val playlists = DataSource.Factory {
                    val upstream = fragments.createDataSource()
                    if (request.referer == CineJoyNativeCatalog.REFERER) CineJoyAudioPlaylistDataSource(upstream, request.url, fragments) else upstream
                }
                val data = DataSource.Factory {
                    val upstream = if (request.offlineDownloadId.isNotBlank())
                        DefaultDataSource(context, com.aliflix.app.downloads.OfflineDownloads.get(context).offlineFactory().createDataSource())
                    else DefaultDataSource(context, playlists.createDataSource())
                    if (request.referer == CineJoyNativeCatalog.REFERER) CineJoyManifestDataSource(upstream, request) else upstream
                }
                val factory = DefaultMediaSourceFactory(data)
                FrameExtractor.Builder(context, item).setMediaSourceFactory(factory)
                    .setEffects(listOf(Presentation.createForHeight(360))).build().use { extractor ->
                    for (position in positions) {
                        if (!stillCurrent()) break
                        val millis = (position * 1000).toLong()
                        val frame = cachedTalkVideoFrame(cached,millis,ceiling) ?: try {
                            val future = extractor.getFrame(millis)
                            val extracted = suspendCancellableCoroutine<FrameExtractor.Frame> { continuation ->
                                continuation.invokeOnCancellation { future.cancel(true) }
                                future.addListener({
                                    if (continuation.isActive) try { continuation.resume(future.get()) }
                                    catch (error: Exception) { continuation.resumeWithException(error) }
                                }, MoreExecutors.directExecutor())
                            }
                            val actual = extracted.presentationTimeMs / 1000.0
                            try {
                                if (actual !in 0.0..ceiling) null else withContext(Dispatchers.Default) {
                                    ByteArrayOutputStream().use { stream ->
                                        extracted.bitmap.compress(Bitmap.CompressFormat.JPEG, 72, stream)
                                        stream.toByteArray().takeIf { it.size in 1..160_000 }?.let { TalkSceneFrame(actual, it) }
                                    }
                                }
                            } finally { extracted.bitmap.recycle() }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        if (frame != null && stillCurrent()) {
                            cached[millis] = frame; output += frame
                            onFrame(frame)
                            while (cached.size > 32) cached.remove(cached.keys.first())
                        }
                    }
                }
            } } finally { extractingAnchor=Double.NaN }
            output.takeIf { stillCurrent() }.orEmpty().distinctBy { it.seconds }.sortedBy { it.seconds }
        }
    }
}
