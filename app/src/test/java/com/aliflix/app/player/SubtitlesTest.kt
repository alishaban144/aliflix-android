package com.aliflix.app.player

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.SubtitleLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    @Test
    fun preferredLanguageSelectsTheBestMatchingTrack() {
        val hearingImpaired = subtitleTrack("hi", "ENG", "English", hearingImpaired = true)
        val ordinaryAss = subtitleTrack("ass", "English", "English", format = "ass")
        val ordinarySrt = subtitleTrack("srt", "EN", "English", format = "srt")

        assertEquals(
            ordinarySrt,
            preferredSubtitleTrack(
                listOf(hearingImpaired, ordinaryAss, ordinarySrt),
                SubtitleLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "de",
            preferredSubtitleTrack(
                listOf(subtitleTrack("de", "GER", "German")),
                SubtitleLanguage.GERMAN,
            )?.id,
        )
        assertEquals(
            null,
            preferredSubtitleTrack(listOf(ordinarySrt), SubtitleLanguage.ARABIC),
        )
    }

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

    @Test
    fun removesMultipleAssOverrideBlocksFromTimedTextWithoutUsingPlatformRegexEscapes() {
        val cues = parseTimedTextSubtitleCues(
            """
            1
            00:00:01,000 --> 00:00:03,000
            {\an8}{\bord2}Visible <i>caption</i>
            """.trimIndent(),
        )

        assertEquals(1, cues.size)
        assertEquals("Visible caption", cues.single().text)
    }

    @Test
    fun directSubtitleUrlResolvesBase64SubdlTokensAndDirectUrls() {
        // Base64url token for "/subtitle/w9xUEm0pCZY/Fts8HZ5q9v"
        val token = "L3N1YnRpdGxlL3c5eFVFbTBwQ1pZL0Z0czhIWjVxOXY"
        assertEquals(
            "https://dl.subdl.com/subtitle/w9xUEm0pCZY/Fts8HZ5q9v",
            directSubtitleUrl(token),
        )

        // Raw path
        assertEquals(
            "https://dl.subdl.com/subtitle/test1234",
            directSubtitleUrl("/subtitle/test1234"),
        )

        // Direct HTTPS URL
        assertEquals(
            "https://dl.subdl.com/subtitle/test.srt",
            directSubtitleUrl("https://dl.subdl.com/subtitle/test.srt"),
        )

        // OpenSubtitles / Stremio Base64 token
        val stremioUrl = "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1618"
        val stremioToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(stremioUrl.toByteArray())
        assertEquals(
            stremioUrl,
            directSubtitleUrl(stremioToken),
        )
    }

    @Test
    fun activeCueAtPositionMatchesCurrentTimestampAndDelay() {
        val cues = listOf(
            SubtitleCue(1.0, 3.0, "First cue"),
            SubtitleCue(4.0, 6.5, "Second cue"),
        )
        val match1 = cues.filter { 2.0 in (it.startSeconds + 0.0)..(it.endSeconds + 0.0) }
        assertEquals("First cue", match1.single().text)

        val match2 = cues.filter { 3.5 in (it.startSeconds + 0.0)..(it.endSeconds + 0.0) }
        assertTrue(match2.isEmpty())

        val match3 = cues.filter { 3.5 in (it.startSeconds + 1.5)..(it.endSeconds + 1.5) }
        assertEquals("First cue", match3.single().text)
    }

    private fun subtitleTrack(
        id: String,
        code: String,
        name: String,
        hearingImpaired: Boolean = false,
        format: String = "srt",
    ) = SubtitleTrack(
        id = id,
        languageCode = code,
        languageName = name,
        releaseName = id,
        fileName = "$id.$format",
        hearingImpaired = hearingImpaired,
        format = format,
        fps = null,
        downloadToken = "abcdefgh",
    )
}
