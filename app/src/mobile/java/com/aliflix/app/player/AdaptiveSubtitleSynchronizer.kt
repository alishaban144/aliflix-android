package com.aliflix.app.player

import kotlin.math.*

/** Native speech-presence alignment. Every FFT uses only observed PCM: unknown
 * scenes never become silence. Search and acceptance use different scenes.
 * Scores are correlations, not probabilities. See docs/mobile-audio-sync.md.
 */
internal object AdaptiveSubtitleSynchronizer {
    private const val HZ = 25
    const val MAX_OFFSET = 600.0
    val rates = listOf(1.0, 25.0 / 24.0, 24.0 / 25.0, 25.0 / 23.976,
        23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0)
    data class Result(val correction: AudioSubtitleCorrection? = null, val reason: String,
                      val windows: Int = 0, val score: Double = 0.0, val margin: Double = 0.0)
    private data class Curve(val window: SpeechWindow, val scores: DoubleArray, val radius: Int) {
        val peak get() = scores.indices.maxBy { scores[it] }
        fun offset(index: Int) = (radius - index).toDouble() / HZ
        fun at(offset: Double): Double = scores.getOrElse((radius - offset * HZ).roundToInt()) { -1.0 }
        fun unique(offset: Double, distance: Double = 1.2): Double {
            val best = at(offset)
            var rival = -1.0
            for (i in scores.indices) if (abs(this.offset(i) - offset) > distance) rival = max(rival, scores[i])
            return best - rival
        }
    }

    fun dialogue(cue: SubtitleCue): Boolean {
        val text = cue.text.replace(Regex("<[^>]*>"), "").trim()
        return cue.startSeconds.isFinite() && cue.endSeconds.isFinite() && cue.startSeconds >= 0 &&
            cue.endSeconds > cue.startSeconds && cue.endSeconds - cue.startSeconds <= 15 &&
            text.any(Char::isLetter) && !text.startsWith("♪") && !text.startsWith("♫") &&
            !(text.startsWith("[") && text.endsWith("]"))
    }

    private fun smooth(raw: DoubleArray, radius: Int = 2): DoubleArray {
        val prefix = DoubleArray(raw.size + 1)
        raw.indices.forEach { prefix[it + 1] = prefix[it] + raw[it] }
        return DoubleArray(raw.size) { i ->
            val a = max(0, i - radius); val b = min(raw.size, i + radius + 1)
            (prefix[b] - prefix[a]) / (b - a)
        }
    }

    private fun curve(cues: List<SubtitleCue>, window: SpeechWindow, rate: Double,
                      cancelled: () -> Unit): Curve {
        cancelled()
        val a = smooth(DoubleArray(window.speech.size / 2) { (window.speech[it * 2] + window.speech[it * 2 + 1]) / 2 })
        val radius = (MAX_OFFSET * HZ).toInt()
        val start = window.start - MAX_OFFSET
        val raw = DoubleArray(a.size + 2 * radius)
        cues.forEach { cue ->
            if (dialogue(cue)) {
                val from = floor((cue.startSeconds * rate - start) * HZ).toInt().coerceIn(0, raw.size)
                val to = ceil((cue.endSeconds * rate - start) * HZ).toInt().coerceIn(0, raw.size)
                for (i in from until to) raw[i] = 1.0
            }
        }
        val b = smooth(raw)
        val sum = a.sum(); val energy = a.sumOf { it * it } - sum * sum / a.size
        val cross = AudioSubtitleAlignment.convolution(a.reversedArray(), b, cancelled)
        val prefix = DoubleArray(b.size + 1); val squared = DoubleArray(b.size + 1)
        b.indices.forEach { prefix[it + 1] = prefix[it] + b[it]; squared[it + 1] = squared[it] + b[it] * b[it] }
        val scores = DoubleArray(2 * radius + 1) { shift ->
            if (shift % 256 == 0) cancelled()
            val s = prefix[shift + a.size] - prefix[shift]
            val variance = squared[shift + a.size] - squared[shift] - s * s / a.size
            if (energy < 4 || variance < 4) -1.0 else
                ((cross[shift + a.size - 1] - sum * s / a.size) / sqrt(energy * variance)).coerceIn(-1.0, 1.0)
        }
        return Curve(window, scores, radius)
    }

    private fun usable(windows: List<SpeechWindow>) = windows.sortedBy { it.start }
        .fold(mutableListOf<SpeechWindow>()) { scenes, window ->
            val previous = scenes.lastOrNull()
            // Adjacent known blocks provide a longer, more distinctive acoustic
            // pattern. Keep seeks/gaps separate; never invent unseen silence.
            if (previous?.speech?.size == 20 * SPEECH_HZ && window.speech.size == 20 * SPEECH_HZ &&
                abs(previous.start + 20 - window.start) < .02)
                scenes[scenes.lastIndex] = SpeechWindow(previous.start, previous.speech + window.speech)
            else scenes.add(window)
            scenes
        }.filter {
        it.start.isFinite() && it.start >= 0 && it.speech.size >= 20 * SPEECH_HZ &&
            it.speech.all { bit -> bit.isFinite() && bit in 0.0..1.0 } &&
            it.speech.average() in .08.. .92 &&
            (1 until it.speech.size).count { index -> abs(it.speech[index - 1] - it.speech[index]) > .4 } >= 6
    }.fold(mutableListOf<SpeechWindow>()) { list, window ->
        if (list.isEmpty() || window.start >= list.last().start + list.last().speech.size.toDouble() / SPEECH_HZ - .02) list.add(window)
        list
    }.let { all ->
        // Bound CPU independently of film length, while retaining early/late scenes.
        if (all.size <= 18) all else (0 until 18).map { all[it * (all.size - 1) / 17] }
    }

    fun match(cues: List<SubtitleCue>, windows: List<SpeechWindow>,
              completeReference: List<SubtitleCue>? = null, cancelled: () -> Unit = {}): Result {
        if (cues.size !in 4..20000 || cues.any { !it.startSeconds.isFinite() || !it.endSeconds.isFinite() || it.endSeconds > 21600 })
            return Result(reason = "invalid_target_timeline")
        val usable = usable(windows)
        if (usable.size < 3) return Result(reason = "insufficient_independent_windows", windows = usable.size)
        val training = usable.filterIndexed { i, _ -> i % 2 == 0 }
        val validation = usable.filterIndexed { i, _ -> i % 2 != 0 }
        val span = usable.last().start - usable.first().start
        data class Proposal(val rate: Double, val offset: Double, val score: Double, val margin: Double, val curves: List<Curve>)
        val proposals = mutableListOf<Proposal>()
        val searched = mutableMapOf<Double, List<Curve>>()
        val candidateRates = rates.toMutableList()
        var index = 0
        while (index < candidateRates.size) {
            val rate = candidateRates[index++]
            // Small FPS differences need enough elapsed media to distinguish them.
            if (rate != 1.0 && (usable.size < 5 || span * abs(rate - 1.0) < 1.0)) continue
            val curves = training.map { curve(cues, it, rate, cancelled) }
            searched[rate] = curves
            val aggregate = DoubleArray(curves.first().scores.size) { shift -> curves.map { it.scores[shift] }.average() }
            val peak = aggregate.indices.maxBy { aggregate[it] }
            if (peak == 0 || peak == aggregate.lastIndex) continue
            val offset = curves.first().offset(peak)
            var rival = -1.0
            for (i in aggregate.indices) if (abs(i - peak) > 1.2 * HZ) rival = max(rival, aggregate[i])
            val score = aggregate[peak]; val margin = score - rival
            if (score >= .46 && margin >= .055 && curves.all { it.at(offset) >= .35 })
                proposals.add(Proposal(rate, offset, score, margin, curves))
            if (rate == 1.0 && usable.size >= 5 && span >= 60) {
                // LAPSE-style OLS proposal, established on training scenes only.
                val anchors = curves.filter { it.scores[it.peak] >= .5 && it.unique(it.offset(it.peak)) >= .07 }
                if (anchors.size >= 3) {
                    val x = anchors.map { it.window.start + it.window.speech.size / (2.0 * SPEECH_HZ) - it.offset(it.peak) }
                    val y = anchors.map { it.offset(it.peak) }
                    val xm = x.average(); val ym = y.average()
                    val denom = x.sumOf { (it - xm).pow(2) }
                    if (denom > 0) {
                        val inferred = 1 + x.indices.sumOf { (x[it] - xm) * (y[it] - ym) } / denom
                        if (inferred in .9..1.1 && rates.none { abs(it - inferred) < .0002 }) candidateRates.add(inferred)
                    }
                }
            }
        }
        // Prefer the simpler model when the evidence cannot distinguish a slope.
        val ranked = proposals.sortedByDescending { it.score - if (it.rate == 1.0) 0.0 else .025 }
        for (proposal in ranked) {
            cancelled()
            val held = validation.map { curve(cues, it, proposal.rate, cancelled) }
            if (held.any { it.at(proposal.offset) < .46 || it.unique(proposal.offset) < .04 ||
                    abs(it.offset(it.peak) - proposal.offset) > .6 }) continue
            if (proposal.curves.any { abs(it.offset(it.peak) - proposal.offset) > .65 }) continue
            if (proposal.rate == 1.0 && proposal.curves.size >= 3) {
                val x = proposal.curves.map { it.window.start }
                val y = proposal.curves.map { it.offset(it.peak) }
                val xm = x.average(); val ym = y.average()
                val denominator = x.sumOf { (it - xm).pow(2) }
                val slope = if (denominator > 0) x.indices.sumOf { (x[it] - xm) * (y[it] - ym) } / denominator else 0.0
                val residual = x.indices.maxOf { abs(y[it] - ym - slope * (x[it] - xm)) }
                // A short, consistently sloping sequence is evidence AGAINST a
                // constant offset even when it cannot yet verify a whole-film
                // drift model. Keep collecting instead of caching the wrong model.
                if (abs(slope) * (x.max() - x.min()) >= .5 && residual <= .15) continue
            }
            if (proposal.rate !in rates) {
                // A fitted slope is only safe for the WHOLE target when observed
                // scatter also bounds extrapolation, rather than merely fitting
                // the three scenes used to estimate it.
                val anchors = (proposal.curves + held).map { it.offset(it.peak) }
                val scatter = anchors.max() - anchors.min() + .08
                val extent = cues.maxOf { it.endSeconds } * proposal.rate
                if (scatter * max(1.0, extent / span) > 1.0) continue
            }
            val competing = proposals.any { other ->
                other !== proposal && other.score >= proposal.score - .03 &&
                    usable.any { w -> abs((w.start - proposal.offset) / proposal.rate * other.rate + other.offset - w.start) > .8 }
            }
            if (competing) return Result(reason = "ambiguous_timing_models", windows = usable.size)
            val score = min(proposal.score, held.map { it.at(proposal.offset) }.average())
            return Result(AudioSubtitleCorrection(proposal.offset, proposal.rate, score), "verified_${if (proposal.rate == 1.0) "offset" else "drift"}",
                usable.size, score, min(proposal.margin, held.minOf { it.unique(proposal.offset) }))
        }
        // Edits cannot be located inside unobserved scenes. Only a complete
        // verified subtitle reference permits assigning every cue to a region.
        if (completeReference != null && usable.size >= 6) {
            val rejected = mutableListOf<String>()
            val pieces = searched.mapNotNull { (rate, fitted) ->
                var train = 0
                val local = usable.mapIndexed { index, window ->
                    if (index % 2 == 0) fitted[train++] else curve(cues, window, rate, cancelled)
                }
                split(cues, completeReference, local, rate, cancelled) { rejected.add(it) }
            }.sortedByDescending { it.score - if (it.correction?.rate == 1.0) 0.0 else .025 }
            pieces.firstOrNull()?.let { best ->
                val model = best.correction!!
                val competing = pieces.drop(1).any { other ->
                    other.score >= best.score - .03 && model.apply(cues).zip(other.correction!!.apply(cues)).any { (a, b) ->
                        abs(a.startSeconds - b.startSeconds) > .8
                    }
                }
                return if (competing) Result(reason = "ambiguous_piecewise_models", windows = usable.size) else best
            }
            return Result(reason = "piecewise_rejected:${rejected.distinct().joinToString(";")}", windows = usable.size)
        }
        return Result(reason = "inconsistent_or_ambiguous_evidence", windows = usable.size,
            score = proposals.maxOfOrNull { it.score } ?: 0.0)
    }

    private fun split(cues: List<SubtitleCue>, reference: List<SubtitleCue>, curves: List<Curve>, rate: Double, cancelled: () -> Unit,
                      rejected: (String) -> Unit): Result? {
        if (curves.any { it.scores[it.peak] < .5 || it.unique(it.offset(it.peak)) < .065 }) {
            val index = curves.indexOfFirst { it.scores[it.peak] < .5 || it.unique(it.offset(it.peak)) < .065 }
            val weak = curves[index]
            rejected("weak_scene:$rate:$index:${weak.scores[weak.peak]},${weak.unique(weak.offset(weak.peak))}")
            return null
        }
        val offsets = curves.map { it.offset(it.peak) }
        val n = offsets.size
        // Penalised segmentation of independent scene measurements. Every region
        // pays a complexity penalty and needs both fitting and held-out scenes.
        val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }; cost[0] = -.8
        val previous = IntArray(n + 1) { -1 }
        for (end in 3..n) for (start in 0..end - 3) {
            val values = offsets.subList(start, end)
            val training = (start until end).filter { it % 2 == 0 }.map { offsets[it] }
            val withheld = (start until end).filter { it % 2 != 0 }
            if (training.size < 2 || withheld.isEmpty()) continue
            val center = training.average()
            if (values.any { abs(it - center) > .55 }) continue
            val candidate = cost[start] + training.sumOf { (it - center).pow(2) } + .8
            if (candidate < cost[end]) { cost[end] = candidate; previous[end] = start }
        }
        if (!cost[n].isFinite()) { rejected("inconsistent_regions:$rate:$offsets"); return null }
        val groups = mutableListOf<IntRange>(); var end = n
        while (end > 0) { val start = previous[end]; if (start < 0) return null; groups.add(start until end); end = start }
        groups.reverse()
        if (groups.size !in 2..8) { rejected("region_count:${groups.size}"); return null }
        fun fittedOffset(group: IntRange) = group.filter { it % 2 == 0 }.map { offsets[it] }.average()
        val regions = mutableListOf(TimingRegion(0, fittedOffset(groups.first())))
        val ref = reference.filter(::dialogue)
        fun overlap(cue: SubtitleCue, shift: Double): Double {
            val start = cue.startSeconds * rate + shift; val endTime = cue.endSeconds * rate + shift
            return ref.sumOf { max(0.0, min(endTime, it.endSeconds) - max(start, it.startSeconds)) } / (endTime - start)
        }
        for (g in 1 until groups.size) {
            cancelled()
            val before = groups[g - 1]; val after = groups[g]
            val a = fittedOffset(before); val b = fittedOffset(after)
            if (abs(a - b) < 1.2) return null
            val lo = (curves[before.last].window.start - a) / rate
            // The first dialogue after an edit may begin INSIDE the first
            // validating scene, after its opening silence. Searching only up
            // to that scene's start excluded the true boundary entirely.
            val next = curves[after.first].window
            val hi = (next.start + next.speech.size.toDouble() / SPEECH_HZ - b) / rate
            val candidates = cues.indices.filter { cues[it].startSeconds in lo..hi }
            if (candidates.size < 2) { rejected("boundary_no_cues"); return null }
            val evidence = candidates.map { i -> if (dialogue(cues[i])) overlap(cues[i], a) - overlap(cues[i], b) else 0.0 }
            val prefix = DoubleArray(evidence.size + 1)
            evidence.indices.forEach { prefix[it + 1] = prefix[it] + evidence[it] }
            val best = (1 until candidates.size).maxBy { prefix[it] }
            val farRival = (1 until candidates.size).filter { abs(it - best) >= 2 }.maxOfOrNull { prefix[it] } ?: Double.NEGATIVE_INFINITY
            // Uncertain edit boundaries and cue order reversals are rejected.
            if (prefix[best] - farRival < .35 || evidence.take(best).sum() < .5 || -evidence.drop(best).sum() < .5) {
                rejected("boundary_ambiguous:${prefix[best] - farRival},${evidence.take(best).sum()},${-evidence.drop(best).sum()}"); return null
            }
            val boundary = candidates[best]
            if (cues[boundary].startSeconds * rate + b < cues[boundary - 1].endSeconds * rate + a - .1) { rejected("cue_order_reversed"); return null }
            regions.add(TimingRegion(boundary, b))
        }
        val score = curves.minOf { it.scores[it.peak] }
        return Result(AudioSubtitleCorrection(regions.first().offset, rate, score, regions, "piecewise"), "verified_piecewise", n, score,
            curves.minOf { it.unique(it.offset(it.peak)) })
    }

    /** Only an uninterrupted, completed cache scan may locate edits in audio.
     * A played/seeked streaming subset cannot turn missing scenes into silence.
     */
    fun completeAudioReference(windows: List<SpeechWindow>): List<SubtitleCue>? {
        val ordered = windows.sortedBy { it.start }
        if (ordered.isEmpty() || ordered.first().start > .02 || ordered.zipWithNext().any { (a, b) ->
                abs(a.start + a.speech.size.toDouble() / SPEECH_HZ - b.start) > .02 }) return null
        val result = mutableListOf<SubtitleCue>()
        ordered.forEach { window ->
            var first = -1
            for (i in 0..window.speech.size) {
                val speech = i < window.speech.size && window.speech[i] >= .5
                if (speech && first < 0) first = i
                if (first >= 0 && (!speech || i - first >= 10 * SPEECH_HZ)) {
                    result.add(SubtitleCue(window.start + first.toDouble() / SPEECH_HZ,
                        window.start + i.toDouble() / SPEECH_HZ, "speech"))
                    first = if (speech) i else -1
                }
            }
        }
        return result
    }

    fun referenceWindows(cues: List<SubtitleCue>, complete: Boolean = true): List<SpeechWindow> {
        val dialogue = cues.filter(::dialogue)
        val end = dialogue.maxOfOrNull { it.endSeconds } ?: return emptyList()
        if (end > 21600) return emptyList()
        val first = if (complete) 0 else ceil((dialogue.minOfOrNull { it.startSeconds } ?: end) / 20).toInt()
        return (first until (end / 20).toInt()).map { block ->
            val start = block * 20.0
            val bits = DoubleArray(20 * SPEECH_HZ)
            dialogue.forEach { cue ->
                val from = floor((cue.startSeconds - start) * SPEECH_HZ).toInt().coerceIn(0, bits.size)
                val to = ceil((cue.endSeconds - start) * SPEECH_HZ).toInt().coerceIn(0, bits.size)
                for (i in from until to) bits[i] = 1.0
            }
            SpeechWindow(start, bits)
        }
    }

    /** Provider/catalogue affiliation alone is never evidence of correct timing. */
    fun verifyReference(reference: List<SubtitleCue>, audio: List<SpeechWindow>, cancelled: () -> Unit = {}): Boolean {
        val result = match(reference, audio, cancelled = cancelled)
        return result.correction?.let { it.regions.isEmpty() && abs(it.offset) <= .65 && abs(it.rate - 1.0) < .0003 && result.score >= .5 } == true
    }
}
