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
    private val vad = VadWebRTC(SampleRate.SAMPLE_RATE_8K, FrameSize.FRAME_SIZE_160, Mode.AGGRESSIVE)
    override fun speech(frame: ShortArray) = vad.isSpeech(frame)
    override fun close() = vad.close()
}

/** Timestamped 20 ms decisions. Sparse 20-second blocks retain up to six hours
 * in at most 1.1 MiB, plus a short compatibility ring and one 320-byte PCM frame.
 * Box-filter resampling preserves 8 kHz timing at 44.1/48/96 kHz across codec buffers.
 * WebRTC's six-band GMM replaces the former loudness/zero-crossing heuristic.
 */
internal class PlaybackSpeechBuffer(
    private val detectorFactory: () -> PlaybackSpeechDetector = { WebRtcPlaybackSpeechDetector() },
) : AutoCloseable {
    private val times = DoubleArray(1500)
    private val bits = ByteArray(1500)
    private val frame = ShortArray(160)
    private val evidence = java.util.TreeMap<Int, ByteArray>()
    private val neuralEvidence = java.util.TreeMap<Int, ByteArray>()
    private var neural: NeuralSpeechWorker? = null
    private var boundaryCount = 0L
    private var duplicateFrames = 0L
    private var failure: String? = null
    private var captureNanos = 0L
    private var detector: PlaybackSpeechDetector? = null
    private var detectorFailed = false
    private var head = 0
    private var count = 0
    private var frameCount = 0
    private var frameStart = 0.0
    private var nextDecisionBin: Int? = null
    private var phase = 0
    private var sum = 0.0
    private var averaged = 0
    private var lastTime = Double.NEGATIVE_INFINITY
    @Volatile var generation = 0L
        private set
    @Volatile var decodedFrameCount = 0L
        private set
    @Volatile var unavailable = false
        private set

    @Synchronized fun reset() {
        evidence.clear(); neuralEvidence.clear(); boundaryCount++; duplicateFrames = 0; failure = null; captureNanos = 0
        head = 0; count = 0; lastTime = Double.NEGATIVE_INFINITY
        decodedFrameCount = 0; clearPartialFrame(); closeDetector(); detectorFailed = false; unavailable = false; generation++
    }
    private fun clearPartialFrame() { frameCount = 0; phase = 0; sum = 0.0; averaged = 0; nextDecisionBin = null }
    private fun closeDetector() { runCatching { detector?.close() }; detector = null }
    @Synchronized override fun close() { neural?.close(); neural = null; reset(); unavailable = true }

    @Synchronized fun enableNeural(context: Context) {
        if (neural == null) neural = NeuralSpeechWorker(context, ::acceptNeural)
    }
    @Synchronized private fun acceptNeural(start: Double, probability: Float, token: Long, boundary: Long) {
        if (token != generation || boundary != boundaryCount) return
        // Assign each 20 ms bin by its midpoint inside the observed 32 ms
        // inference interval. Never round both endpoints and fabricate a gap.
        val first = ceil((start - .01) * SPEECH_HZ - 1e-7).toInt()
        val end = ceil((start + .032 - .01) * SPEECH_HZ - 1e-7).toInt()
        for (bin in first until end) if (bin in 0 until 21600 * SPEECH_HZ) {
            neuralEvidence.getOrPut(bin / 1000) { ByteArray(1000) { -1 } }[bin % 1000] =
                (probability * 100).roundToInt().coerceIn(0, 100).toByte()
        }
    }
    fun awaitNeuralCapacity() = neural?.awaitCapacity()
    fun awaitNeuralReady() = neural?.awaitReady()
    fun awaitNeuralIdle() = neural?.awaitIdle()

    /** Flush/seek changes decoder continuity, not the identity of the soundtrack. */
    @Synchronized fun discontinuity() {
        head = 0; count = 0; lastTime = Double.NEGATIVE_INFINITY
        clearPartialFrame(); closeDetector(); boundaryCount++
    }

    @Synchronized fun diagnostics(): String = "generation=$generation,frames=$decodedFrameCount,blocks=${evidence.size}," +
        "boundaries=$boundaryCount,duplicates=$duplicateFrames,unavailable=$unavailable,reason=$failure,captureMs=${captureNanos / 1_000_000},${neural?.diagnostics()}"

    fun windows(positionSeconds: Double = Double.POSITIVE_INFINITY, limit: Int = Int.MAX_VALUE): List<SpeechWindow> {
        // Copy compact bytes under the capture monitor; expand/inspect evidence
        // outside it so a long-film validation never holds up the PCM renderer.
        val snapshot = synchronized(this) {
            fun known(source: java.util.TreeMap<Int, ByteArray>) = source.entries.filter { (block, values) ->
                block * 20.0 + 20 <= positionSeconds && values.none { it < 0 }
            }
            val preferred = if (neural?.ready == true) known(neuralEvidence) else emptyList()
            val useNeural = neural?.ready == true && (preferred.size >= 3 || neural?.dropped == 0L)
            val entries = if (useNeural) preferred else known(evidence)
            entries.map { it.key to it.value.copyOf() } to useNeural
        }
            val threshold = if (snapshot.second) 50 else 1
            val candidates = snapshot.first.filter { (_, values) ->
                if (limit == Int.MAX_VALUE) true else {
                    val voiced = values.count { it >= threshold }
                    voiced in 80..920 && (1 until values.size).count { (values[it] >= threshold) != (values[it - 1] >= threshold) } >= 6
                }
            }
            val selected = if (candidates.size <= limit) candidates else (0 until limit).map { i ->
                candidates[i * (candidates.size - 1) / (limit - 1).coerceAtLeast(1)]
            }
            return selected.map { (block, values) ->
            val start = block * 20.0
                SpeechWindow(start, DoubleArray(values.size) { if (values[it] >= threshold) 1.0 else 0.0 })
            }
    }

    /** Expose every contiguous observed run, including the current partial block.
     * A missing inference must not hide the other nineteen seconds of a block.
     * Keep detectors separate: combining their differing hangover policies would
     * introduce artificial speech boundaries. No seek gap or decode-ahead is filled.
     */
    fun quickWindows(positionSeconds: Double = Double.POSITIVE_INFINITY): List<SpeechWindow> {
        val snapshot = synchronized(this) {
            evidence.entries.map { it.key to it.value.copyOf() } to
                neuralEvidence.entries.map { it.key to it.value.copyOf() }
        }
        fun runs(entries: List<Pair<Int, ByteArray>>, threshold: Int): List<SpeechWindow> {
            val result = mutableListOf<SpeechWindow>()
            val values = mutableListOf<Double>()
            var start = -1; var previous = -2
            fun finish() {
                if (values.size >= 6 * SPEECH_HZ)
                    result.add(SpeechWindow(start.toDouble() / SPEECH_HZ, values.toDoubleArray()))
                values.clear(); start = -1
            }
            for ((block, bytes) in entries) for (i in bytes.indices) {
                val bin = block * 1000 + i
                if ((bin + 1).toDouble() / SPEECH_HZ > positionSeconds) break
                if (bytes[i] < 0 || bin != previous + 1) finish()
                if (bytes[i] >= 0) {
                    if (start < 0) start = bin
                    values.add(if (bytes[i] >= threshold) 1.0 else 0.0)
                }
                previous = bin
            }
            finish()
            return result
        }
        val neuralRuns = runs(snapshot.second, 50)
        // Neural startup can leave a short leading hole. Use it as soon as it
        // provides independent evidence, otherwise retain complete WebRTC runs.
        return if (neuralRuns.sumOf { it.speech.size } >= 18 * SPEECH_HZ) neuralRuns else runs(snapshot.first, 1)
    }

    @Synchronized fun pcm(buffer: ByteBuffer, format: Format, pts: Long, offset: Long) {
        if (unavailable && neural?.ready != true) return
        if (pts == C.TIME_UNSET || format.sampleRate !in 8000..192000 || format.channelCount !in 1..8) {
            failure = "invalid_pcm_clock_or_format"; return
        }
        val started = System.nanoTime()
        val width = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> { failure = "unsupported_pcm_${format.pcmEncoding}"; return }
        }
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerFrame = format.channelCount * width
        val declaredStart = subtitleMediaSeconds(pts, offset, 0, format.sampleRate)
        val expectedStart = lastTime + 1.0 / format.sampleRate
        // Codec PTS is quantised independently of sample counts. Keep sample
        // continuity for sub-frame rounding; real gaps remain explicit boundaries.
        val batchStart = if (lastTime.isFinite() && abs(declaredStart - expectedStart) <= .003) expectedStart else declaredStart
        var sourceFrame = 0
        try {
            while (input.remaining() >= bytesPerFrame) {
                val time = batchStart + sourceFrame++ / format.sampleRate.toDouble()
                if (time <= lastTime) { duplicateFrames++; input.position(input.position() + bytesPerFrame); continue }
                if (lastTime.isFinite() && abs(time - lastTime - 1.0 / format.sampleRate) > .005) {
                    // A seek/discontinuity is a boundary, never fabricated silent audio.
                    clearPartialFrame(); closeDetector(); boundaryCount++
                }
                lastTime = time
                var mono = 0.0
                repeat(format.channelCount) { channel ->
                    val value = when (format.pcmEncoding) {
                        C.ENCODING_PCM_8BIT -> ((input.get().toInt() and 255) - 128) / 128.0
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
                        times[head] = frameStart
                        neural?.offer(frame, frameStart, generation, boundaryCount)
                        bits[head] = try {
                            if (detectorFailed) -1 else {
                                val vad = detector ?: detectorFactory().also { detector = it }
                                if (vad.speech(frame)) 1 else 0
                            }
                        } catch (error: Exception) { detectorFailure(error.javaClass.simpleName) }
                        catch (error: LinkageError) { detectorFailure(error.javaClass.simpleName) }
                        // Unknown bins stay -1, never fabricated silence. Round the
                        // frame start once; codec PTS quantisation must not drift.
                        // Quantise once per decoder continuity, then count frames.
                        // Rounding each timestamp independently at a half-bin
                        // phase alternates ties due to floating-point noise,
                        // producing duplicates and permanent unknown holes.
                        val bin = nextDecisionBin ?: (frameStart * SPEECH_HZ).roundToInt()
                        nextDecisionBin = bin + 1
                        if (bin in 0 until 21600 * SPEECH_HZ) {
                            val block = evidence.getOrPut(bin / 1000) { ByteArray(1000) { -1 } }
                            block[bin % 1000] = bits[head]
                        }
                        head = (head + 1) % times.size; count = min(count + 1, times.size)
                        decodedFrameCount++
                        frameCount = 0
                    }
                }
            }
        } catch (error: Exception) { failCapture(error.javaClass.simpleName) }
        catch (error: LinkageError) { failCapture(error.javaClass.simpleName) } // Capture failure must never stop playback.
        finally { captureNanos += System.nanoTime() - started }
    }
    private fun failCapture(reason: String) { failure = reason; unavailable = true; closeDetector(); clearPartialFrame() }
    private fun detectorFailure(reason: String): Byte {
        detectorFailed = true; failure = "webrtc_$reason"; closeDetector()
        unavailable = neural == null || neural?.failure != null
        return -1
    }
    @Synchronized fun noDecodedPcm() { failCapture("encoded_passthrough_without_decoder") }
    @Synchronized fun decodedPcmAvailable() {
        if (failure == "encoded_passthrough_without_decoder") { unavailable = false; failure = null }
    }

    private data class Snapshot(val times: DoubleArray, val bits: ByteArray)
    @Synchronized private fun snapshot(maxMediaSeconds: Double): Snapshot {
        val t = DoubleArray(count); val b = ByteArray(count)
        var copied = 0
        for (i in 0 until count) {
            val index = (head - count + i + times.size) % times.size
            if (times[index] + .02 > maxMediaSeconds + 1e-6) continue
            t[copied] = times[index]; b[copied++] = bits[index]
        }
        return if (copied == count) Snapshot(t, b) else Snapshot(t.copyOf(copied), b.copyOf(copied))
    }

    /** One contiguous exchange ending at the tap position. Never select older scenes,
     * decode-ahead audio, or bridge a seek. No waiting for additional samples.
     */
    fun currentWindow(positionSeconds: Double): SpeechWindow? {
        if (!positionSeconds.isFinite() || positionSeconds < 0) return null
        val data = snapshot(positionSeconds)
        val end = data.times.lastIndex
        if (end < 0 || positionSeconds - data.times[end] > .25) return null
        var first = end
        while (first > 0 && end - first + 1 < 12 * SPEECH_HZ &&
            abs(data.times[first] - data.times[first - 1] - 1.0 / SPEECH_HZ) < .003) first--
        if (end - first + 1 < 6 * SPEECH_HZ) return null
        // A tap during dialogue should use dialogue now, not a stale earlier line.
        val recentStart = max(first, end - SPEECH_HZ + 1)
        var voiced = 0
        for (i in recentStart..end) voiced += data.bits[i]
        if (voiced < 5) return null
        return SpeechWindow(data.times[first], DoubleArray(end - first + 1) { data.bits[first + it].toDouble() })
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
            capture.discontinuity()
        format = config.format
        // Some HDMI routes can play formats the phone cannot decode. Preserve
        // their playback; explain missing PCM instead of disabling working audio.
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) capture.noDecodedPcm()
        else capture.decodedPcmAvailable()
    }
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        if (offset != outputStreamOffsetUs) { capturedBuffer = null; capture.discontinuity() }
        offset = outputStreamOffsetUs; super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }
    override fun handleDiscontinuity() { capturedBuffer = null; capturedPts = C.TIME_UNSET; capture.discontinuity(); super.handleDiscontinuity() }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (capturedBuffer !== buffer || capturedPts != presentationTimeUs) {
            capturedBuffer = buffer; capturedPts = presentationTimeUs
            capture.pcm(buffer, format, presentationTimeUs, offset)
        }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (consumed) capturedBuffer = null
        return consumed
    }
    override fun flush() { capturedBuffer = null; capturedPts = C.TIME_UNSET; capture.discontinuity(); super.flush() }
    override fun reset() { capturedBuffer = null; capturedPts = C.TIME_UNSET; capture.discontinuity(); super.reset() }
    override fun release() { capture.close(); super.release() }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SpeechCaptureRenderers(context: Context, private val capture: PlaybackSpeechBuffer) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        SpeechCaptureAudioSink(checkNotNull(super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)), capture)
}
