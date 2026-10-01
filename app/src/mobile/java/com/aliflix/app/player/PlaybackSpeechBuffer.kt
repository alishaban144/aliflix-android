package com.aliflix.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

internal interface PlaybackSpeechDetector : AutoCloseable {
    fun speech(frame: ShortArray): Boolean
}

private class WebRtcPlaybackSpeechDetector : PlaybackSpeechDetector {
    private val vad = VadWebRTC(SampleRate.SAMPLE_RATE_8K, FrameSize.FRAME_SIZE_160, Mode.VERY_AGGRESSIVE)
    override fun speech(frame: ShortArray) = vad.isSpeech(frame)
    override fun close() = vad.close()
}

/** Six minutes of 20 ms decisions (~162 KiB) and one 320-byte PCM frame.
 * Box-filter resampling preserves 8 kHz timing at 44.1/48/96 kHz across codec buffers.
 * WebRTC's six-band GMM replaces the former loudness/zero-crossing heuristic.
 */
internal class PlaybackSpeechBuffer(
    private val detectorFactory: () -> PlaybackSpeechDetector = { WebRtcPlaybackSpeechDetector() },
) : AutoCloseable {
    private val times = DoubleArray(18000)
    private val bits = ByteArray(18000)
    private val frame = ShortArray(160)
    private var detector: PlaybackSpeechDetector? = null
    private var head = 0
    private var count = 0
    private var frameCount = 0
    private var frameStart = 0.0
    private var phase = 0
    private var sum = 0.0
    private var averaged = 0
    private var lastTime = Double.NEGATIVE_INFINITY
    @Volatile var generation = 0L
        private set
    @Volatile var unavailable = false
        private set

    @Synchronized fun reset() {
        head = 0; count = 0; lastTime = Double.NEGATIVE_INFINITY
        clearPartialFrame(); closeDetector(); unavailable = false; generation++
    }
    private fun clearPartialFrame() { frameCount = 0; phase = 0; sum = 0.0; averaged = 0 }
    private fun closeDetector() { runCatching { detector?.close() }; detector = null }
    @Synchronized override fun close() { reset(); unavailable = true }

    @Synchronized fun pcm(buffer: ByteBuffer, format: Format, pts: Long, offset: Long) {
        if (unavailable || pts == C.TIME_UNSET || format.sampleRate !in 8000..192000 || format.channelCount !in 1..8) return
        val width = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerFrame = format.channelCount * width
        var sourceFrame = 0
        try {
            while (input.remaining() >= bytesPerFrame) {
                val time = subtitleMediaSeconds(pts, offset, sourceFrame++, format.sampleRate)
                if (time <= lastTime) { input.position(input.position() + bytesPerFrame); continue }
                if (lastTime.isFinite() && abs(time - lastTime - 1.0 / format.sampleRate) > .005) {
                    // A seek/discontinuity is a boundary, never fabricated silent audio.
                    clearPartialFrame(); closeDetector()
                }
                lastTime = time
                var mono = 0.0
                repeat(format.channelCount) { channel ->
                    val value = when (format.pcmEncoding) {
                        C.ENCODING_PCM_16BIT -> input.short / 32768.0
                        C.ENCODING_PCM_24BIT -> ((input.get().toInt() and 255) or
                            ((input.get().toInt() and 255) shl 8) or (input.get().toInt() shl 16)) / 8388608.0
                        C.ENCODING_PCM_32BIT -> input.int / 2147483648.0
                        else -> input.float.toDouble().takeIf(Double::isFinite) ?: 0.0
                    }
                    // Film surround mixes put dialogue in the centre; averaging LFE and
                    // surrounds can bury it. Stereo/mono retain their usual downmix.
                    if (format.channelCount <= 2 || channel == 2) mono += value
                }
                val x = mono / if (format.channelCount <= 2) format.channelCount else 1
                sum += x; averaged++; phase += 8000
                if (phase >= format.sampleRate) {
                    phase -= format.sampleRate
                    if (frameCount == 0) frameStart = time - (averaged - 1.0) / format.sampleRate
                    frame[frameCount++] = (sum / averaged * 32767).toInt().coerceIn(-32768, 32767).toShort()
                    sum = 0.0; averaged = 0
                    if (frameCount == frame.size) {
                        val vad = detector ?: detectorFactory().also { detector = it }
                        times[head] = frameStart
                        bits[head] = if (vad.speech(frame)) 1 else 0
                        head = (head + 1) % times.size; count = min(count + 1, times.size)
                        frameCount = 0
                    }
                }
            }
        } catch (_: Exception) { failCapture() }
        catch (_: LinkageError) { failCapture() } // Capture failure must never stop playback.
    }
    private fun failCapture() { unavailable = true; closeDetector(); clearPartialFrame() }
    @Synchronized fun noDecodedPcm() { failCapture() }

    private data class Snapshot(val times: DoubleArray, val bits: ByteArray)
    @Synchronized private fun snapshot(maxMediaSeconds: Double): Snapshot {
        val t = DoubleArray(count); val b = ByteArray(count)
        var copied = 0
        for (i in 0 until count) {
            val index = (head - count + i + times.size) % times.size
            if (times[index] + .02 > maxMediaSeconds) continue
            t[copied] = times[index]; b[copied++] = bits[index]
        }
        return if (copied == count) Snapshot(t, b) else Snapshot(t.copyOf(copied), b.copyOf(copied))
    }

    /** Analyse outside the render-thread lock. Two independent 10-second scenes can
     * start matching in ~20 seconds; longer history and new scenes strengthen it.
     */
    fun windows(maxMediaSeconds: Double = Double.POSITIVE_INFINITY): List<SpeechWindow> {
        val data = snapshot(maxMediaSeconds)
        val rich = mutableListOf<SpeechWindow>()
        val length = 10 * SPEECH_HZ
        var i = 0
        while (i + length <= data.times.size) {
            val start = data.times[i]
            var voiced = 0; var transitions = 0; var contiguous = true
            for (k in 0 until length) {
                if (abs(data.times[i + k] - start - k.toDouble() / SPEECH_HZ) > .003) { contiguous = false; break }
                voiced += data.bits[i + k]
                if (k > 0 && data.bits[i + k] != data.bits[i + k - 1]) transitions++
            }
            if (contiguous && voiced.toDouble() / length in .12.. .90 && transitions >= 4) {
                rich += SpeechWindow(start, DoubleArray(length) { data.bits[i + it].toDouble() })
                i += length
            } else i += SPEECH_HZ / 2
        }
        // Keep distant anchors for drift, but continually incorporate new evidence.
        if (rich.size <= 6) return rich
        return (0..5).map { rich[it * (rich.size - 1) / 5] }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SpeechCaptureAudioSink(
    sink: AudioSink,
    private val capture: PlaybackSpeechBuffer,
    private val canDecode: (Format) -> Boolean = { format ->
        runCatching {
            androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(format.sampleMimeType.orEmpty(), false, false).isNotEmpty()
        }.getOrDefault(false)
    },
) : ForwardingAudioSink(sink) {
    private var format = Format.Builder().build()
    private var offset = 0L
    private var capturedBuffer: ByteBuffer? = null
    private var capturedPts = C.TIME_UNSET
    // Encoded passthrough/offload has no PCM to inspect. Select the platform decoder
    // when preparing local playback, never switch or restart the pipeline on a tap.
    private fun needsDecoder(format: Format) = format.sampleMimeType != MimeTypes.AUDIO_RAW && canDecode(format)
    override fun supportsFormat(format: Format) = !needsDecoder(format) && super.supportsFormat(format)
    override fun getFormatSupport(format: Format) = if (!needsDecoder(format))
        super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = if (format.sampleMimeType == MimeTypes.AUDIO_RAW || needsDecoder(format))
        AudioOffloadSupport.DEFAULT_UNSUPPORTED else super.getFormatOffloadSupport(format)
    override fun configure(config: AudioSink.AudioSinkConfig) {
        super.configure(config)
        if (format.sampleRate != config.format.sampleRate || format.channelCount != config.format.channelCount || format.pcmEncoding != config.format.pcmEncoding)
            capture.reset()
        format = config.format
        // Some HDMI routes can play formats the phone cannot decode. Preserve
        // their playback; explain missing PCM instead of disabling working audio.
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) capture.noDecodedPcm()
    }
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { offset = outputStreamOffsetUs; super.setOutputStreamOffsetUs(outputStreamOffsetUs) }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (capturedBuffer !== buffer || capturedPts != presentationTimeUs) {
            capturedBuffer = buffer; capturedPts = presentationTimeUs
            capture.pcm(buffer, format, presentationTimeUs, offset)
        }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (consumed) capturedBuffer = null
        return consumed
    }
    override fun flush() { capturedBuffer = null; capturedPts = C.TIME_UNSET; capture.reset(); super.flush() }
    override fun reset() { capturedBuffer = null; capturedPts = C.TIME_UNSET; capture.reset(); super.reset() }
    override fun release() { capture.close(); super.release() }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SpeechCaptureRenderers(context: Context, private val capture: PlaybackSpeechBuffer) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        SpeechCaptureAudioSink(checkNotNull(super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)), capture)
}
