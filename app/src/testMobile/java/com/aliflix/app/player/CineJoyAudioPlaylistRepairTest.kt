package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CineJoyAudioPlaylistRepairTest {
    private val root = "https://cdn.test/hls/catalog/"
    private val mediaRoot = "https://cdn.test/hls/media/"
    private val masterUrl = "https://cdn.test/master.m3u8"
    private fun master(includeTarget: Boolean = true) = buildString {
        append("#EXTM3U\n")
        for (n in if (includeTarget) listOf(1, 3, 4) else listOf(1, 4))
            append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Track $n\",URI=\"${root}audio_$n.m3u8\"\n")
        append("#EXT-X-STREAM-INF:AUDIO=\"a\",BANDWIDTH=1000\nvideo.m3u8\n")
    }
    private fun playlist(n: Int, middle: Double = 1.0, extra: String = "") = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:1
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-MAP:URI="${mediaRoot}audio_${n}_init.html"
        $extra
        #EXTINF:1.0,
        #EXT-X-BITRATE:130
        ${mediaRoot}audio_${n}_1.html
        #EXTINF:$middle,
        ${mediaRoot}audio_${n}_2.html
        #EXTINF:0.5,
        ${mediaRoot}audio_${n}_3.html
        #EXT-X-ENDLIST
    """.trimIndent()
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach(::putInt) }.array()
    private fun box(type: String, data: ByteArray) = ints(data.size + 8) + type.toByteArray() + data
    private fun init(handler: String = "soun") = box("moov", box("trak", box("mdia",
        box("hdlr", ints(0, 0) + handler.toByteArray()) + box("mdhd", ints(0, 0, 0, 1000, 0)))))
    private fun fragment(time: Int, duration: Int) = box("moof", box("traf",
        box("tfhd", ints(0, 1)) + box("tfdt", ints(0, time)) + box("trun", ints(256, 1, duration))))
    private fun files(includeTarget: Boolean = true, middle: Double = 1.0, lastTime: Int = 2000, extra: String = "") = mapOf(
        masterUrl to master(includeTarget).toByteArray(),
        "${root}audio_1.m3u8" to playlist(1).toByteArray(),
        "${root}audio_4.m3u8" to playlist(4, middle, extra).toByteArray(),
        "${mediaRoot}audio_3_init.html" to init(),
        "${mediaRoot}audio_3_1.html" to fragment(0, 1000),
        "${mediaRoot}audio_3_3.html" to fragment(lastTime, 475),
    )
    @Test fun repairsDeclaredAudioUsingAgreeingSiblingsAndActualFinalDuration() {
        val requests = mutableListOf<String>()
        val data = files()
        val result = CineJoyAudioPlaylistRepair.repair(masterUrl, "${root}audio_3.m3u8") {
            requests += it; data.getValue(it)
        }
        assertNotNull(result)
        assertTrue(result!!.contains("#EXTINF:0.47500,"))
        assertTrue(result.contains("${mediaRoot}audio_3_init.html"))
        assertTrue(result.contains("${mediaRoot}audio_3_2.html"))
        assertFalse(result.contains("audio_1_"))
        assertFalse(result.contains("audio_4_"))
        assertEquals(6, requests.size)
    }
    @Test fun refusesUndeclaredTracksMismatchedTimelinesAndDiscontinuities() {
        for (data in listOf(files(includeTarget = false), files(middle = 1.2), files(extra = "#EXT-X-DISCONTINUITY")))
            assertNull(CineJoyAudioPlaylistRepair.repair(masterUrl, "${root}audio_3.m3u8", data::getValue))
    }
    @Test fun refusesTargetAudioWithDifferentDecodeTimestamps() {
        val data = files(lastTime = 3000)
        assertNull(CineJoyAudioPlaylistRepair.repair(masterUrl, "${root}audio_3.m3u8", data::getValue))
    }
    @Test fun acceptsConsistentTimestampOffsetAndMultipleAudioSampleRuns() {
        val data = files(lastTime = 12000).toMutableMap()
        data["${mediaRoot}audio_3_1.html"] = box("moof", box("traf",
            box("tfhd", ints(0, 1)) + box("tfdt", ints(0, 10000)) +
                box("trun", ints(256, 1, 400)) + box("trun", ints(256, 1, 600))))
        assertNotNull(CineJoyAudioPlaylistRepair.repair(masterUrl, "${root}audio_3.m3u8", data::getValue))
    }
    @Test fun timingReaderRejectsNonAudioAndMalformedBoxes() {
        for (bytes in listOf(init("vide"), byteArrayOf(0, 1, 2), ints(Int.MAX_VALUE) + "moov".toByteArray())) {
            try { AudioFragmentTiming.timescale(bytes); fail("Expected invalid audio initialization") }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals(1000L, AudioFragmentTiming.timescale(init()))
        assertEquals(2.0 to 0.475, AudioFragmentTiming.fragment(fragment(2000, 475), 1000))
    }
}
