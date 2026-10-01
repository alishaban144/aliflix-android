package com.aliflix.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Six minutes of timestamped speech decisions (~162 KiB), never raw audio.
 * 8 kHz speech-band energy / zero crossings with adaptive noise floor and 60 ms hangover.
 * Only a duplicate PCM view is read; capture never owns, modifies or delays playback buffers.
 */
internal class PlaybackSpeechBuffer {
    private val times = DoubleArray(18000)
    private val bits = ByteArray(18000)
    private var head = 0
    private var count = 0
    private var lastTime = Double.NEGATIVE_INFINITY
    private var bin = Long.MIN_VALUE
    private var energy = 0.0
    private var total = 0.0
    private var samples = 0
    private var crossings = 0
    private var previous = 0.0
    private var highpass = 0.0
    private var lowpass = 0.0
    private var noise = .00001
    private var hangover = 0
    @Volatile var generation = 0L
        private set

    @Synchronized fun reset() {
        head = 0; count = 0; lastTime = Double.NEGATIVE_INFINITY; bin = Long.MIN_VALUE
        clearFrame(); previous = 0.0; highpass = 0.0; lowpass = 0.0; noise = .00001; hangover = 0
        generation++
    }
    private fun clearFrame() { energy = 0.0; total = 0.0; samples = 0; crossings = 0 }
    @Synchronized fun pcm(buffer: ByteBuffer, format: Format, pts: Long, offset: Long) {
        if (format.sampleRate !in 8000..192000 || format.channelCount !in 1..8) return
        val bytesPerSample = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val stride = max(1, format.sampleRate / 8000)
        val bytesPerFrame = format.channelCount * bytesPerSample
        var frame = 0
        while (input.remaining() >= bytesPerFrame) {
            val time = subtitleMediaSeconds(pts, offset, frame, format.sampleRate)
            val currentFrame = frame++
            if (currentFrame % stride != 0 || time <= lastTime) {
                input.position(input.position() + bytesPerFrame)
                continue
            }
            var mono = 0.0
            repeat(format.channelCount) {
                mono += when (format.pcmEncoding) {
                    C.ENCODING_PCM_16BIT -> input.short / 32768.0
                    C.ENCODING_PCM_24BIT -> {
                        val value = (input.get().toInt() and 255) or ((input.get().toInt() and 255) shl 8) or (input.get().toInt() shl 16)
                        value / 8388608.0
                    }
                    C.ENCODING_PCM_32BIT -> input.int / 2147483648.0
                    else -> input.float.toDouble().takeIf { it.isFinite() } ?: 0.0
                }
            }
            if (lastTime.isFinite() && abs(time - lastTime) > .1) {
                clearFrame(); bin = Long.MIN_VALUE; previous = 0.0; highpass = 0.0; lowpass = 0.0; hangover = 0
            }
            lastTime = time
            val nextBin = floor(time * SPEECH_HZ).toLong()
            if (nextBin != bin) {
                if (bin != Long.MIN_VALUE && samples >= 100) {
                    val e = energy / samples
                    val z = crossings.toDouble() / samples
                    val ratio = energy / max(total, 1e-12)
                    val voiced = e > max(.00002, noise * 3.5) && z in .025.. .50 && ratio > .18
                    if (voiced) hangover = 3 else if (hangover > 0) hangover--
                    if (!voiced) noise = .98 * noise + .02 * min(e, noise * 2)
                    times[head] = bin.toDouble() / SPEECH_HZ
                    bits[head] = if (voiced || hangover > 0) 1 else 0
                    head = (head + 1) % times.size; count = min(count + 1, times.size)
                }
                bin = nextBin; clearFrame()
            }
            val x = mono / format.channelCount
            val hp = .87 * (highpass + x - previous)
            previous = x; highpass = hp
            val filtered = lowpass + .70 * (hp - lowpass)
            if ((filtered >= 0) != (lowpass >= 0)) crossings++
            lowpass = filtered; energy += filtered * filtered; total += x * x; samples++
        }
    }

    /** Non-overlapping dialogue-rich windows, with gaps/seeks never treated as silence. */
    @Synchronized fun windows(): List<SpeechWindow> {
        val result = mutableListOf<SpeechWindow>()
        val length = 25 * SPEECH_HZ
        var i = 0
        fun index(k: Int) = (head - count + k + times.size) % times.size
        while (i + length <= count && result.size < 6) {
            val start = times[index(i)]
            var contiguous = true
            var voiced = 0
            var transitions = 0
            for (k in 0 until length) {
                val idx = index(i + k)
                if (abs(times[idx] - (start + k.toDouble() / SPEECH_HZ)) > .01) { contiguous = false; break }
                voiced += bits[idx]
                if (k > 0 && bits[idx] != bits[index(i + k - 1)]) transitions++
            }
            if (contiguous && voiced.toDouble() / length in .15.. .8 && transitions >= 10) {
                result += SpeechWindow(start, DoubleArray(length) { bits[index(i + it)].toDouble() })
                i += length + 5 * SPEECH_HZ
            } else i += SPEECH_HZ
        }
        return result
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SpeechCaptureRenderers(context: Context, private val capture: PlaybackSpeechBuffer) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        object : ForwardingAudioSink(checkNotNull(super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams))) {
            private var format = Format.Builder().build()
            private var offset = 0L
            override fun configure(config: AudioSink.AudioSinkConfig) {
                if (format.sampleRate != config.format.sampleRate || format.channelCount != config.format.channelCount || format.pcmEncoding != config.format.pcmEncoding)
                    capture.reset()
                format = config.format; super.configure(config)
            }
            override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { offset = outputStreamOffsetUs; super.setOutputStreamOffsetUs(outputStreamOffsetUs) }
            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                capture.pcm(buffer, format, presentationTimeUs, offset)
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            override fun flush() { capture.reset(); super.flush() }
        }
}
