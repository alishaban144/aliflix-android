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
    private var debugSpeechObserver: ((List<SpeechWindow>) -> Unit)? = null
    internal fun debugObserveSpeech(observer: ((List<SpeechWindow>) -> Unit)?) {
        if (com.aliflix.app.BuildConfig.DEBUG) debugSpeechObserver = observer
    }
    suspend fun collect(context: Context, request: NativePlaybackRequest, audio: String,
                        position: Double, progress: (BufferedDialogueEvidence) -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            if (audio.isBlank() || !position.isFinite()) return@withContext
            // Retain current dialogue and decode nearby evidence without
            // moving or accelerating the visible player's clock.
            val start = (position - 8.0).coerceAtLeast(0.0)
            // A translated PAL edition needs widely separated scenes. Stop as
            // soon as the owner verifies a clock; the scan still has an eleven
            // second wall-clock limit and retains at most 120 seconds of PCM.
            val end = start + 240.0
            val capture = PlaybackSpeechBuffer(retainDecodedNeuralHistory = true)
            capture.enableNeural(context)
            val clips = DecodedDialogueClips()
            capture.dialogueFrameObserver = { time, pcm, _, boundary, _ -> clips.append(time, pcm, boundary) }
            val selectedAudioReady = java.util.concurrent.atomic.AtomicBoolean(false)
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                            enableAudioOutputPlaybackParams: Boolean): AudioSink =
                    OfflineSpeechAnalysis.ScanAudioSink(checkNotNull(super.buildAudioSink(context, false, false)), capture, end, selectedAudioReady::get,
                        validatePcmContinuity=true)
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
                    withContext(Dispatchers.IO) { capture.awaitNeuralReady() }
                    scanner.trackSelectionParameters = scanner.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    val saved = if (request.offlineDownloadId.isNotBlank()) OfflineDownloads.get(context)
                        .manager.downloadIndex.getDownload(request.offlineDownloadId)?.request?.toMediaItem() else null
                    scanner.setMediaItem(saved ?: MediaItem.Builder().setUri(request.url).setMimeType(request.mimeType).build(), (start * 1_000).toLong())
                    scanner.prepare()
                    while (scanner.currentTracks.groups.none { it.type == C.TRACK_TYPE_AUDIO }) {
                        scanner.playerError?.let { throw it }; delay(25)
                    }
                    val available = scanner.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        .flatMap { group -> (0 until group.length).map { group to it } }
                    // Match the selected soundtrack even when there is only
                    // one rendition. Unknown identity cannot certify captions.
                    android.util.Log.i("AliflixAudioSync", "buffered_tracks:count=${available.size},exact=${available.count { (g, i) -> audioSyncFingerprint(g.getTrackFormat(i)) == audio }}")
                    val selected = available.firstOrNull { (group, index) -> audioSyncFingerprint(group.getTrackFormat(index)) == audio }
                        ?: return@withTimeout
                    scanner.trackSelectionParameters = scanner.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(selected.first.mediaTrackGroup, selected.second)).build()
                    while (scanner.currentTracks.groups.none { group -> group.type == C.TRACK_TYPE_AUDIO &&
                        group.mediaTrackGroup == selected.first.mediaTrackGroup && group.isTrackSelected(selected.second) }) {
                        scanner.playerError?.let { throw it }; delay(25)
                    }
                    android.util.Log.i("AliflixAudioSync", "buffered_audio_selected")
                    capture.reset()
                    selectedAudioReady.set(true)
                    scanner.play()
                    val emitted = mutableMapOf<Double, Double>()
                    var speechVersion = -1 to -1.0
                    while (isActive) {
                        scanner.playerError?.let { throw it }
                        val snapshots = clips.snapshot()
                        val speech = withContext(Dispatchers.Default) { capture.quickWindows(end) }
                        val version = speech.sumOf { it.speech.size } to (speech.lastOrNull()?.let { it.start + it.speech.size / SPEECH_HZ.toDouble() } ?: -1.0)
                        if (version != speechVersion && snapshots.isNotEmpty()) {
                            speechVersion = version
                            progress(BufferedDialogueEvidence(snapshots.last(), speech))
                            if (com.aliflix.app.BuildConfig.DEBUG) debugSpeechObserver?.invoke(speech)
                        }
                        for (pcm in snapshots) {
                            val through = pcm.start + pcm.frames.size * .02
                            if (through - (emitted[pcm.start] ?: (pcm.start - 1.0)) >= 4.0) {
                                emitted[pcm.start] = through
                                android.util.Log.i("AliflixAudioSync", "buffered_dialogue:frames=${pcm.frames.size},start=${pcm.start}")
                                progress(BufferedDialogueEvidence(pcm, speech))
                            }
                        }
                        if (snapshots.maxOfOrNull { it.start + it.frames.size * .02 }?.let { it >= end - .1 } == true ||
                            scanner.playbackState == Player.STATE_ENDED) {
                            runInterruptible(Dispatchers.IO) { capture.awaitNeuralIdle() }
                            val completeSpeech = withContext(Dispatchers.Default) { capture.quickWindows(end) }
                            snapshots.lastOrNull()?.let { progress(BufferedDialogueEvidence(it, completeSpeech)) }
                            if (com.aliflix.app.BuildConfig.DEBUG) debugSpeechObserver?.invoke(completeSpeech)
                            break
                        }
                        delay(50)
                    }
                }
            } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.i("AliflixAudioSync", "buffered_dialogue_unavailable:${error.javaClass.simpleName}") }
            finally {
                android.util.Log.i("AliflixAudioSync", "buffered_complete:${capture.diagnostics()}")
                scanner.release(); capture.close()
            }
        }
}
