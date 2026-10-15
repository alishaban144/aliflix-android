package com.aliflix.app.player

import android.content.Context
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aliflix.app.downloads.OfflineDownloads
import kotlinx.coroutines.*

internal data class BufferedDialogueEvidence(val pcm: PlayedDialoguePcm, val speech: List<SpeechWindow>)

/** A bounded silent decoder reads the selected soundtrack through the playback
 * byte cache, fetching missing nearby segments only if needed. It never seeks
 * the visible player, takes audio focus, renders video, or changes audio tracks.
 * Future words are only accepted alongside this decoder's measured media PTS.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object BufferedDialogueAnalysis {
    suspend fun collect(context: Context, request: NativePlaybackRequest, audio: String,
                        position: Double, progress: (BufferedDialogueEvidence) -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            if (audio.isBlank() || !position.isFinite()) return@withContext
            val start = (position - 8.0).coerceAtLeast(0.0)
            val end = start + 60.0
            val capture = PlaybackSpeechBuffer()
            val selectedAudioReady = java.util.concurrent.atomic.AtomicBoolean(false)
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                            enableAudioOutputPlaybackParams: Boolean): AudioSink =
                    OfflineSpeechAnalysis.ScanAudioSink(checkNotNull(super.buildAudioSink(context, false, false)), capture, end, selectedAudioReady::get)
            }
            val media = if (request.offlineDownloadId.isNotBlank())
                DefaultDataSource.Factory(context, OfflineDownloads.get(context).offlineFactory())
            else StartupStreamCache.mediaFactory(context, request)
            val scanner = ExoPlayer.Builder(context).setRenderersFactory(renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(media))
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 4_000, 250, 500).build())
                .setAudioAttributes(AudioAttributes.DEFAULT, false).setHandleAudioBecomingNoisy(false).build()
            try {
                withTimeout(11_000) {
                    scanner.trackSelectionParameters = scanner.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    val saved = if (request.offlineDownloadId.isNotBlank()) OfflineDownloads.get(context)
                        .manager.downloadIndex.getDownload(request.offlineDownloadId)?.request?.toMediaItem() else null
                    scanner.setMediaItem(saved ?: MediaItem.Builder().setUri(request.url).setMimeType(request.mimeType).build(), (start * 1_000).toLong())
                    scanner.prepare()
                    while (scanner.currentTracks.groups.none { it.type == C.TRACK_TYPE_AUDIO }) {
                        scanner.playerError?.let { throw it }; delay(25)
                    }
                    val selected = scanner.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        .flatMap { group -> (0 until group.length).map { group to it } }
                        .firstOrNull { (group, index) -> audioSyncFingerprint(group.getTrackFormat(index)) == audio }
                        ?: return@withTimeout // No substitute language or rendition.
                    scanner.trackSelectionParameters = scanner.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(selected.first.mediaTrackGroup, selected.second)).build()
                    while (scanner.currentTracks.groups.none { group -> group.type == C.TRACK_TYPE_AUDIO &&
                        (0 until group.length).any { group.isTrackSelected(it) && audioSyncFingerprint(group.getTrackFormat(it)) == audio } }) {
                        scanner.playerError?.let { throw it }; delay(25)
                    }
                    capture.reset()
                    selectedAudioReady.set(true)
                    scanner.play()
                    var previousFrames = 0
                    while (isActive) {
                        scanner.playerError?.let { throw it }
                        val evidence = withContext(Dispatchers.Default) {
                            capture.decodedDialoguePcm(start, end)?.let { BufferedDialogueEvidence(it, capture.quickWindows(end)) }
                        }
                        if (evidence != null && evidence.pcm.frames.size >= 1_000 && evidence.pcm.frames.size >= previousFrames + 500) {
                            previousFrames = evidence.pcm.frames.size
                            android.util.Log.i("AliflixAudioSync", "buffered_dialogue:frames=$previousFrames,start=${evidence.pcm.start}")
                            progress(evidence)
                        }
                        if (evidence != null && evidence.pcm.start + evidence.pcm.frames.size * .02 >= end - .1 ||
                            scanner.playbackState == Player.STATE_ENDED) break
                        delay(50)
                    }
                }
            } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.i("AliflixAudioSync", "buffered_dialogue_unavailable:${error.javaClass.simpleName}") }
            finally { scanner.release(); capture.close() }
        }
}
