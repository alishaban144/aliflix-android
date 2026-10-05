package com.aliflix.app.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.*
import androidx.media3.extractor.wav.WavExtractor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** No Android emulator: real Media3 extraction, production capture, real JNI VAD.
 * Only the hardware AudioTrack downstream of the capture is replaced. Device
 * codec/rendering integration is covered separately by the instrumentation test.
 */
class NativeSpeechCaptureTest {
    @Test fun media3ExtractedPcmThroughProductionSinkMatchesOriginalVAD() {
        assumeTrue("Run with ALIFLIX_SYNC_HOST_VAD=1 and the host JNI library on java.library.path",
            System.getenv("ALIFLIX_SYNC_HOST_VAD") == "1")
        // Enable Media3's strict parser bounds explicitly on the non-Android host.
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        val data = JSONObject(javaClass.getResource("/audio-sync-human-speech.json")!!.readText()).getJSONArray("clips").getJSONObject(0)
        val oracle = data.getString("webrtc")
        val wav = File(requireNotNull(System.getenv("ALIFLIX_SYNC_TESTSET")), data.getString("name")).readBytes()
        val capture = PlaybackSpeechBuffer()
        var speed = PlaybackParameters.DEFAULT
        var retried = false
        var writes = 0
        val downstream = java.lang.reflect.Proxy.newProxyInstance(AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "handleBuffer" -> {
                    val b = args!![0] as ByteBuffer
                    writes++
                    if (!retried) { retried = true; b.position(b.position() + b.remaining() / 2); false }
                    else { retried = false; b.position(b.limit()); true }
                }
                "setPlaybackParameters" -> { speed = args!![0] as PlaybackParameters; null }
                "getPlaybackParameters" -> speed
                else -> null
            }
        } as AudioSink
        val sink = SpeechCaptureAudioSink(downstream, capture) { true }
        sink.setOutputStreamOffsetUs(17_000_000)
        val pending = ByteArrayOutputStream()
        var map: SeekMap? = null
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                assertEquals(C.TRACK_TYPE_AUDIO, type)
                return object : TrackOutput {
                    override fun format(format: Format) {
                        assertEquals(16000, format.sampleRate)
                        assertEquals(C.ENCODING_PCM_16BIT, format.pcmEncoding)
                        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
                    }
                    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                        val bytes = ByteArray(minOf(length, 2051))
                        val n = input.read(bytes, 0, bytes.size)
                        if (n > 0) pending.write(bytes, 0, n)
                        return n
                    }
                    override fun sampleData(input: ParsableByteArray, length: Int, sampleDataPart: Int) {
                        pending.write(input.data, input.position, length); input.skipBytes(length)
                    }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        val bytes = pending.toByteArray()
                        val pcm = ByteBuffer.wrap(bytes, bytes.size - offset - size, size).slice()
                        if (timeUs >= 5_000_000) sink.setPlaybackParameters(PlaybackParameters(1.5f))
                        assertFalse(sink.handleBuffer(pcm, timeUs + 17_000_000, 1))
                        assertTrue(sink.handleBuffer(pcm, timeUs + 17_000_000, 1))
                        pending.reset()
                        if (offset > 0) pending.write(bytes, bytes.size - offset, offset)
                    }
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        }
        val extractor = WavExtractor()
        extractor.init(output)
        fun inputAt(start: Long): DefaultExtractorInput {
            var position = start.toInt()
            return DefaultExtractorInput(DataReader { b, off, length ->
                if (position == wav.size) -1 else minOf(length, wav.size - position, 4093).also {
                    wav.copyInto(b, off, position, position + it); position += it
                }
            }, start, wav.size.toLong())
        }
        fun drain(start: Long = 0) {
            var input = inputAt(start)
            val seek = PositionHolder()
            var calls = 0
            while (true) {
                check(calls++ < 100000)
                when (extractor.read(input, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> return
                    Extractor.RESULT_SEEK -> input = inputAt(seek.position)
                }
            }
        }
        try {
            drain()
            assertFalse(capture.diagnostics(), capture.unavailable)
            val window = capture.currentWindow(11.50)!!
            assertEquals(0.0, window.start, 1e-6)
            assertEquals(575, window.speech.size)
            repeat(window.speech.size) { assertEquals("Native VAD frame $it", if (oracle[it] == '1') 1.0 else 0.0, window.speech[it], 0.0) }
            assertEquals(1.5f, sink.playbackParameters.speed)
            assertTrue(writes > 100)
            val epoch = capture.generation
            sink.handleDiscontinuity()
            assertEquals(epoch, capture.generation) // Seek retains soundtrack identity.
            assertNull(capture.currentWindow(11.50))
            val point = checkNotNull(map).getSeekPoints(2_000_000).first
            extractor.seek(point.position, point.timeUs)
            drain(point.position)
            assertFalse(capture.diagnostics(), capture.unavailable)
            assertEquals(point.timeUs / 1e6, capture.currentWindow(11.50)!!.start, 1e-6)
        } finally { capture.close(); extractor.release() }
    }
}
