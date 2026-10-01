package com.aliflix.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class PlaybackSpeechBufferTest {
    @Test fun timestampedPcmUsesMediaClockAndDoesNotConsumeOrDuplicatePlaybackBuffers() {
        val capture = PlaybackSpeechBuffer()
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
}
