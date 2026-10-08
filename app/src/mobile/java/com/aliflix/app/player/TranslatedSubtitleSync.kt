package com.aliflix.app.player

import kotlin.math.min

internal data class SubtitleSyncReference(val cues: List<SubtitleCue>, val targetToReference: AudioSubtitleCorrection)

/** Translation changes words, not the independently verified playback clock.
 * First align the complete chosen translation to a reference timeline, then
 * verify that reference against current spoken phrases. Neither affiliation nor
 * similar filenames can certify a reference, and the chosen text never changes.
 */
internal object TranslatedSubtitleSync {
    fun prepare(target: List<SubtitleCue>, reference: List<SubtitleCue>, cancelled: () -> Unit = {}): SubtitleSyncReference? {
        matchCaptionTimelines(target, reference, cancelled)?.let { return SubtitleSyncReference(reference, it) }
        matchCaptionGaps(target, reference, cancelled)?.let { return SubtitleSyncReference(reference, it) }
        val windows = AdaptiveSubtitleSynchronizer.referenceWindows(reference)
        val clock = AdaptiveSubtitleSynchronizer.matchQuick(target, windows, reference, cancelled).correction ?: return null
        return SubtitleSyncReference(reference, clock)
    }

    fun match(reference: SubtitleSyncReference, heard: List<HeardWord>, history: List<HeardWord>,
              audio: List<SpeechWindow>, position: Double, cancelled: () -> Unit = {}): AudioSubtitleCorrection? {
        val verified = DialogueWordAlignment.match(reference.cues, heard, audio, recentAfter = position - 18,
                diagnostic = { android.util.Log.i("AliflixAudioSync", "reference_current:$it") }, cancelled = cancelled)
            ?: (if (history.any { it.end >= position - 30 }) DialogueWordAlignment.match(reference.cues, history, audio,
                recentAfter = position - 18,
                diagnostic = { android.util.Log.i("AliflixAudioSync", "reference_history:$it") }, cancelled = cancelled) else null)
            ?: return null
        if (!AdaptiveSubtitleSynchronizer.verifyEarlierClock(reference.cues, verified, audio, position, cancelled,
            diagnostic = { android.util.Log.i("AliflixAudioSync", "reference_verification:$it") })) return null
        return composeSubtitleClocks(reference.targetToReference, verified)
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
