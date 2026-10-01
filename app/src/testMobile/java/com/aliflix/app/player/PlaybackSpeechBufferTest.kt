package com.aliflix.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class PlaybackSpeechBufferTest {
    private fun capture() = PlaybackSpeechBuffer {
        object : PlaybackSpeechDetector {
            override fun speech(frame: ShortArray) = frame.any { kotlin.math.abs(it.toInt()) > 200 }
            override fun close() = Unit
        }
    }
    @Test fun timestampedPcmUsesMediaClockAndDoesNotConsumeOrDuplicatePlaybackBuffers() {
        val capture = capture()
        val format = Format.Builder().setSampleMimeType("audio/raw").setSampleRate(8000)
            .setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        val buffer = ByteBuffer.allocate(70 * 8000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(70 * 8000) { i ->
            val time = i / 8000.0
            val voiced = time % 4.3 < 1.8
            val signal = if (voiced) .06 * sin(2 * Math.PI * 700 * time) + .02 * sin(2 * Math.PI * 1200 * time) else 0.0
            buffer.putShort((signal * 32767).toInt().toShort())
        }
        buffer.flip()
        capture.pcm(buffer, format, 30_000_000, 20_000_000)
        assertEquals(0, buffer.position())
        val first = capture.windows()
        assertTrue(first.size >= 2)
        assertTrue(first.first().start >= 10.0)
        assertTrue(first.first().start < 12.0)
        capture.pcm(buffer, format, 30_000_000, 20_000_000)
        assertEquals(first.map { it.start }, capture.windows().map { it.start })
        val generation = capture.generation
        capture.reset()
        assertTrue(capture.windows().isEmpty())
        assertTrue(capture.generation > generation)
    }

    @Test fun realCodecSized44100HzBuffersRemainContiguousAndCollectTwoScenesInTwentySeconds() {
        val capture = capture()
        val rate = 44100
        val format = Format.Builder().setSampleMimeType("audio/raw").setSampleRate(rate)
            .setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        var first = 0
        val end = 22 * rate
        while (first < end) {
            val length = minOf(1024, end - first)
            val buffer = ByteBuffer.allocate(length * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(length) { i ->
                val time = (first + i) / rate.toDouble()
                val signal = if (time % 3.7 < 1.3) .1 * sin(2 * Math.PI * 500 * time) else 0.0
                repeat(2) { buffer.putShort((signal * 32767).toInt().toShort()) }
            }
            buffer.flip()
            capture.pcm(buffer, format, 10_000_000L + first * 1_000_000L / rate, 10_000_000)
            assertEquals(0, buffer.position())
            first += length
        }
        assertEquals(1, capture.windows(19.9).size)
        assertEquals(2, capture.windows(20.1).size)
        assertFalse(capture.unavailable)
    }

    @Test fun freshScenesReplaceRejectedEvidenceAndSeeksNeverFabricateSilence() {
        val capture = capture()
        val format = Format.Builder().setSampleMimeType("audio/raw").setSampleRate(8000)
            .setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        fun feed(start: Int, duration: Int) {
            val data = ByteBuffer.allocate(duration * 8000 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(duration * 8000) { i ->
                data.putShort(if (i / 8000.0 % 4.3 < 1.8) 1000 else 0)
            }
            data.flip(); capture.pcm(data, format, start * 1_000_000L, 0)
        }
        feed(0, 65)
        val first = capture.windows().map { it.start }
        feed(65, 20)
        val newer = capture.windows().map { it.start }
        assertEquals(6, newer.size)
        assertTrue(newer.last() > first.last())
        feed(1000, 11)
        assertTrue(capture.windows().all { it.start < 85 || it.start >= 1000 })
        capture.reset()
        assertTrue(capture.windows().isEmpty())
    }

    @Test fun captureErrorsCannotConsumePlaybackAudioOrEscapeIntoTheRenderer() {
        val capture = PlaybackSpeechBuffer { throw UnsatisfiedLinkError("unsupported ABI") }
        val format = Format.Builder().setSampleRate(8000).setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        val data = ByteBuffer.allocate(640)
        capture.pcm(data, format, 0, 0)
        assertEquals(0, data.position())
        assertTrue(capture.unavailable)
        capture.reset()
        assertFalse(capture.unavailable)
    }

    @Test fun sinkSelectsPcmAndCapturesPartialWriteRetriesOnlyOnce() {
        val capture = capture()
        var consumed = false
        val delegate = java.lang.reflect.Proxy.newProxyInstance(
            androidx.media3.exoplayer.audio.AudioSink::class.java.classLoader,
            arrayOf(androidx.media3.exoplayer.audio.AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "supportsFormat" -> true
                "getFormatSupport" -> androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "handleBuffer" -> {
                    val buffer = args!![0] as ByteBuffer
                    buffer.position(if (consumed) buffer.limit() else buffer.limit() / 2)
                    consumed.also { consumed = true }
                }
                else -> null
            }
        } as androidx.media3.exoplayer.audio.AudioSink
        val sink = SpeechCaptureAudioSink(delegate, capture) { true }
        val pcm = Format.Builder().setSampleMimeType("audio/raw").setSampleRate(8000)
            .setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        assertTrue(sink.supportsFormat(pcm))
        assertFalse(sink.supportsFormat(Format.Builder().setSampleMimeType("audio/ac3").build()))
        val passthroughOnly = SpeechCaptureAudioSink(delegate, capture) { false }
        val encoded = Format.Builder().setSampleMimeType("audio/ac3").build()
        assertTrue("A passthrough-only device must retain working audio", passthroughOnly.supportsFormat(encoded))
        passthroughOnly.configure(androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig.Builder(encoded).build())
        assertTrue(capture.unavailable)
        capture.reset()
        assertFalse(sink.getFormatOffloadSupport(pcm).isFormatSupported)
        sink.configure(androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig.Builder(pcm).build())
        val data = ByteBuffer.allocate(22 * 8000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(22 * 8000) { i -> data.putShort(if (i / 8000.0 % 4.3 < 1.8) 1000 else 0) }
        data.flip()
        assertFalse(sink.handleBuffer(data, 0, 1))
        val starts = capture.windows().map { it.start }
        assertEquals(2, starts.size)
        assertTrue(sink.handleBuffer(data, 0, 1))
        assertEquals(starts, capture.windows().map { it.start })
        sink.flush()
        assertTrue(capture.windows().isEmpty())
    }
}
