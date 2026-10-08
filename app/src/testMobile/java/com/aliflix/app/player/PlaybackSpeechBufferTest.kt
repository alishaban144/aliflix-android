package com.aliflix.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class PlaybackSpeechBufferTest {
    @Test fun finiteDialogueContainsOnlyPlayedContiguousPcmAndClearsOnSeek() {
        val capture = capture()
        capture.dialogueFrameObserver = { _, _, _, _, _ -> }
        val format = Format.Builder().setSampleRate(16000).setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        val buffer = ByteBuffer.allocate(20 * 16000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(20 * 16000) { buffer.putShort(1200) }; buffer.flip()
        capture.pcm(buffer, format, 100_000_000, 0)
        val observed = requireNotNull(capture.playedDialoguePcm(115.0))
        assertEquals(100.0, observed.start, .001)
        assertTrue(observed.start + observed.frames.size * .02 <= 115.0)
        assertNull(capture.playedDialoguePcm(105.0))
        capture.discontinuity()
        assertNull(capture.playedDialoguePcm(115.0))
    }

    @Test fun localWordDecoderReceivesSelectedSixteenKhzAudioWithTheSameClockAndSeekBoundary() {
        val capture = capture()
        val frames = mutableListOf<Triple<Double, ShortArray, Long>>()
        capture.dialogueFrameObserver = { time, pcm, _, boundary, _ -> frames.add(Triple(time, pcm.copyOf(), boundary)) }
        val format = Format.Builder().setSampleRate(48000).setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        fun feed(start: Long) {
            val input = ByteBuffer.allocate(4800 * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(4800) { input.putShort(1200).putShort(1200) }; input.flip()
            capture.pcm(input, format, start, 0)
            assertEquals(0, input.position())
        }
        feed(10_000_000)
        assertEquals(5, frames.size)
        assertTrue(frames.all { it.second.size == 320 && it.second.all { sample -> sample in 1198..1201 } })
        assertEquals(10.0, frames.first().first, .0001)
        assertEquals(10.08, frames.last().first, .0001)
        val boundary = frames.last().third
        capture.discontinuity(); feed(30_000_000)
        assertTrue(frames.last().third > boundary)
        assertEquals(30.08, frames.last().first, .0001)
    }
    @Test fun partialUnalignedRunsAreAvailableWithinEighteenSecondsAndNeverBridgeASeek() {
        val capture = capture()
        val format = Format.Builder().setSampleRate(8000).setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        fun feed(startUs: Long, seconds: Int) {
            val data = ByteBuffer.allocate(seconds * 16000).order(ByteOrder.LITTLE_ENDIAN)
            repeat(seconds * 8000) { data.putShort(if (it / 8000.0 % 4.3 < 1.8) 1000 else 0) }
            data.flip(); capture.pcm(data, format, startUs, 0)
        }
        feed(201_350_000, 18)
        assertTrue("Old all-or-nothing block path hides the entire run", capture.windows().isEmpty())
        val first = capture.quickWindows(219.35).single()
        assertEquals(201.36, first.start, .02)
        assertTrue(first.speech.size in 18 * SPEECH_HZ - 1..18 * SPEECH_HZ)
        val heard = capture.quickWindows(213.35).single()
        assertTrue(heard.start + heard.speech.size.toDouble() / SPEECH_HZ <= 213.35)
        capture.discontinuity(); feed(501_350_000, 9)
        assertEquals(2, capture.quickWindows().size)
        assertEquals(27 * SPEECH_HZ, capture.quickWindows().sumOf { it.speech.size })
        capture.reset()
        assertTrue(capture.quickWindows().isEmpty())
    }
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
        val first = capture.currentWindow(79.9)!!
        assertTrue(first.start >= 67.8)
        assertTrue(first.start < 68.0)
        assertEquals(600, first.speech.size)
        capture.pcm(buffer, format, 30_000_000, 20_000_000)
        assertEquals(first.start, capture.currentWindow(79.9)!!.start, 1e-8)
        assertArrayEquals(first.speech, capture.currentWindow(79.9)!!.speech, 0.0)
        val generation = capture.generation
        capture.reset()
        assertNull(capture.currentWindow(79.9))
        assertTrue(capture.generation > generation)
    }

    @Test fun realCodecSized44100HzBuffersProvideOneCurrentExchangeWithoutFutureAudio() {
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
        assertNull(capture.currentWindow(5.9))
        val recent = capture.currentWindow(7.9)!!
        assertTrue(recent.start + recent.speech.size / 50.0 <= 7.9)
        assertTrue(recent.speech.size in 300..600)
        assertNotNull(capture.currentWindow(20.1))
        assertEquals(1, capture.windows(22.0).size)
        assertFalse(capture.unavailable)
    }

    @Test fun seekRetainsObservedHistoryButNeverFillsUnheardGapsOrRejectsBackwardPcm() {
        val capture = capture()
        val format = Format.Builder().setSampleRate(8000).setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        fun feed(start: Int, seconds: Int) {
            val data = ByteBuffer.allocate(seconds * 16000).order(ByteOrder.LITTLE_ENDIAN)
            repeat(seconds * 8000) { data.putShort(if (it / 8000.0 % 4.3 < 1.8) 1000 else 0) }
            data.flip(); capture.pcm(data, format, start * 1_000_000L, 0)
        }
        feed(0, 40)
        val generation = capture.generation
        val before = capture.windows()
        capture.discontinuity(); feed(200, 20)
        assertEquals(listOf(0.0, 20.0, 200.0), capture.windows().map { it.start })
        assertEquals(generation, capture.generation)
        capture.discontinuity(); feed(0, 20)
        assertArrayEquals(before.first().speech, capture.windows().first().speech, 0.0)
        assertEquals(listOf(0.0, 20.0, 200.0), capture.windows().map { it.start })
        assertTrue(capture.decodedFrameCount >= 4000)
        capture.reset()
        assertTrue(capture.windows().isEmpty())
        assertTrue(capture.generation > generation)
    }

    @Test fun onlyCurrentDialogueIsReturnedAndSeeksNeverJoinEarlierScenes() {
        val capture = capture()
        val format = Format.Builder().setSampleMimeType("audio/raw").setSampleRate(8000)
            .setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        fun feed(start: Int, duration: Int) {
            val data = ByteBuffer.allocate(duration * 8000 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(duration * 8000) { i -> data.putShort(if (i / 8000.0 % 4.3 < 1.8) 1000 else 0) }
            data.flip(); capture.pcm(data, format, start * 1_000_000L, 0)
        }
        feed(0, 65)
        val first = capture.currentWindow(64.9)!!
        assertTrue(first.start > 52)
        assertNull(capture.currentWindow(30.0)) // Old scenes have left the bounded buffer.
        feed(1000, 11)
        assertNull(capture.currentWindow(1005.0)) // A gap must not borrow an earlier scene.
        assertTrue(capture.currentWindow(1010.9)!!.start >= 1000)
        assertNull(capture.currentWindow(1012.0)) // No stale/future speech.
        capture.reset()
        assertNull(capture.currentWindow(1010.9))
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
        val current = capture.currentWindow(21.9)!!
        assertTrue(sink.handleBuffer(data, 0, 1))
        assertEquals(current.start, capture.currentWindow(21.9)!!.start, 1e-8)
        assertArrayEquals(current.speech, capture.currentWindow(21.9)!!.speech, 0.0)
        sink.flush()
        assertNull(capture.currentWindow(21.9))
    }
}
