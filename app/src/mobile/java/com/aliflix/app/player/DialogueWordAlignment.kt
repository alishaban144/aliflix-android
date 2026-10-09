package com.aliflix.app.player

import kotlin.math.abs

/** Text and independent word times are supplied by the selected local decoder.
 * Only a unique, consistent clock supported by earlier AND later phrases applies.
 */
/** Recognition may finish at a silence boundary; every played phrase in the
 * same 30-second analysis window remains eligible evidence. */
internal const val CURRENT_DIALOGUE_SECONDS = 30.0

internal data class VerifiedDialogueBoundary(val cue: Int, val captionTime: Double, val audioTime: Double, val ending: Boolean,
    val phraseStart: Double = audioTime, val distinctiveWords: Int = 0, val independentMeasurements: Int = 1)

internal object DialogueWordAlignment {
    private val token = Regex("[\\p{L}\\p{N}']+")
    private val markup = Regex("<[^>]*>")
    private val fillers = setOf("um", "uh", "erm")
    private val common = setOf("a", "an", "the", "and", "then", "i", "you", "your", "me", "my", "we", "it", "is", "am", "are", "was", "were", "to", "in", "on", "of", "that", "this", "do", "not", "can")
    private fun tokens(text: String): List<String> = token.findAll(text.replace(markup, "").lowercase()).flatMap { match ->
        when (val word = match.value) {
            "i'm" -> listOf("i", "am"); "it's" -> listOf("it", "is"); "don't" -> listOf("do", "not")
            "can't" -> listOf("can", "not"); "we're" -> listOf("we", "are"); "you're" -> listOf("you", "are")
            "i'll" -> listOf("i", "will"); "that's" -> listOf("that", "is")
            "ifyou" -> listOf("if", "you") // Common missing space in original SRT releases.
            "sent" -> listOf("send") // ASR may transcribe this spoken inflection either way.
            else -> listOf(word.replace("'", ""))
        }.asSequence()
    }.filter { it !in fillers }.map { word ->
        // ASR commonly emits a singular where a caption uses the plural. This
        // normalization is lexical only; it supplies no timing information.
        if (word.length > 5 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word
    }.toList()
    private data class Word(val text: String, val start: Double, val end: Double, val endReliable: Boolean, val measuredEnd: Boolean, val startReliable: Boolean)
    private data class Anchor(val cue: Int, val time: Double, val end: Double, val offset: Double, val captionTime: Double, val weight: Int, val coverage: Double, val measuredEnding: Boolean, val corroborationOnly: Boolean = false, val clip: Int = 0)
    private fun Anchor.richPhrase() = weight >= 4 && (coverage >= .85 || coverage >= .75 && measuredEnding)

    fun match(cues: List<SubtitleCue>, heard: List<HeardWord>, speech: List<SpeechWindow> = emptyList(), recentAfter: Double? = null,
        diagnostic: (String) -> Unit = {}, boundaries: (List<VerifiedDialogueBoundary>) -> Unit = {},
        cancelled: () -> Unit = {}): AudioSubtitleCorrection? =
        matchClips(cues, listOf(heard), speech, recentAfter, diagnostic, boundaries, cancelled)

    /** Extract phrases inside each independent transcript. Never splice words
     * across clips. Widely separated measured phrases certify an edition rate. */
    fun matchClips(cues: List<SubtitleCue>, clips: List<List<HeardWord>>, speech: List<SpeechWindow> = emptyList(), recentAfter: Double? = null,
        diagnostic: (String) -> Unit = {}, boundaries: (List<VerifiedDialogueBoundary>) -> Unit = {},
        cancelled: () -> Unit = {}, requireWideClock: Boolean = false, candidateRates: List<Double>? = null): AudioSubtitleCorrection? {
        if (cues.size !in 4..20000 || clips.sumOf { it.size } < 9) return null
        val anchors = mutableListOf<Anchor>()
        val phraseBoundaries = mutableListOf<Triple<VerifiedDialogueBoundary, Double, Int>>()
        for ((clipIndex, heard) in clips.withIndex()) {
        cancelled()
        if (heard.size < 3) continue
        val words = heard.flatMap { word -> tokens(word.text).map { Word(it, word.start, word.end, word.endReliable, word.measuredEnd, word.startReliable) } }
            .fold(mutableListOf<Word>()) { list, word ->
                if (list.lastOrNull()?.let { it.text == word.text && word.start - it.start < .3 } != true) list.add(word)
                list
            }
        if (words.size < 3 || words.any { !it.start.isFinite() || !it.end.isFinite() || it.end < it.start || it.end == it.start && it.endReliable }) continue
        val first = words.first().start; val last = words.last().end
        if (last - first < .5) continue
        val joinedWords = words.zipWithNext().map { it.first.text + it.second.text to listOf(it.first.text, it.second.text) }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct().singleOrNull() }
        cues.forEachIndexed { index, cue ->
            if (index % 32 == 0) cancelled()
            if (!AdaptiveSubtitleSynchronizer.dialogue(cue) || cue.startSeconds !in first - 600..last + 600) return@forEachIndexed
            // Some SRT files accidentally join adjacent words at line edits.
            // Repair only an unambiguous pair actually recognized in the audio.
            val expected = tokens(cue.text).flatMap { joinedWords[it] ?: listOf(it) }
            val distinctive = expected.filter { it.length >= 3 && it !in common }.distinct()
            if (expected.size !in 3..35 || distinctive.size < 2) return@forEachIndexed
            for (begin in words.indices) {
                val prefix = expected.indexOf(words[begin].text)
                if (prefix !in 0..2) continue
                var at = begin; var matched = 0; var skipped = 0
                val insertionBudget = maxOf(2, minOf(6, (expected.size + 4) / 5))
                val matchedIndices = mutableListOf<Int>()
                val matchedTokens = mutableSetOf<String>()
                for (reference in prefix until expected.size) {
                    val wanted = expected[reference]
                    if (at >= words.size || (at > begin && words[at].start - words[at - 1].end > 3.0)) break
                    if (words[at].text != wanted && skipped < insertionBudget) {
                        val next = (at + 1..minOf(words.lastIndex, at + insertionBudget - skipped)).firstOrNull { candidate ->
                            words[candidate].text == wanted && (at until candidate).all { words[it + 1].start - words[it].end <= 3.0 }
                        }
                        if (next != null) { skipped += next - at; at = next }
                    }
                    if (words[at].text == wanted) {
                        matched++; matchedIndices.add(reference); matchedTokens.add(wanted); at++
                    }
                }
                val coverage = matched.toDouble() / expected.size
                val distinctiveMatched = distinctive.count { it in matchedTokens }
                val partialPrefix = prefix > 0
                // A recognizer may omit articles/pronouns in a fast exchange.
                // All content words and the measured ending must survive. Such
                // a phrase can only corroborate two independently rich phrases;
                // it never trains their offset or supplies an invented onset.
                val commonOmissions = coverage >= .55 && matched >= 6 && distinctiveMatched >= 3 &&
                    expected.indices.filter { it !in matchedIndices }.all { expected[it] in common || expected[it] == "hey" } &&
                    matchedIndices.lastOrNull() == expected.lastIndex
                val corroborationOnly = commonOmissions && coverage < .85
                if (distinctiveMatched < 2 || at <= begin + 2 ||
                    (!partialPrefix && coverage < .85 && !commonOmissions) ||
                    (partialPrefix && !commonOmissions && (matched < 6 || coverage < .75 ||
                        !(expected.size - 3 until expected.size).all { it in matchedIndices }))) continue
                val firstWord = words[begin]; val finalWord = words[at - 1]
                val duration = cue.endSeconds - cue.startSeconds
                if (finalWord.end - firstWord.start > duration + 4) continue
                // Android supplies the next onset as an ending proxy; a long
                // gap there is silence padding. Whisper supplies a measured
                // word ending, so a naturally elongated word remains usable.
                val firstReliable = !corroborationOnly && !partialPrefix && firstWord.startReliable && firstWord.endReliable &&
                    (firstWord.end - firstWord.start <= .65 || firstWord.measuredEnd && firstWord.text.length >= 4 && firstWord.text !in common)
                val finalReliable = finalWord.endReliable && matchedIndices.lastOrNull() == expected.lastIndex &&
                    (finalWord.measuredEnd || finalWord.end - finalWord.start <= .65)
                if (corroborationOnly && (!finalReliable || !finalWord.measuredEnd)) continue
                if (firstReliable) phraseBoundaries.add(Triple(VerifiedDialogueBoundary(index, cue.startSeconds, firstWord.start, false, firstWord.start, distinctiveMatched), coverage, clipIndex))
                if (finalReliable) phraseBoundaries.add(Triple(VerifiedDialogueBoundary(index, cue.endSeconds, finalWord.end, true, firstWord.start, distinctiveMatched), coverage, clipIndex))
                val startOffset = firstWord.start - cue.startSeconds
                val endOffset = finalWord.end - cue.endSeconds
                // A recognizer can pad a first word with preceding silence.
                // The final spoken word is an independent boundary when the
                // phrase's ending is present. Never guess a missing onset.
                val offset = when {
                    firstReliable -> startOffset
                    finalReliable -> endOffset
                    else -> continue
                }
                if (abs(offset) <= 600) anchors.add(Anchor(index, firstWord.start, finalWord.end, offset, if (firstReliable) cue.startSeconds else cue.endSeconds, distinctiveMatched, coverage, finalReliable && finalWord.measuredEnd, corroborationOnly, clipIndex))
            }
        }
        }
        // Shifted clips can recognize the same words with a compressed or
        // padded timestamp. When two independent responses agree on a measured
        // boundary, a lone conflicting response cannot supply a second clock.
        // Vote once per clip; repeated partial matches are not extra evidence.
        // A reliable complete onset owns this clip's phrase clock. A partial
        // suffix from those same words is not another independent measurement;
        // caption hold time must not create a competing offset inside one clip.
        val preferred = anchors.groupBy { it.cue to it.clip }.values.flatMap { samePhrase ->
            val onsets = samePhrase.filter { !it.corroborationOnly && it.coverage >= .85 && it.captionTime == cues[it.cue].startSeconds }
            onsets.ifEmpty { samePhrase }
        }
        val agreed = preferred.groupBy { it.cue to it.captionTime }.values.flatMap { sameBoundary ->
            val support = sameBoundary.associateWith { anchor ->
                sameBoundary.filter { abs(it.offset - anchor.offset) <= .35 }.map { it.clip }.distinct().size
            }
            val maximum = support.values.maxOrNull() ?: 0
            sameBoundary.filter { maximum < 2 || support.getValue(it) >= 2 }
        }
        diagnostic("word_consensus_discarded=${anchors.size - agreed.size}")
        anchors.retainAll(agreed.toSet())
        diagnostic("word_anchors=$anchors") // Numeric timing and coverage only.
        fun repeatedMeasurement(anchor: Anchor) = anchors.filter {
            it.cue == anchor.cue && it.captionTime == anchor.captionTime &&
                !it.corroborationOnly && it.measuredEnding && abs(it.offset - anchor.offset) <= .35
        }.map { it.clip }.distinct().size >= 2
        if (requireWideClock && (anchors.distinctBy { it.cue }.size < 3 ||
            anchors.maxOf { it.time } - anchors.minOf { it.time } < 45)) return null
        data class Verified(val offset: Double, val rate: Double, val weight: Int, val count: Int, val coverage: Double)
        // Recognized framerates require widely separated observed phrases.
        // A single short dialogue window continues to fit constant offset only.
        val supportedRates = if (anchors.size >= 3 &&
            anchors.maxOf { it.time } - anchors.minOf { it.time } >= 45)
            candidateRates?.filter { it.isFinite() && it in .9..1.1 }?.distinct() ?: AdaptiveSubtitleSynchronizer.rates else listOf(1.0)
        val verified = supportedRates.flatMap { rate ->
            val adjusted = anchors.map { it.copy(offset = it.offset + (1 - rate) * it.captionTime) }
            val candidates = adjusted.map { seed ->
                adjusted.filter { abs(it.offset - seed.offset) <= if (it.corroborationOnly) .75 else .550001 }.distinctBy { it.cue }.sortedBy { it.time }
            }.filter { (it.size >= 3 || it.size == 2 && it.all { anchor -> anchor.richPhrase() }) &&
                it.last().end - it.first().time >= (if (it.size == 2) {
                    // A compact exchange needs two independent measurements of
                    // BOTH complete phrases; one response still needs six seconds.
                    if (it.all { anchor -> repeatedMeasurement(anchor) }) 4 else 6
                } else 8) &&
                (recentAfter == null || it.last().end >= recentAfter) &&
                (!requireWideClock || it.last().time - it.first().time >= 45) }
                // The same caption indexes may describe competing audio clocks.
                // Deduplicating only indexes would hide a contradictory response.
                .distinctBy { group -> group.map { it.cue to it.offset } }
            candidates.mapNotNull { group ->
                val strong = group.filterNot { it.corroborationOnly }
                if (strong.size < 2) return@mapNotNull null
                val training = strong.dropLast(1)
                val held = strong.last()
                val offsets = training.map { it.offset }.sorted()
                val offset = (offsets[(offsets.size - 1) / 2] + offsets[offsets.size / 2]) / 2
                val requiredTraining = if (strong.size == 2 && strong.all { it.richPhrase() }) 1 else 2
                // A clip may begin mid-sentence. A long distinctive suffix
                // with a measured, complete ending can train a two-phrase
                // constant clock; the later phrase must still verify it.
                val trainingCount = training.count { if (requiredTraining == 1) it.richPhrase() else it.coverage >= .85 }
                if (trainingCount < requiredTraining || training.maxOf { abs(it.offset - offset) } > .550001 || abs(held.offset - offset) > .550001 ||
                    group.filter { it.corroborationOnly }.any { abs(it.offset - (strong.minOf { it.offset } + strong.maxOf { it.offset }) / 2) > .75 }) null
                else {
                    // The held phrase has already passed independently. Center
                    // the applied clock over every verified boundary to minimize
                    // its worst timing error, rather than favoring earlier words.
                    val centered = (strong.minOf { it.offset } + strong.maxOf { it.offset }) / 2
                    Verified(centered, rate, group.sumOf { it.weight }, group.size, strong.minOf { it.coverage })
                }
            }
        }.sortedByDescending { it.weight }
        val best = verified.firstOrNull() ?: run { diagnostic("word_no_independent_clock"); return null }
        if (best.weight < 7 || verified.any {
            val from = cues.first().startSeconds; val until = cues.last().endSeconds
            (abs((it.rate - best.rate) * from + it.offset - best.offset) > .6 ||
                abs((it.rate - best.rate) * until + it.offset - best.offset) > .6) && it.weight >= best.weight - 2
        }) return null
        // A candidate cannot discard the latest clearly recognized phrase merely
        // because that phrase contradicts its clock. That would accept the first
        // half of a drifting scene and ignore the evidence the user just heard.
        val latest = anchors.filter { it.coverage >= .85 && it.weight >= 3 || it.richPhrase() }
            .maxByOrNull { it.end }
        if (latest != null && abs(latest.offset + (1 - best.rate) * latest.captionTime - best.offset) > .6) {
            diagnostic("word_latest_phrase_contradicts_clock")
            return null
        }
        // Independently matched phrases must not support a steadily changing clock.
        val supporting = anchors.map { it.copy(offset = it.offset + (1 - best.rate) * it.captionTime) }.filter { !it.corroborationOnly && abs(it.offset - best.offset) <= 1.5 }.distinctBy { it.cue }.sortedBy { it.time }
        if (supporting.size >= 3) {
            val x = supporting.map { it.time }; val y = supporting.map { it.offset }
            val xm = x.average(); val ym = y.average(); val variance = x.sumOf { (it - xm) * (it - xm) }
            val slope = if (variance > 0) x.indices.sumOf { (x[it] - xm) * (y[it] - ym) } / variance else 0.0
            val residual = x.indices.maxOf { abs(y[it] - ym - slope * (x[it] - xm)) }
            if (abs(slope) * (x.last() - x.first()) > .65 && residual < .15) return null
        }
        boundaries(phraseBoundaries.filter { (boundary, coverage) -> (coverage >= .85 || boundary.ending && anchors.any { it.cue == boundary.cue && it.corroborationOnly } || coverage >= .75 && boundary.ending &&
            anchors.any { it.cue == boundary.cue && it.measuredEnding && it.richPhrase() }) &&
            abs(boundary.audioTime - boundary.captionTime * best.rate - best.offset) <= if (coverage < .75) .75 else .65
        }.groupBy { it.first.cue to it.first.ending }.values.map { candidates ->
            // Retain measured onsets AND endings: a translation may split or
            // hold a sentence differently. Each response gets one vote; center
            // independently measured boundaries rather than picking the first
            // equally complete response and inheriting its timestamp padding.
            val independent = candidates.groupBy { it.third }.values.map { it.maxBy { row -> row.second } }
            val times = independent.map { it.first.audioTime }.sorted()
            val center = (times[(times.size - 1) / 2] + times[times.size / 2]) / 2
            val starts = independent.map { it.first.phraseStart }.sorted()
            independent.maxBy { it.second }.first.copy(audioTime = center,
                phraseStart = (starts[(starts.size - 1) / 2] + starts[starts.size / 2]) / 2,
                independentMeasurements = independent.size)
        }.sortedBy { it.audioTime })
        return AudioSubtitleCorrection(best.offset, best.rate, best.coverage)
    }
}
