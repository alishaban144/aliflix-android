package com.aliflix.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class OfflineSpeechClockTest {
    @Test fun fixedTrackScannerPreservesExactPcmAcrossPeriodNotificationsButNeverBridgesRealGapsOrFlushes() {
        fun capture()=PlaybackSpeechBuffer { object:PlaybackSpeechDetector {
            override fun speech(frame:ShortArray)=false
            override fun close()=Unit
        } }
        val delegate=java.lang.reflect.Proxy.newProxyInstance(AudioSink::class.java.classLoader,arrayOf(AudioSink::class.java)) { _,_,_->null } as AudioSink
        val format=Format.Builder().setSampleMimeType("audio/raw").setSampleRate(8000).setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        for(gap in listOf(0L,500_000L)) {
            val capture=capture()
            val sink=OfflineSpeechAnalysis.ScanAudioSink(delegate,capture,validatePcmContinuity=true)
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
            sink.setOutputStreamOffsetUs(17_000_000)
            sink.handleBuffer(ByteBuffer.allocate(64_080*2),17_000_000,1)
            sink.handleDiscontinuity()
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
            sink.setOutputStreamOffsetUs(21_000_000)
            sink.handleBuffer(ByteBuffer.allocate(96_080*2),29_010_000+gap,1)
            if(gap==0L) {
                assertEquals(1,capture.windows().size)
                assertNotNull(capture.decodedDialoguePcm(0.0,20.0))
            } else {
                assertTrue(capture.windows().isEmpty())
                val after=requireNotNull(capture.decodedDialoguePcm(0.0,20.0))
                assertTrue(after.start>=8.5)
                assertTrue(after.start+after.frames.size*.02<=20.001)
                assertTrue(capture.quickWindows().zipWithNext().any { (a,b)->b.start-a.start-a.speech.size*.02>.4 })
            }
            sink.flush()
            assertNull(capture.decodedDialoguePcm(0.0,20.0))
            capture.close()
        }
    }
    @Test fun independentScannerUsesRendererClockButRecordsMediaPtsWithoutWritingHardwareAudio() {
        var hardwareWrites = 0
        val delegate = java.lang.reflect.Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            when (method.name) {
                "handleBuffer", "configure", "play", "pause" -> { hardwareWrites++; null }
                "supportsFormat" -> true
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                else -> null
            }
        } as AudioSink
        val capture = PlaybackSpeechBuffer { object : PlaybackSpeechDetector {
            override fun speech(frame: ShortArray) = false
            override fun close() = Unit
        } }
        val sink = OfflineSpeechAnalysis.ScanAudioSink(delegate, capture)
        val format = Format.Builder().setSampleMimeType("audio/raw").setSampleRate(8000).setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
        assertFalse(sink.hasPendingData())
        assertFalse(sink.supportsFormat(Format.Builder().setSampleMimeType("audio/aac").build()))
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        sink.setOutputStreamOffsetUs(17_000_000)
        val buffer = ByteBuffer.allocate(22 * 16000)
        assertTrue(sink.handleBuffer(buffer, 17_000_000, 1))
        assertEquals(buffer.limit(), buffer.position())
        assertEquals(17_000_000, sink.getCurrentPositionUs(false))
        assertTrue(sink.hasPendingData())
        assertEquals(0.0, capture.windows().single().start, 0.0)
        sink.play(); sink.pause(); sink.playToEndOfStream()
        assertTrue(sink.isEnded())
        assertFalse(sink.hasPendingData())
        sink.flush()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
        assertFalse(sink.isEnded())
        assertEquals(0, hardwareWrites)
    }
}
