package com.aliflix.app.player

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    @Test
    fun subtitleIdentityUsesExactMovieOrEpisodeAndNeverServer() {
        val movie = PlaybackSelection(Media(27205, MediaType.MOVIE, "Inception"))
        val episode = PlaybackSelection(
            media = Media(1405, MediaType.TV, "Dexter"),
            seasonNumber = 1,
            episodeNumber = 8,
        )

        assertEquals("movie:27205", subtitleContentKey(movie))
        assertEquals("tv:1405:s1:e8", subtitleContentKey(episode))
        assertEquals(
            subtitleContentKey(episode),
            subtitleContentKey(episode.copy(source = com.aliflix.app.model.PlaybackSource.moviepire("https://custom.example"))),
        )
    }

    @Test
    fun parsesSrtAndWebVttTimingPrecisely() {
        val cues = parseTimedTextSubtitleCues(
            """
            WEBVTT

            1
            00:00:01,250 --> 00:00:03,500
            Hello <i>there</i>

            cue-two
            00:01:02.100 --> 00:01:04.250 align:center
            Second line
            continues
            """.trimIndent(),
        )

        assertEquals(2, cues.size)
        assertEquals(1.25, cues[0].startSeconds, 0.0001)
        assertEquals(3.5, cues[0].endSeconds, 0.0001)
        assertEquals("Hello there", cues[0].text)
        assertEquals(62.1, cues[1].startSeconds, 0.0001)
        assertEquals("Second line\ncontinues", cues[1].text)
    }

    @Test
    fun parsesAssDialogueAndRemovesFormattingCommands() {
        val cues = parseAssSubtitleCues(
            """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.10,0:00:04.35,Default,,0,0,0,,{\an8}Top\Nline
            """.trimIndent(),
        )

        assertEquals(1, cues.size)
        assertEquals(2.1, cues.single().startSeconds, 0.0001)
        assertEquals(4.35, cues.single().endSeconds, 0.0001)
        assertEquals("Top\nline", cues.single().text)
        assertTrue(cues.single().endSeconds > cues.single().startSeconds)
    }
}
