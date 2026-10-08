package com.aliflix.app.player

import kotlin.math.abs

/** Text and independent word times are supplied by the selected local decoder.
 * Only a unique, consistent clock supported by earlier AND later phrases applies.
 */
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
            else -> listOf(word.replace("'", ""))
        }.asSequence()
    }.filter { it !in fillers }.map { word ->
        // ASR commonly emits a singular where a caption uses the plural. This
        // normalization is lexical only; it supplies no timing information.
        if (word.length > 5 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word
    }.toList()
    private data class Word(val text: String, val start: Double, val end: Double)
    private data class Anchor(val cue: Int, val time: Double, val end: Double, val offset: Double, val captionTime: Double, val weight: Int, val coverage: Double)

    fun match(cues: List<SubtitleCue>, heard: List<HeardWord>, speech: List<SpeechWindow> = emptyList(), recentAfter: Double? = null,
        diagnostic: (String) -> Unit = {}, cancelled: () -> Unit = {}): AudioSubtitleCorrection? {
        if (heard.size < 9 || cues.size !in 4..20000) return null
        val words = heard.flatMap { word -> tokens(word.text).map { Word(it, word.start, word.end) } }
            .fold(mutableListOf<Word>()) { list, word ->
                if (list.lastOrNull()?.let { it.text == word.text && word.start - it.start < .3 } != true) list.add(word)
                list
            }
        if (words.size < 9 || words.any { !it.start.isFinite() || !it.end.isFinite() || it.end <= it.start }) return null
        val first = words.first().start; val last = words.last().end
        if (last - first < 8) return null
        val joinedWords = words.zipWithNext().map { it.first.text + it.second.text to listOf(it.first.text, it.second.text) }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct().singleOrNull() }
        val anchors = mutableListOf<Anchor>()
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
                val matchedIndices = mutableListOf<Int>()
                val matchedTokens = mutableSetOf<String>()
                for (reference in prefix until expected.size) {
                    val wanted = expected[reference]
                    if (at >= words.size || (at > begin && words[at].start - words[at - 1].end > 3.0)) break
                    if (words[at].text != wanted && at + 1 < words.size && words[at + 1].text == wanted && skipped < 2) { at++; skipped++ }
                    if (words[at].text == wanted) {
                        matched++; matchedIndices.add(reference); matchedTokens.add(wanted); at++
                    }
                }
                val coverage = matched.toDouble() / expected.size
                val distinctiveMatched = distinctive.count { it in matchedTokens }
                val partialPrefix = prefix > 0
                if (distinctiveMatched < 2 || at <= begin + 2 ||
                    (!partialPrefix && coverage < .85) ||
                    (partialPrefix && (matched < 6 || coverage < .75 ||
                        !(expected.size - 3 until expected.size).all { it in matchedIndices }))) continue
                val firstWord = words[begin]; val finalWord = words[at - 1]
                val duration = cue.endSeconds - cue.startSeconds
                if (finalWord.end - firstWord.start > duration + 4) continue
                val firstReliable = !partialPrefix && firstWord.end - firstWord.start <= .65
                val finalReliable = matchedIndices.lastOrNull() == expected.lastIndex && finalWord.end - finalWord.start <= .65
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
                if (abs(offset) <= 600) anchors.add(Anchor(index, firstWord.start, finalWord.end, offset, if (firstReliable) cue.startSeconds else cue.endSeconds, distinctiveMatched, coverage))
            }
        }
        diagnostic("word_anchors=$anchors") // Numeric timing and coverage only.
        data class Verified(val offset: Double, val rate: Double, val weight: Int, val count: Int, val coverage: Double)
        // Recognized framerates require widely separated observed phrases.
        // A single short dialogue window continues to fit constant offset only.
        val supportedRates = if (anchors.size >= 3 &&
            anchors.maxOf { it.time } - anchors.minOf { it.time } >= 45)
            AdaptiveSubtitleSynchronizer.rates else listOf(1.0)
        val verified = supportedRates.flatMap { rate ->
            val adjusted = anchors.map { it.copy(offset = it.offset + (1 - rate) * it.captionTime) }
            val candidates = adjusted.map { seed ->
                adjusted.filter { abs(it.offset - seed.offset) <= .55 }.distinctBy { it.cue }.sortedBy { it.time }
            }.filter { it.size >= 3 && it.last().end - it.first().time >= 8 &&
                (recentAfter == null || it.last().end >= recentAfter) }
                .distinctBy { group -> group.map { it.cue } }
            candidates.mapNotNull { group ->
                val training = group.dropLast(1)
                val held = group.last()
                val offsets = training.map { it.offset }.sorted()
                val offset = (offsets[(offsets.size - 1) / 2] + offsets[offsets.size / 2]) / 2
                if (training.count { it.coverage >= .85 } < 2 || training.maxOf { abs(it.offset - offset) } > .55 || abs(held.offset - offset) > .55) null
                else Verified(offset, rate, group.sumOf { it.weight }, group.size, group.minOf { it.coverage })
            }
        }.sortedByDescending { it.weight }
        val best = verified.firstOrNull() ?: run { diagnostic("word_no_independent_clock"); return null }
        if (best.weight < 7 || verified.any {
            val from = cues.first().startSeconds; val until = cues.last().endSeconds
            (abs((it.rate - best.rate) * from + it.offset - best.offset) > .6 ||
                abs((it.rate - best.rate) * until + it.offset - best.offset) > .6) && it.weight >= best.weight - 2
        }) return null
        // Independently matched phrases must not support a steadily changing clock.
        val supporting = anchors.map { it.copy(offset = it.offset + (1 - best.rate) * it.captionTime) }.filter { abs(it.offset - best.offset) <= 1.5 }.distinctBy { it.cue }.sortedBy { it.time }
        if (supporting.size >= 3) {
            val x = supporting.map { it.time }; val y = supporting.map { it.offset }
            val xm = x.average(); val ym = y.average(); val variance = x.sumOf { (it - xm) * (it - xm) }
            val slope = if (variance > 0) x.indices.sumOf { (x[it] - xm) * (y[it] - ym) } / variance else 0.0
            val residual = x.indices.maxOf { abs(y[it] - ym - slope * (x[it] - xm)) }
            if (abs(slope) * (x.last() - x.first()) > .65 && residual < .15) return null
        }
        return AudioSubtitleCorrection(best.offset, best.rate, best.coverage)
    }
}
