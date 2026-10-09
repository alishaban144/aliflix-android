package com.aliflix.app.player

import kotlin.math.min

internal data class SubtitleSyncReference(val cues: List<SubtitleCue>, val targetToReference: AudioSubtitleCorrection, val targetCues: List<SubtitleCue> = emptyList(),
                                          val exactTextClock: Boolean = false, val verifiedEditionClock: Boolean = false)

/** Translation changes words, not the independently verified playback clock.
 * First align the complete chosen translation to a reference timeline, then
 * verify that reference against current spoken phrases. Neither affiliation nor
 * similar filenames can certify a reference, and the chosen text never changes.
 */
internal object TranslatedSubtitleSync {
    fun prepare(target: List<SubtitleCue>, reference: List<SubtitleCue>, cancelled: () -> Unit = {}): SubtitleSyncReference? {
        matchCaptionTimelines(target, reference, cancelled)?.let { return SubtitleSyncReference(reference, it, target, exactTextClock = true, verifiedEditionClock = true) }
        matchCaptionGaps(target, reference, cancelled)?.let { return SubtitleSyncReference(reference, it, target) }
        val windows = AdaptiveSubtitleSynchronizer.referenceWindows(reference)
        val clock = AdaptiveSubtitleSynchronizer.matchQuick(target, windows, reference, cancelled).correction ?: return null
        return SubtitleSyncReference(reference, clock, target)
    }

    fun match(reference: SubtitleSyncReference, heard: List<HeardWord>, history: List<HeardWord>,
              audio: List<SpeechWindow>, position: Double, recentAfter: Double = position - CURRENT_DIALOGUE_SECONDS,
              cancelled: () -> Unit = {}, independentClips: List<List<HeardWord>> = emptyList()): AudioSubtitleCorrection? {
        var boundaries = emptyList<VerifiedDialogueBoundary>()
        var verifiedWords = heard
        val differentEditionRate = kotlin.math.abs(reference.targetToReference.rate - 1.0) > .0003
        // Offset-only translation compares overlapping responses to this scene;
        // a different edition rate needs the widely separated earlier clips.
        val comparisonClips = if (differentEditionRate || heard.isEmpty()) independentClips else independentClips.filter {
            it.isNotEmpty() && it.first().start <= heard.last().end + 2 && it.last().end >= heard.first().start - 2
        }
        if (!reference.exactTextClock && independentClips.isNotEmpty() && comparisonClips.size < 2) return null
        val verified = (if (independentClips.isNotEmpty()) DialogueWordAlignment.matchClips(reference.cues, comparisonClips, audio,
            recentAfter = recentAfter, diagnostic = { android.util.Log.i("AliflixAudioSync", "reference_clips:$it") },
            cancelled = cancelled, boundaries = { boundaries = it }, requireWideClock = differentEditionRate,
            candidateRates = if (reference.verifiedEditionClock) listOf(1.0, reference.targetToReference.rate, 1.0 / reference.targetToReference.rate) else null) else null)
            ?: (if (differentEditionRate || independentClips.isNotEmpty()) null else DialogueWordAlignment.match(reference.cues, heard, audio, recentAfter = recentAfter,
                diagnostic = { android.util.Log.i("AliflixAudioSync", "reference_current:$it") }, cancelled = cancelled, boundaries = { boundaries = it })
            ?: (if (history.any { it.end >= recentAfter }) { verifiedWords = history; DialogueWordAlignment.match(reference.cues, history, audio,
                recentAfter = recentAfter,
                diagnostic = { android.util.Log.i("AliflixAudioSync", "reference_history:$it") }, cancelled = cancelled, boundaries = { boundaries = it }) } else null))
            ?: return null
        if (!reference.exactTextClock && independentClips.isNotEmpty() &&
            boundaries.filter { it.distinctiveWords >= 4 && it.independentMeasurements >= 2 }.map { it.cue }.distinct().size < 2) return null
        if (!AdaptiveSubtitleSynchronizer.verifyEarlierClock(reference.cues, verified, audio, position, cancelled,
            diagnostic = { android.util.Log.i("AliflixAudioSync", "reference_verification:$it") })) return null
        val composed = composeSubtitleClocks(reference.targetToReference, verified) ?: return null
        // Full-file text proves the relationship between two caption editions,
        // not which edition rate matches this stream. Verify that with widely
        // separated independently heard phrases before transferring any drift.
        if (kotlin.math.abs(composed.rate - 1.0) > .0003 &&
            (boundaries.size < 2 || boundaries.maxOf { it.audioTime } - boundaries.minOf { it.audioTime } < 45)) return null
        return if (!reference.exactTextClock && composed.regions.isEmpty())
            refineTranslatedBoundaries(reference, boundaries, composed, cancelled, verifiedWords) else composed
    }
}

/** Region indexes belong to the chosen translation. Never transplant reference indexes. */
internal fun composeSubtitleClocks(target: AudioSubtitleCorrection, reference: AudioSubtitleCorrection): AudioSubtitleCorrection? {
    if (reference.regions.isNotEmpty()) return null
    val rate = target.rate * reference.rate
    val offset = target.offset * reference.rate + reference.offset
    val regions = target.regions.map { TimingRegion(it.firstCue, it.offset * reference.rate + reference.offset) }
    if (rate !in .9..1.1 || offset !in -600.0..600.0 || regions.any { it.offset !in -600.0..600.0 }) return null
    return AudioSubtitleCorrection(offset, rate, min(target.confidence, reference.confidence), regions, "verified_translation")
}

/** A full-file translation clock identifies corresponding cue boundaries, but
 * editors vary their hold times. Verify the chosen captions against measured
 * phrase boundaries too; never inherit a reference's local bias unchecked. */
internal fun refineTranslatedBoundaries(reference: SubtitleSyncReference, boundaries: List<VerifiedDialogueBoundary>,
    composed: AudioSubtitleCorrection, cancelled: () -> Unit = {}, heard: List<HeardWord> = emptyList()): AudioSubtitleCorrection? {
    if (composed.regions.isNotEmpty() || reference.targetCues.isEmpty()) return null
    val clock = reference.targetToReference
    data class Pairing(val cue: Int, val audio: Double, val offset: Double, val phraseStart: Double, val distinctiveWords: Int)
    val cuePairs = boundaries.mapNotNull { boundary ->
        cancelled()
        val cue = reference.cues.getOrNull(boundary.cue) ?: return@mapNotNull null
        val candidates = reference.targetCues.mapIndexedNotNull { index, target ->
            val point = if (boundary.ending) target.endSeconds else target.startSeconds
            val difference = kotlin.math.abs(point * clock.rate + clock.offset - boundary.captionTime)
            val overlap = min(target.endSeconds * clock.rate + clock.offset, cue.endSeconds) -
                kotlin.math.max(target.startSeconds * clock.rate + clock.offset, cue.startSeconds)
            if (difference <= .6 && overlap >= .5) Triple(index, point, difference) else null
        }.sortedBy { it.third }
        val nearest = candidates.firstOrNull() ?: return@mapNotNull null
        if (candidates.drop(1).any { it.third - nearest.third < .25 }) return@mapNotNull null
        Pairing(nearest.first, boundary.audioTime, boundary.audioTime - nearest.second * composed.rate, boundary.phraseStart, boundary.distinctiveWords)
    }.distinctBy { it.cue }
    // A translation often splits a reference cue at a spoken clause pause.
    // Only measured clause endings or gaps inside word-verified phrases fill
    // a missing target boundary. Keep this transcript independent; neither
    // infer an unknown onset nor interpolate reference cue hold times.
    data class Pause(val audio: Double, val ending: Boolean)
    val pauses = (heard.zipWithNext().filter { (word, next) ->
        word.measuredEnd && word.endReliable && next.startReliable && next.start - word.end in .3..3.0
    }.flatMap { (word, next) -> listOf(Pause(word.end, true), Pause(next.start, false)) } +
        heard.filter { it.measuredEnd && it.endReliable && it.text.trim().lastOrNull() in listOf('.', '?', '!') }
            .map { Pause(it.end,true) }).distinct()
    val verifiedRanges = boundaries.groupBy { it.cue }.mapNotNull { (cueIndex, points) ->
        val cue = reference.cues.getOrNull(cueIndex) ?: return@mapNotNull null
        Triple(cue, points.minOf { it.phraseStart }..points.maxOf { it.audioTime }, points.maxOf { it.distinctiveWords })
    }
    val missingPairs = reference.targetCues.mapIndexedNotNull { index, target ->
        cancelled()
        if (cuePairs.any { it.cue == index }) return@mapIndexedNotNull null
        val candidates = listOf(false, true).mapNotNull { ending ->
            val point = if (ending) target.endSeconds else target.startSeconds
            val referencePoint = point * clock.rate + clock.offset
            val expectedAudio = point * composed.rate + composed.offset
            val range = verifiedRanges.firstOrNull { (cue, _, weight) -> weight >= 4 &&
                referencePoint in cue.startSeconds - .6..cue.endSeconds + .6 &&
                min(target.endSeconds * clock.rate + clock.offset, cue.endSeconds) -
                    kotlin.math.max(target.startSeconds * clock.rate + clock.offset, cue.startSeconds) >= .5
            } ?: return@mapNotNull null
            val nearby = pauses.filter { it.ending == ending && it.audio in range.second &&
                kotlin.math.abs(it.audio - expectedAudio) <= .55 }.sortedBy { kotlin.math.abs(it.audio - expectedAudio) }
            val nearest = nearby.firstOrNull() ?: return@mapNotNull null
            if (nearby.drop(1).any { kotlin.math.abs(it.audio - expectedAudio) - kotlin.math.abs(nearest.audio - expectedAudio) < .25 }) return@mapNotNull null
            Pairing(index,nearest.audio,nearest.audio-point*composed.rate,range.second.start,range.third) to kotlin.math.abs(nearest.audio-expectedAudio)
        }
        candidates.minByOrNull { it.second }?.first
    }
    val pairs = (cuePairs + missingPairs).sortedBy { it.audio }
    // Two substantial sentences are already independently word-verified by
    // the reference matcher. Their uniquely corresponding target boundaries
    // may train then verify a constant clock without inventing a third row.
    val verifiedPhrases = boundaries.filter { it.distinctiveWords >= 4 }.distinctBy { it.cue }
    val minimumExchange = if (verifiedPhrases.size >= 2 && verifiedPhrases.all { it.independentMeasurements >= 2 }) 4 else 6
    val richExchange = pairs.size >= 2 && pairs.all { it.distinctiveWords >= 4 } && verifiedPhrases.size >= 2 &&
        boundaries.maxOf { it.audioTime } - boundaries.minOf { it.phraseStart } >= minimumExchange &&
        pairs.last().audio - pairs.first().audio >= 3 && kotlin.math.abs(composed.rate - 1.0) < .0003
    android.util.Log.i("AliflixAudioSync", "translation_boundaries:count=${pairs.size},rich=$richExchange,offsets=${pairs.map { it.offset }}")
    if (!richExchange && (pairs.size < 3 || pairs.last().audio - pairs.first().audio < 8)) return null
    val training = pairs.dropLast(1).map { it.offset }.sorted()
    val fitted = (training[(training.size-1)/2] + training[training.size/2]) / 2
    // Target caption authors vary hold times. For two unique target boundaries,
    // each measurement has up to half a second of uncertainty. Their intervals
    // must agree; the centered applied clock must still explain every boundary
    // within .55 seconds. Word-level held-out verification remains unchanged.
    val heldTolerance = if (richExchange && pairs.size == 2) 1.0 else .6
    if (training.any { kotlin.math.abs(it-fitted) > .6 } || kotlin.math.abs(pairs.last().offset-fitted) > heldTolerance) return null
    val centered = (pairs.minOf { it.offset } + pairs.maxOf { it.offset }) / 2
    if (pairs.any { kotlin.math.abs(it.offset-centered) > .55 } || kotlin.math.abs(centered-composed.offset) > .75) return null
    return composed.copy(offset=centered, model="verified_translation_boundaries")
}
