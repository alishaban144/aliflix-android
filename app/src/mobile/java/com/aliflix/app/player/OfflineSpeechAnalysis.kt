package com.aliflix.app.player

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aliflix.app.downloads.OfflineDownloads
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/** A second, silent, audio-only decoder reads the COMPLETED download cache.
 * No upstream DataSource exists. It never touches the visible player's clock,
 * surface, audio focus, selected tracks or position. No PCM is persisted.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object OfflineSpeechAnalysis {
    data class Evidence(val windows: List<SpeechWindow>, val reference: List<SubtitleCue>?, val complete: Boolean = false)
    suspend fun analyse(context: Context, request: NativePlaybackRequest, audio: String): Evidence? = withContext(Dispatchers.Main.immediate) {
        val downloads = OfflineDownloads.get(context)
        val saved = downloads.entries.value.firstOrNull { it.id == request.offlineDownloadId &&
            it.download.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED } ?: return@withContext null
        val capture = PlaybackSpeechBuffer()
        capture.enableNeural(context)
        val reference = EmbeddedSyncReference()
        val factory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                ScanAudioSink(checkNotNull(super.buildAudioSink(context, false, false)), capture)
        }
        val scanner = ExoPlayer.Builder(context).setRenderersFactory(factory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(downloads.offlineFactory(), ReferenceExtractorsFactory(reference)))
            .setAudioAttributes(AudioAttributes.DEFAULT, false).setHandleAudioBecomingNoisy(false).build()
        try {
            scanner.trackSelectionParameters = scanner.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            withContext(Dispatchers.IO) { capture.awaitNeuralReady() }
            scanner.setMediaItem(saved.download.request.toMediaItem())
            scanner.prepare()
            withTimeout(15_000) {
                while (scanner.currentTracks.groups.none { it.type == C.TRACK_TYPE_AUDIO }) {
                    scanner.playerError?.let { throw it }; delay(50)
                }
            }
            val selected = scanner.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
                (0 until group.length).map { group to it }
            }.firstOrNull { (group, i) ->
                val f = group.getTrackFormat(i)
                audioSyncFingerprint(f) == audio
            } ?: run {
                android.util.Log.d("AliflixAudioSync", "offline_audio_identity_mismatch:requested=${audio.hashCode()}," +
                    "available=${scanner.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
                        (0 until group.length).map { audioSyncFingerprint(group.getTrackFormat(it)).hashCode() }
                    }}")
                return@withContext null // Never analyse another language/rendition silently.
            }
            scanner.trackSelectionParameters = scanner.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(selected.first.mediaTrackGroup, selected.second)).build()
            scanner.play()
            withTimeout(120_000) {
                while (scanner.playbackState != Player.STATE_ENDED) {
                    scanner.playerError?.let { throw it }
                    if (capture.unavailable) return@withTimeout
                    delay(50)
                }
            }
            withContext(Dispatchers.IO) { capture.awaitNeuralIdle() }
            android.util.Log.d("AliflixAudioSync", "offline_scan:${capture.diagnostics()}")
            val windows = capture.windows()
            android.util.Log.d("AliflixAudioSync", "offline_known_blocks:${windows.map { it.start }},complete=${scanner.playbackState == Player.STATE_ENDED}")
            Evidence(windows, reference.preferred()?.cues, scanner.playbackState == Player.STATE_ENDED)
        } catch (_: TimeoutCancellationException) {
            Evidence(capture.windows(), null).takeIf { it.windows.isNotEmpty() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.d("AliflixAudioSync", "offline_scan_failed:${error.javaClass.simpleName}")
            // Partial cached evidence is still useful; gaps remain unknown.
            Evidence(capture.windows(), null).takeIf { it.windows.isNotEmpty() }
        } finally { scanner.release(); capture.close() }
    }

    internal class ScanAudioSink(delegate: AudioSink, private val capture: PlaybackSpeechBuffer) : ForwardingAudioSink(delegate) {
        private var format = Format.Builder().build()
        private var offset = 0L
        private var clock = AudioSink.CURRENT_POSITION_NOT_SET
        private var ended = false
        override fun supportsFormat(format: Format) = format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)
        override fun getFormatSupport(format: Format) = if (format.sampleMimeType == MimeTypes.AUDIO_RAW)
            super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun configure(config: AudioSink.AudioSinkConfig) { format = config.format; capture.discontinuity(); ended = false }
        override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
            if (offset != outputStreamOffsetUs) capture.discontinuity()
            offset = outputStreamOffsetUs
        }
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            capture.awaitNeuralCapacity()
            capture.pcm(buffer, format, presentationTimeUs, offset)
            // MediaCodecAudioRenderer's clock is in renderer time; only the
            // fingerprint timestamp adapter removes outputStreamOffsetUs.
            clock = presentationTimeUs
            buffer.position(buffer.limit())
            return true
        }
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = clock
        override fun hasPendingData() = clock != AudioSink.CURRENT_POSITION_NOT_SET && !ended
        override fun isEnded() = ended
        override fun playToEndOfStream() { ended = true }
        override fun play() = Unit
        override fun pause() = Unit
        override fun handleDiscontinuity() { capture.discontinuity() }
        override fun flush() { capture.discontinuity(); clock = AudioSink.CURRENT_POSITION_NOT_SET; ended = false }
        override fun reset() { flush() }
    }
}
