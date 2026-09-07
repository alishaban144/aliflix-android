package com.aliflix.app.player

import android.net.Uri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class CastSubtitleTracksTest {
    @Test fun receiverTracksPreserveSelectedLanguageAndMedia3RoundTripIdentity() {
        val converter = DefaultMediaItemConverter()
        val item = MediaItem.Builder().setMediaId("tv:42:s2:e3")
            .setUri("http://192.168.1.2:8080/token/video")
            .setMimeType("video/mp4")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Episode three").build()).build()
        val original = converter.toMediaQueueItem(item)
        val request = NativePlaybackRequest("https://cdn.example/video.mp4", "video/mp4", "https://provider.example", "UA", "", "Episode three", 980125, false,
            "WEBVTT\n\n", subtitleLanguage = "de", subtitleLabel = "German")
        val result = withCastSubtitles(original, request, "http://192.168.1.2:8080/token/subtitles.vtt")
        assertEquals(original.media!!.customData.toString(), result.media!!.customData.toString())
        val track = result.media!!.mediaTracks!!.single()
        assertEquals("de", track.language)
        assertEquals("text/vtt", track.contentType)
        assertEquals("http://192.168.1.2:8080/token/subtitles.vtt", track.contentId)
        assertArrayEquals(longArrayOf(track.id), result.activeTrackIds)
        assertEquals(item.mediaId, converter.toMediaItem(result).mediaId)
    }
}
