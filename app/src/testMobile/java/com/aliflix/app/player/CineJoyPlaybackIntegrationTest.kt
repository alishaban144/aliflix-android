package com.aliflix.app.player

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.zip.ZipInputStream

/** JVM Media3 integration: real HLS loading, MP4 extraction and track selection;
 * sample-consuming fake renderers, no Android device/emulator or hardware decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CineJoyPlaybackIntegrationTest {
    private class Fixture : AutoCloseable {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var failTrack = ""
        @Volatile var failuresRemaining = 0
        val saved = mutableListOf<String?>()
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val video = FakeRenderer(C.TRACK_TYPE_VIDEO)
        private val files = buildMap<String, ByteArray> {
            ZipInputStream(checkNotNull(javaClass.getResourceAsStream("/cinejoy/playback-fixture.zip"))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                }
            }
        }
        private val master = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            for (i in 1..4) append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Track $i\",DEFAULT=${if (i == 1) "YES" else "NO"},AUTOSELECT=YES,URI=\"track$i/audio.m3u8\"\n")
            append("#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=160x90,CODECS=\"avc1.42c00b,mp4a.40.2\",AUDIO=\"audio\"\nvideo.m3u8\n")
        }.toByteArray()
        private val request = NativePlaybackRequest("https://fixture.test/master.m3u8", "application/x-mpegURL",
            CineJoyNativeCatalog.REFERER, "test", "", "Fixture", 0, true, preferredVideoWidth = 160, preferredVideoHeight = 90)
        private val sourceFactory = DataSource.Factory {
            CineJoyManifestDataSource(object : DataSource {
                private var delegate: ByteArrayDataSource? = null
                override fun open(spec: DataSpec): Long {
                    val path = spec.uri.path.orEmpty().removePrefix("/")
                    synchronized(requests) { requests.add(path) }
                    if (failTrack.isNotEmpty() && path.startsWith(failTrack) && path.endsWith(".m4s") && failuresRemaining > 0) {
                        failuresRemaining--
                        throw HttpDataSource.InvalidResponseCodeException(502, "Bad Gateway", null, emptyMap(), spec, byteArrayOf())
                    }
                    val bytes = if (path == "master.m3u8") master else checkNotNull(files[path.substringAfterLast('/')]) { path }
                    return ByteArrayDataSource(bytes).also { delegate = it }.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(delegate).read(buffer, offset, length)
                override fun getUri(): Uri? = delegate?.uri
                override fun close() { delegate?.close(); delegate = null }
                override fun addTransferListener(listener: TransferListener) {}
            }, request)
        }
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication(),
            androidx.media3.exoplayer.RenderersFactory { _, _, _, _, _ -> arrayOf(video, audio) })
            .setClock(FakeClock(true))
            .setMediaSourceFactory(HlsMediaSource.Factory(sourceFactory).setLoadErrorHandlingPolicy(CineJoyLoadErrorPolicy()))
            .build()
        val recovery = CineJoyPlayerRecovery(player, Handler(Looper.getMainLooper())) { _, label -> saved.add(label) }
        init {
            recovery.reset(true)
            player.addListener(recovery)
            player.setMediaItem(MediaItem.fromUri(request.url))
            player.prepare()
            run(player).untilState(Player.STATE_READY)
            recovery.tick()
            recovery.tick()
        }
        fun select(label: String) {
            val group = player.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.getTrackFormat(0).label == label }
            assertTrue(recovery.select(group.mediaTrackGroup, 0))
        }
        fun settle() {
            run(player).ignoringNonFatalErrors().untilPendingCommandsAreFullyHandled()
            run(player).ignoringNonFatalErrors().untilState(Player.STATE_READY)
            recovery.tick(); recovery.tick()
        }
        fun selected(): String? = player.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }.getTrackFormat(0).label
        override fun close() { recovery.reset(false); player.release() }
    }

    @Test fun allFourAudioTracksRemainSelectableAcrossForwardAndBackwardSeeks() = Fixture().use { f ->
        for ((index, position) in listOf(8_000L, 16_000L, 4_000L, 12_000L).withIndex()) {
            val label = "Track ${index + 1}"
            f.select(label)
            f.player.seekTo(position)
            f.settle()
            assertEquals(label, f.selected())
            assertEquals(position, f.player.currentPosition)
            assertNull(f.player.playerError)
        }
        assertEquals("Track 4", f.saved.last())
        assertEquals(1, f.requests.count { it == "master.m3u8" })
    }

    @Test fun transient502DuringAudioSwitchRetriesFragmentWithoutReloadingMaster() = Fixture().use { f ->
        f.failTrack = "track2/"; f.failuresRemaining = 2
        f.select("Track 2")
        f.player.seekTo(10_000)
        f.settle()
        assertEquals(0, f.failuresRemaining)
        assertEquals("Track 2", f.selected())
        assertEquals("Track 2", f.saved.last())
        assertEquals(1, f.requests.count { it == "master.m3u8" })
    }

    @Test fun controllerTrackIdsAreTranslatedBackToThePlayersRealAudioGroup() = Fixture().use { f ->
        val actual = f.player.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.getTrackFormat(0).label == "Track 4" }
        val transported = androidx.media3.common.TrackGroup.fromBundle(
            androidx.media3.common.TrackGroup("session:1234",
                actual.getTrackFormat(0).buildUpon().setPrimaryTrackGroupId("session:5678").build()).toBundle())
        val choice = checkNotNull(nativeAudioChoice(f.player.currentTracks, transported, 0))
        assertEquals(actual.mediaTrackGroup, choice.mediaTrackGroup)
        assertTrue(f.recovery.select(choice.mediaTrackGroup, choice.trackIndices.first()))
        f.settle()
        assertEquals("Track 4", f.selected())
    }

    @Test fun rapidAudioChangesAndSeeksDoNotCommitAnIntermediateChoice() = Fixture().use { f ->
        f.select("Track 2")
        f.select("Track 4")
        f.player.seekTo(18_000)
        f.player.seekTo(2_000)
        f.settle()
        assertEquals("Track 4", f.selected())
        assertEquals(2_000L, f.player.currentPosition)
        assertEquals(listOf("Track 4"), f.saved)
        assertEquals(1, f.requests.count { it == "master.m3u8" })
    }

    @Test fun failedAudioRollsBackWithoutPoisoningPreferenceAndCanBeSelectedAgain() = Fixture().use { f ->
        f.failTrack = "track3/"; f.failuresRemaining = 100
        f.select("Track 3")
        f.player.seekTo(8_000)
        run(f.player).ignoringNonFatalErrors().untilPlayerError()
        f.recovery.tick()
        run(f.player).ignoringNonFatalErrors().untilPendingCommandsAreFullyHandled()
        run(f.player).ignoringNonFatalErrors().untilPlayerError()
        f.recovery.tick()
        f.settle()
        assertEquals("Track 1", f.selected())
        assertFalse(f.saved.contains("Track 3"))
        assertEquals(8_000L, f.player.currentPosition)
        f.failuresRemaining = 0
        f.select("Track 3")
        f.settle()
        assertEquals("Track 3", f.selected())
        assertEquals("Track 3", f.saved.last())
    }

    @Test fun queuedRecoveryCannotUndoANewerAudioChoiceOrSeek() = Fixture().use { f ->
        f.select("Track 2")
        f.settle()
        f.recovery.recover()
        f.select("Track 4")
        f.player.seekTo(12_000)
        f.settle()
        assertEquals("Track 4", f.selected())
        assertEquals(12_000L, f.player.currentPosition)
        assertEquals(1, f.requests.count { it == "master.m3u8" })
    }

    @Test fun seekingOutOfAFailedFragmentRestartsIdlePlayerWithTheSameAudio() = Fixture().use { f ->
        f.select("Track 2")
        f.settle()
        f.failTrack = "track2/"; f.failuresRemaining = 100
        // A source reset ensures the fixture is loaded again instead of using buffered media.
        f.player.stop(); f.player.seekTo(18_000); f.player.prepare()
        run(f.player).ignoringNonFatalErrors().untilPlayerError()
        f.recovery.tick()
        run(f.player).ignoringNonFatalErrors().untilPendingCommandsAreFullyHandled()
        run(f.player).ignoringNonFatalErrors().untilPlayerError()
        f.recovery.tick()
        assertNotNull(f.recovery.message)
        f.failuresRemaining = 0
        f.player.seekTo(4_000)
        f.settle()
        assertEquals("Track 2", f.selected())
        assertEquals(4_000L, f.player.currentPosition)
        assertNull(f.player.playerError)
        assertNull(f.recovery.message)
    }

    @Test fun selectedTracksSupplyActualAudioAndVideoSamplesAfterSeeking() = Fixture().use { f ->
        for ((index, position) in listOf(4_000L, 12_000L, 2_000L, 8_000L).withIndex()) {
            val label = "Track ${index + 1}"
            f.select(label)
            f.player.seekTo(position)
            f.settle()
            val beforeAudio = f.audio.sampleBufferReadCount
            val beforeVideo = f.video.sampleBufferReadCount
            androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play(f.player)
                .untilPositionAtLeast(position + 1_000)
            f.player.pause()
            assertTrue(f.audio.sampleBufferReadCount > beforeAudio)
            assertTrue(f.video.sampleBufferReadCount > beforeVideo)
            assertEquals(label, f.selected())
        }
    }

    @Test fun playAfterExhaustedRetriesRestartsTheSameItemAndAudio() = Fixture().use { f ->
        f.select("Track 4")
        f.settle()
        f.failTrack = "track4/"; f.failuresRemaining = 100
        f.player.stop(); f.player.seekTo(12_000); f.player.prepare()
        run(f.player).ignoringNonFatalErrors().untilPlayerError()
        f.recovery.tick()
        run(f.player).ignoringNonFatalErrors().untilPendingCommandsAreFullyHandled()
        run(f.player).ignoringNonFatalErrors().untilPlayerError()
        f.recovery.tick()
        assertNotNull(f.recovery.message)
        f.failuresRemaining = 0
        f.player.play()
        f.settle()
        assertEquals("Track 4", f.selected())
        assertTrue(f.player.playWhenReady)
        assertNull(f.player.playerError)
        assertNull(f.recovery.message)
    }
}
