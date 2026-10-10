package com.aliflix.app.player

import com.aliflix.app.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class AutomaticSubtitleLoaderTest {
    private val movie = PlaybackSelection(Media(100, MediaType.MOVIE, "The Shining"),
        source = PlaybackSource(MobilePlaybackProvider.FLIXER, "https://example.com"))
    private val episode = movie.copy(media = Media(200, MediaType.TV, "Dark"), seasonNumber = 2, episodeNumber = 4)
    private val english = listOf(SubtitleCue(1.0, 4.0, "What are you doing here? We need to leave now."))
    private val arabic = listOf(SubtitleCue(1.0, 4.0, "ما الذي تفعله هنا؟ لقد بدأت رحلة لا تنسى"))
    private fun track(id: String, language: String = "eng", name: String = "Movie") =
        SubtitleTrack(id, language, language, name, "$name.srt", false, "srt", null, "download-$id")

    @Test fun startupUsesMatchingSourceBeforeSearching() = runTest {
        var searched = false
        val source = track("source")
        val result = AutomaticSubtitleLoader({ listOf(source) }, { searched = true; emptyList() }, { english })
            .load(movie, "EN", 0, 24f, { true })
        assertEquals(source.id, result?.track?.id)
        assertFalse(searched)
    }
    @Test fun regionalAndThreeLetterLanguagesAreCanonical() {
        listOf("eng", "en-US", "en_US", "English").forEach { assertEquals("EN", canonicalSubtitleLanguageCode(it)) }
        listOf("ara", "ar-SA", "ar_EG", "Arabic").forEach { assertEquals("AR", canonicalSubtitleLanguageCode(it)) }
        assertEquals("DE", canonicalSubtitleLanguageCode("deu"))
        assertEquals("FR", canonicalSubtitleLanguageCode("fre"))
    }
    @Test fun mismatchedSourceFallsBackToSubdlInSelectedLanguage() = runTest {
        val requested = mutableListOf<String>()
        val result = AutomaticSubtitleLoader({ listOf(track("foreign", "EN")) },
            { listOf(track("subdl", "ara"), track("wrong", "EN")) },
            { requested += it.id; arabic }).load(movie, "ar_EG", 0, 24f, { true })
        assertEquals(listOf("subdl"), requested)
        assertEquals("AR", result?.track?.languageCode)
    }
    @Test fun sourceLookupFailureStillSearchesSubdl() = runTest {
        val result = AutomaticSubtitleLoader({ throw java.io.IOException() }, { listOf(track("fallback")) }, { english })
            .load(movie, "EN", 0, 24f, { true })
        assertEquals("fallback", result?.track?.id)
    }
    @Test fun downloadFailureDamagedAndWrongLanguageFilesTryAlternatives() = runTest {
        val requests = mutableListOf<String>()
        val result = AutomaticSubtitleLoader({ listOf(track("broken"), track("wrong"), track("good")) }, { emptyList() },
            { requests += it.id; when (it.id) {
                "broken" -> throw java.io.IOException()
                "wrong" -> arabic
                else -> english
            } }).load(movie, "EN", 0, 24f, { true })
        assertEquals(listOf("broken", "wrong", "good"), requests)
        assertEquals("good", result?.track?.id)
    }
    @Test fun unusableSourceCandidatesFallThroughToSearch() = runTest {
        val result = AutomaticSubtitleLoader({ listOf(track("empty")) }, { listOf(track("valid")) },
            { if (it.id == "empty") emptyList() else english }).load(movie, "EN", 0, 24f, { true })
        assertEquals("valid", result?.track?.id)
    }
    @Test fun manualOffOrDisabledAutoNeverStartsLookup() = runTest {
        var calls = 0
        val result = AutomaticSubtitleLoader({ calls++; emptyList() }, { calls++; emptyList() }, { calls++; english })
            .load(movie, "EN", 0, 24f, { false })
        assertNull(result)
        assertEquals(0, calls)
    }
    @Test fun manualSelectionDuringDownloadRevokesAutomaticResult() = runTest {
        var current = true
        val result = AutomaticSubtitleLoader({ listOf(track("auto")) }, { emptyList() }, {
            delay(10); current = false; english
        }).load(movie, "EN", 0, 24f, { current })
        assertNull(result)
    }
    @Test fun episodeOrServerChangeAfterLookupRejectsOldResults() = runTest {
        var current = true
        var downloads = 0
        val result = AutomaticSubtitleLoader({ current = false; listOf(track("old")) }, { emptyList() },
            { downloads++; english }).load(episode, "EN", 0, 24f, { current })
        assertNull(result)
        assertEquals(0, downloads)
    }
    @Test fun episodeIdentityRejectsWrongSeasonAndBothFilenameConventions() = runTest {
        val result = AutomaticSubtitleLoader({ listOf(track("wrong", name = "Dark.S02E03"),
            track("wrong-season", name = "Dark.1x04"), track("valid", name = "Dark.2x04")) },
            { emptyList() }, { english }).load(episode, "EN", 0, 24f, { true })
        assertEquals("valid", result?.track?.id)
    }
    @Test fun completeMovieIsChosenOverSplitRelease() = runTest {
        val split = listOf(SubtitleCue(10.0, 100.0, "We have to leave."))
        val complete = listOf(SubtitleCue(10.0, 11.0, "Where are you?"), SubtitleCue(5980.0, 5990.0, "We have to leave."))
        val result = AutomaticSubtitleLoader({ listOf(track("part"), track("full")) }, { emptyList() },
            { if (it.id == "part") split else complete }).load(movie, "EN", 6_000_000, 24f, { true })
        assertEquals("full", result?.track?.id)
    }
    @Test fun duplicatesAreNeverDownloadedTwiceAcrossFallback() = runTest {
        var downloads = 0
        val result = AutomaticSubtitleLoader({ listOf(track("same")) }, { listOf(track("same")) },
            { downloads++; emptyList() }).load(movie, "EN", 0, 24f, { true })
        assertNull(result)
        assertEquals(1, downloads)
    }
    @Test fun manualFileBelongsToEpisodeWhileOffFollowsTheSeries() {
        val next = episode.copy(episodeNumber = 5)
        assertEquals(true, scopedManualSubtitleChoice(episode.media.key, subtitleContentKey(episode), true, episode))
        assertNull(scopedManualSubtitleChoice(episode.media.key, subtitleContentKey(episode), true, next))
        assertEquals(false, scopedManualSubtitleChoice(episode.media.key, subtitleContentKey(episode), false, next))
        assertNull(scopedManualSubtitleChoice(movie.media.key, subtitleContentKey(movie), false, episode))
    }
    @Test fun languageChangeDuringDownloadDiscardsPreviousPreference() = runTest {
        var language = "EN"
        val result = AutomaticSubtitleLoader({ listOf(track("old")) }, { emptyList() }, {
            language = "AR"; english
        }).load(movie, "EN", 0, 24f, { language == "EN" })
        assertNull(result)
    }
    @Test fun conflictingLanguageMetadataAndForeignLatinTextAreRejected() = runTest {
        val mislabeled = track("conflict").copy(languageName = "Arabic")
        var downloads = 0
        val result = AutomaticSubtitleLoader({ listOf(mislabeled) }, { emptyList() }, { downloads++; english })
            .load(movie, "EN", 0, 24f, { true })
        assertNull(result)
        assertEquals(0, downloads)
        val spanish = listOf(SubtitleCue(1.0, 4.0, "Que no te puedes ir porque nosotros tenemos que volver para que ellos puedan llegar."))
        assertFalse(subtitleLanguageIsPlausible(spanish, "EN"))
    }

    @Test fun serviceActivationRejectsLateAutomaticResultsAndUnknownLanguages() {
        assertTrue(automaticSubtitleMayActivate(true, false, "eng", "en_US", false))
        assertFalse(automaticSubtitleMayActivate(false, false, "EN", "EN", false))
        assertFalse(automaticSubtitleMayActivate(true, true, "EN", "EN", false))
        assertFalse(automaticSubtitleMayActivate(true, false, "AR", "EN", false))
        assertFalse(automaticSubtitleMayActivate(true, false, "EN", "EN", true))
        assertFalse(automaticSubtitleMayActivate(true, false, "und", "und", false))
    }

    @Test fun repeatedNamesAndSharedLatinWordsDoNotRejectValidCaptions() {
        val portuguese = listOf(SubtitleCue(1.0, 4.0, "Que que que que que? Você não quer ficar aqui? Estou pronto."))
        val names = listOf(SubtitleCue(1.0, 4.0, "Ben! Ben! Ben! Ben!"))
        val french = listOf(SubtitleCue(1.0, 4.0, "Que que que que que que? Vous et nous avec cette famille pour vous."))
        assertTrue(subtitleLanguageIsPlausible(portuguese, "PT"))
        assertTrue(subtitleLanguageIsPlausible(names, "EN"))
        assertTrue(subtitleLanguageIsPlausible(french, "FR"))
    }
    @Test fun distinctiveLatinGrammarRejectsMislabeledForeignCaptions() {
        val french = listOf(SubtitleCue(1.0, 4.0, "Vous et nous avec cette famille pour vous."))
        assertFalse(subtitleLanguageIsPlausible(french, "EN"))
        assertFalse(subtitleLanguageIsPlausible(english, "PT"))
    }

    @Test fun stalledSourceFilesCannotExhaustTheSubdlFallbackBudget() = runTest {
        var searches = 0
        val result = kotlinx.coroutines.withTimeoutOrNull(28_000) {
            AutomaticSubtitleLoader({ delay(5_000); (1..6).map { track("slow-$it") } },
                { searches++; delay(2_000); listOf(track("subdl")) },
                { if (it.id.startsWith("slow")) { delay(15_000); emptyList() } else english })
                .load(movie, "EN", 0, 24f, { true })
        }
        assertEquals(1, searches)
        assertEquals("subdl", result?.track?.id)
    }

}
