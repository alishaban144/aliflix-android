package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class AdaptiveSubtitleSynchronizerTest {
    @Test fun publishedCaptionHangoverDoesNotDiscardAnOtherwiseDistinctiveExchange() {
        val fixture = org.json.JSONObject(javaClass.getResource("/audio-sync/published-caption-hangover.json")!!.readText())
        val rows = fixture.getJSONArray("cues")
        val originals = (0 until rows.length()).map { rows.getJSONArray(it).let { c ->
            SubtitleCue(c.getDouble(0), c.getDouble(1), c.getString(2))
        } }
        val observed = fixture.getJSONArray("windows").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).let { w ->
            SpeechWindow(w.getDouble("start"), w.getString("bits").map { if (it == '1') 1.0 else 0.0 }.toDoubleArray())
        } } }
        val result = AdaptiveSubtitleSynchronizer.matchQuick(originals, observed)
        assertNotNull(result.toString(), result.correction)
        assertEquals(4.5, result.correction!!.offset, .6)
        assertTrue(AdaptiveSubtitleSynchronizer.verifyReferenceQuick(result.correction.apply(originals), observed))
        val contradicted = observed + windows(originals, 18.0, starts = listOf(150.0, 180.0))
        assertFalse(AdaptiveSubtitleSynchronizer.verifyReferenceQuick(result.correction.apply(originals), contradicted))
        assertFalse(AdaptiveSubtitleSynchronizer.dialogue(SubtitleCue(1.0, 3.0, "HOWLING WIND")))
        assertTrue(AdaptiveSubtitleSynchronizer.dialogue(SubtitleCue(1.0, 3.0, "DON'T MOVE!")))
    }
    @Test fun localWordProposalMustStillRespectEarlierObservedClock() {
        val originals = cues()
        val correction = AudioSubtitleCorrection(7.25, 1.0, .9)
        val previous = windows(originals, 7.25, starts = listOf(90.0, 150.0, 210.0))
        assertTrue(AdaptiveSubtitleSynchronizer.verifyEarlierClock(originals, correction, previous, 740.0))
        assertFalse(AdaptiveSubtitleSynchronizer.verifyEarlierClock(originals, correction,
            previous + windows(originals, 18.0, starts = listOf(300.0)), 740.0))
        assertTrue(AdaptiveSubtitleSynchronizer.verifyEarlierClock(originals, correction,
            previous + windows(originals, 18.0, starts = listOf(710.0)), 740.0))
    }
    @Test fun currentWindowDoesNotExtrapolateAContradictoryDriftingClock() {
        val originals = cues()
        val current = SpeechWindow(700.0, DoubleArray(30 * SPEECH_HZ) { i ->
            val time = (700 + i.toDouble() / SPEECH_HZ - 7.0) / (25.0 / 24)
            if (originals.any { time in it.startSeconds..it.endSeconds }) 1.0 else 0.0
        })
        assertNull(AdaptiveSubtitleSynchronizer.matchCurrent(originals, current).correction)
    }
    @Test fun currentContiguousExchangeFitsEarlierPhrasesAndVerifiesLaterPhrases() {
        val originals = cues()
        for (offset in listOf(-47.0, 7.25)) {
            val current = SpeechWindow(700.0, DoubleArray(30 * SPEECH_HZ) { i ->
                val time = 700 + i.toDouble() / SPEECH_HZ - offset
                if (originals.any { time in it.startSeconds..it.endSeconds }) 1.0 else 0.0
            })
            val result = AdaptiveSubtitleSynchronizer.matchCurrent(originals, current)
            assertNotNull(result.toString(), result.correction)
            assertEquals(offset, result.correction!!.offset, .6)
            assertEquals(1.0, result.correction.rate, .0001)
            assertNull(AdaptiveSubtitleSynchronizer.matchCurrent(cues(455), current).correction)
        }
    }
    @Test fun currentDialogueRejectsSilenceAndRepeatedTimingPatterns() {
        val periodic = List(3000) { SubtitleCue(it * 3.0, it * 3.0 + 1, "Repeated") }
        val repeated = SpeechWindow(700.0, DoubleArray(30 * SPEECH_HZ) { if (it % 150 < 50) 1.0 else 0.0 })
        assertNull(AdaptiveSubtitleSynchronizer.matchCurrent(periodic, repeated).correction)
        assertNull(AdaptiveSubtitleSynchronizer.matchCurrent(cues(), SpeechWindow(700.0, DoubleArray(1500))).correction)
    }
    @Test fun shortBufferedScenesVerifyOffsetWithoutWaitingForFortySecondBlocks() {
        val originals = cues()
        assertNull(AdaptiveSubtitleSynchronizer.matchQuick(originals, windows(cues(455), 2.0)).correction)
        val observed = windows(originals, -47.0, starts = listOf(700.0, 721.0))
        val result = AdaptiveSubtitleSynchronizer.matchQuick(originals, observed)
        assertNotNull(result.toString(), result.correction)
        assertEquals(-47.0, result.correction!!.offset, .12)
        assertEquals(1.0, result.correction.rate, .0001)
    }
    @Test fun shortReferenceChecksVerifyTheSuppliedClockWithoutSearchingForAnotherOffset() {
        val originals = cues()
        val observed = windows(originals, 0.0, starts = listOf(700.0, 721.0))
        assertTrue(AdaptiveSubtitleSynchronizer.verifyReferenceQuick(originals, observed))
        assertFalse(AdaptiveSubtitleSynchronizer.verifyReferenceQuick(originals, windows(originals, 3.0, starts = listOf(700.0, 721.0))))
        assertFalse(AdaptiveSubtitleSynchronizer.verifyReferenceQuick(originals, emptyList()))
        assertFalse(AdaptiveSubtitleSynchronizer.verifyReferenceQuick(cues(818), observed))
    }
    @Test fun shortPeriodicOrContradictingEvidenceCannotBeAcceptedToMeetADeadline() {
        val periodic = List(3000) { SubtitleCue(it * 3.0, it * 3.0 + 1.0, "Repeated") }
        assertNull(AdaptiveSubtitleSynchronizer.matchQuick(periodic, windows(periodic, 2.0)).correction)
        val originals = cues()
        assertNull(AdaptiveSubtitleSynchronizer.matchQuick(originals,
            windows(originals, 4.0, starts = listOf(700.0, 740.0, 780.0), corrupt = 1)).correction)
    }
    private fun cues(seed: Long = 7621): List<SubtitleCue> {
        val rng = Random(seed); var time = 0.0
        return List(2200) {
            time += .35 + rng.nextDouble() * 2.5
            val start = time; time += .4 + rng.nextDouble() * 2.1
            SubtitleCue(start, time, "حوار بالعربية.")
        }
    }
    private fun windows(cues: List<SubtitleCue>, offset: Double, rate: Double = 1.0,
                        starts: List<Double> = listOf(700.0, 900.0, 1100.0, 1300.0, 1500.0, 1700.0),
                        corrupt: Int = -1): List<SpeechWindow> = starts.mapIndexed { index, start ->
        SpeechWindow(start, DoubleArray(20 * SPEECH_HZ) { i ->
            val time = (start + i.toDouble() / SPEECH_HZ - offset - if (index == corrupt) 17 else 0) / rate
            if (cues.any { time >= it.startSeconds && time < it.endSeconds }) 1.0 else 0.0
        })
    }
    @Test fun separatedScenesVerifyLargeOffsetsAndCrossLanguageWithoutManualDelay() {
        val cues = cues()
        for (offset in listOf(-285.0, 0.0, 310.0)) {
            val result = AdaptiveSubtitleSynchronizer.match(cues, windows(cues, offset))
            assertNotNull("$offset: $result", result.correction)
            assertEquals(offset, result.correction!!.offset, .12)
            assertEquals(1.0, result.correction.rate, 1e-8)
            assertTrue(result.margin >= .04)
            assertEquals(cues.first().startSeconds, cues.first().startSeconds, 0.0)
        }
    }
    @Test fun framerateMismatchAndMeasuredProgressiveDriftAreEstablishedOnIndependentScenes() {
        val cues = cues()
        for (rate in listOf(25.0 / 24.0, 24.0 / 25.0, 1.0123)) {
            val result = AdaptiveSubtitleSynchronizer.match(cues, windows(cues, 8.0, rate))
            assertNotNull("$rate: $result", result.correction)
            assertEquals(rate, result.correction!!.rate, .0004)
            assertEquals(8.0, result.correction.offset, .65)
            assertTrue(abs(result.correction.apply(cues).last().endSeconds - (cues.last().endSeconds * rate + 8)) < 2.5)
        }
    }
    @Test fun withheldWrongSceneCannotBeSilentlyOutvotedByTrainingScenes() {
        val cues = cues()
        val result = AdaptiveSubtitleSynchronizer.match(cues, windows(cues, 4.0, corrupt = 1))
        assertNull(result.correction)
    }
    @Test fun fittingBudgetMustNotDiscardKnownContradictingScenesInLongFilms() {
        val originals = cues()
        val starts = (0 until 40).map { 700.0 + it * 100 }
        for (rate in listOf(1.0, 25.0 / 24.0, 1.0123)) {
            val verified = AdaptiveSubtitleSynchronizer.match(originals, windows(originals, 4.0, rate, starts = starts))
            assertNotNull("All known scenes support this clock: $rate $verified", verified.correction)
            assertEquals(rate, verified.correction!!.rate, .0004)
        }
        val result = AdaptiveSubtitleSynchronizer.match(originals, windows(originals, 4.0, starts = starts, corrupt = 7))
        assertNull("A known scene omitted from the fitting subset still contradicts this correction: $result", result.correction)
    }
    @Test fun shortProgressiveEvidenceCannotBeMislabelledAsAWholeFilmConstantOffset() {
        val originals = cues()
        val result = AdaptiveSubtitleSynchronizer.match(originals,
            windows(originals, 8.0, 1.0123, starts = (0 until 8).map { 700.0 + it * 20 }))
        assertTrue(result.toString(), result.correction == null || kotlin.math.abs(result.correction.rate - 1.0123) < .0004)
    }
    @Test fun threeEarlyScenesWithSmallConsistentDriftCannotVerifyAWholeFilmOffset() {
        val originals = cues()
        for (rate in listOf(1.003, 1.006, 1.0123)) {
            val result = AdaptiveSubtitleSynchronizer.match(originals,
                windows(originals, 8.0, rate, starts = listOf(700.0, 740.0, 780.0)))
            assertNull("Collect more evidence rather than mislabeling $rate drift: $result", result.correction)
        }
    }
    @Test fun indistinguishableFrameratesCannotBeExtrapolatedAcrossUnseenFilm() {
        val originals = cues()
        val starts = listOf(700.0, 800.0, 900.0, 1000.0, 1100.0)
        val a = windows(originals, 8.0, 25.0 / 24.0, starts)
        val b = windows(originals, 8.0, 25.0 / 23.976, starts)
        val ambiguous = a.zip(b).map { (left, right) ->
            SpeechWindow(left.start, DoubleArray(left.speech.size) { (left.speech[it] + right.speech[it]) / 2 })
        }
        val result = AdaptiveSubtitleSynchronizer.match(originals, ambiguous)
        assertNull("Locally equivalent clocks diverge by seconds over this whole film: $result", result.correction)
    }
    @Test fun silencePeriodicSpeechWrongFilmAndSingleSceneNeverApply() {
        val cues = cues()
        assertNull(AdaptiveSubtitleSynchronizer.match(cues, listOf(windows(cues, 2.0).first())).correction)
        assertNull(AdaptiveSubtitleSynchronizer.match(cues, List(6) { SpeechWindow(it * 20.0, DoubleArray(1000)) }).correction)
        val periodic = List(3000) { SubtitleCue(it * 3.0, it * 3.0 + 1.0, "Repeated") }
        assertNull(AdaptiveSubtitleSynchronizer.match(periodic, windows(periodic, 2.0)).correction)
        assertNull(AdaptiveSubtitleSynchronizer.match(cues, windows(cues(455), 2.0)).correction)
    }
    @Test fun providerReferenceMustBeIndependentlyOnTimeInSelectedAudio() {
        val cues = cues()
        assertTrue(AdaptiveSubtitleSynchronizer.verifyReference(cues, windows(cues, 0.0)))
        assertFalse(AdaptiveSubtitleSynchronizer.verifyReference(cues, windows(cues, 6.0)))
        assertFalse(AdaptiveSubtitleSynchronizer.verifyReference(cues, emptyList()))
    }
    @Test fun cancellationIsCheckedInsideFftAndNeverReturnsPartialModel() {
        var checks = 0
        try {
            AdaptiveSubtitleSynchronizer.match(cues(), windows(cues(), 3.0)) {
                if (++checks > 12) throw java.util.concurrent.CancellationException()
            }
            fail("Expected cooperative cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
    }
    @Test fun piecewiseCorrectionsRemainImmutableAndManualDelayIsAddedExactlyOnce() {
        val original = listOf(SubtitleCue(10.0, 12.0, "One"), SubtitleCue(30.0, 32.0, "Two"))
        val correction = AudioSubtitleCorrection(3.0, 1.0, .9, listOf(TimingRegion(0, 3.0), TimingRegion(1, -4.0)), "piecewise")
        assertEquals(listOf(14.5, 27.5), correction.apply(original, 1.5).map { it.startSeconds })
        assertEquals(listOf(10.0, 30.0), original.map { it.startSeconds })
        assertEquals(correction.apply(original), correction.apply(original))
    }
    @Test fun editedSceneBoundariesAndFramerateMismatchRequireHeldOutScenesInEachRegion() {
        val originals = cues()
        val boundary = originals.indexOfFirst { it.startSeconds >= 1800 }
        for (rate in listOf(1.0, 25.0 / 24.0)) {
            val reference = originals.mapIndexed { index, cue ->
                val shift = if (index < boundary) 4.0 else 16.0
                cue.copy(startSeconds = cue.startSeconds * rate + shift, endSeconds = cue.endSeconds * rate + shift, text = "Reference dialogue")
            }
            val starts = (0 until 12).map { 700.0 + it * 200 }
            val observed = windows(reference, 0.0, starts = starts)
            val result = AdaptiveSubtitleSynchronizer.match(originals, observed, reference)
            assertNotNull("$rate: $result", result.correction)
            assertEquals("piecewise", result.correction!!.model)
            assertEquals(rate, result.correction.rate, .0003)
            assertEquals(listOf(0, boundary), result.correction.regions.map { it.firstCue })
            assertEquals(4.0, result.correction.regions[0].offset, .15)
            assertEquals(16.0, result.correction.regions[1].offset, .15)
            // Unobserved streaming edit boundaries cannot be guessed.
            assertNull(AdaptiveSubtitleSynchronizer.match(originals, observed).correction)
        }
    }
    @Test fun nestedCredentialsAndEscapedAssetPathsRemainStable() {
        assertEquals("https://cdn.test/a%20b/master.m3u8?quality=1080", stableSyncUrl("https://cdn.test/a%20b/master.m3u8?token=x&quality=1080"))
        assertEquals(stableSyncRules("""{"query":{"params":{"token":"old","quality":"1080"}},"headers":{"Cookie":"a"}}"""),
            stableSyncRules("""{"headers":{"Cookie":"b"},"query":{"params":{"quality":"1080","token":"new"}}}"""))
        assertNull(AdaptiveSubtitleSynchronizer.completeAudioReference(listOf(SpeechWindow(20.0, DoubleArray(1000)))))
        assertNull(AdaptiveSubtitleSynchronizer.completeAudioReference(listOf(SpeechWindow(0.0, DoubleArray(1000)), SpeechWindow(40.0, DoubleArray(1000)))))
    }
    @Test fun signedUrlsAndCredentialsDoNotInvalidateStableAssetCorrections() {
        val request = NativePlaybackRequest("https://cdn.test/title/master.m3u8?quality=1080&token=old&expires=1", "application/x-mpegURL",
            "https://source.test/movie/1", "ua", "cookie=old", "title", 0, true)
        val renewed = request.copy(url = "https://cdn.test/title/master.m3u8?expires=9&token=new&quality=1080", cookie = "cookie=new")
        assertEquals(subtitleCorrectionKey(request, "movie:1", "original", "audio-en"), subtitleCorrectionKey(renewed, "movie:1", "original", "audio-en"))
        assertNotEquals(subtitleCorrectionKey(request, "movie:1", "original", "audio-en"), subtitleCorrectionKey(request.copy(url = request.url.replace("1080", "720")), "movie:1", "original", "audio-en"))
    }
}
