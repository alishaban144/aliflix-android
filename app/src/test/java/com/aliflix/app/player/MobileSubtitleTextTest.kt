package com.aliflix.app.player

import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class MobileSubtitleTextTest {
    private val arabic = "ما الذي تفعله هنا؟\nلقد بدأت رحلة لا تنسى"
    private fun file(text: String) = "1\n00:00:01,000 --> 00:00:04,000\n$text\n"

    @Test fun arabicWindows1256IsReadableEvenWhenMislabeledEnglish() {
        val bytes = file(arabic).toByteArray(Charset.forName("windows-1256"))
        assertEquals(file(arabic), decodeMobileSubtitleText(bytes, "AR"))
        val decoded = decodeMobileSubtitleText(bytes, "EN")
        assertEquals(file(arabic), decoded)
        assertFalse(subtitleLanguageIsPlausible(parseTimedTextSubtitleCues(decoded), "EN"))
        assertTrue(subtitleLanguageIsPlausible(parseTimedTextSubtitleCues(decoded), "AR"))
    }

    @Test fun repairsArabicWrappedInUtf8AfterWrongWesternDecode() {
        val corrupt = String(file(arabic).toByteArray(Charset.forName("windows-1256")), Charset.forName("windows-1252"))
        assertEquals(file(arabic), decodeMobileSubtitleText(corrupt.toByteArray(), "EN"))
    }

    @Test fun preservesUnicodeAndWesternAccentsAndRepairsDoubleEncoding() {
        val text = file("Café. Voilà! It's déjà vu.")
        assertEquals(text, decodeMobileSubtitleText(text.toByteArray(), "EN"))
        assertEquals(text, decodeMobileSubtitleText(text.toByteArray(Charset.forName("windows-1252")), "EN"))
        val corrupt = String(text.toByteArray(), Charset.forName("windows-1252"))
        assertEquals(text, decodeMobileSubtitleText(corrupt.toByteArray(), "EN"))
        assertEquals(file(arabic), decodeMobileSubtitleText(file(arabic).toByteArray(Charsets.UTF_16), "AR"))
        assertEquals(file(arabic), decodeMobileSubtitleText(file(arabic).toByteArray(), "AR"))
    }

    @Test fun selectsMatchingEpisodeAndReleaseBeforeGenericFiles() {
        fun track(id: String, release: String, language: String = "ENG") = SubtitleTrack(id, language, "English",
            release, "$release.srt", false, "srt", null, "abcdefgh")
        val wrong = track("wrong", "Show.S02E03.WEB-DL")
        val generic = track("generic", "Show.S02E04")
        val matching = track("matching", "Show.S02E04.WEB-DL.GROUP")
        val foreign = track("foreign", "Show.S02E04.WEB-DL.GROUP", "AR")
        assertEquals(listOf(matching, generic), mobileSubtitleCandidates(listOf(wrong, generic, foreign, matching),
            "en", 2, 4, "https://example/video/Show.S02E04.WEB-DL.GROUP.mp4"))
    }

    @Test fun rejectsDamagedTextInsteadOfDisplayingReplacementGlyphs() {
        assertThrows(SubtitleException::class.java) { decodeMobileSubtitleText(file("Broken \uFFFD subtitle").toByteArray(), "EN") }
        assertThrows(SubtitleException::class.java) { decodeMobileSubtitleText(file("ãÇ ÇáĐí ÊYÚáå åäÇ¿ - áÞÏ BÇÏÊ ÑÍÁÉ ÁÇ Êõñóì").toByteArray(), "EN") }
        assertFalse(subtitleLanguageIsPlausible(emptyList(), "EN"))
    }
}
