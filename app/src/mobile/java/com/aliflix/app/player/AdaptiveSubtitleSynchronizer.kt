package com.aliflix.app.player

import kotlin.math.*

/** Native speech-presence alignment. Every FFT uses only observed PCM: unknown
 * scenes never become silence. Search and acceptance use different scenes.
 * Scores are correlations, not probabilities. See docs/mobile-audio-sync.md.
 */
internal object AdaptiveSubtitleSynchronizer {
    private const val HZ = 25
    private val markup = Regex("<[^>]*>")
    private val soundDescription = Regex("(?:(?:SOFT|LOUD|LOUDER|DISTANT|BACKGROUND|HOWLING|BUZZING|RINGING|RUMBLING|CHATTERY)\\s+)+(?:WIND|MUSIC|WIRES|VOICES|CONVERSATIONS|FOOTSTEPS|THUNDER|RAIN|BELLS|SIRENS)(?:\\s+AND\\s+.*)?")
    const val MAX_OFFSET = 600.0
    val rates = listOf(1.0, 25.0 / 24.0, 24.0 / 25.0, 25.0 / 23.976,
        23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0)
    data class Result(val correction: AudioSubtitleCorrection? = null, val reason: String,
                      val windows: Int = 0, val score: Double = 0.0, val margin: Double = 0.0)
    private data class Curve(val window: SpeechWindow, val scores: DoubleArray, val radius: Int, val coverage: DoubleArray) {
        val peak by lazy { scores.indices.maxBy { scores[it] } }
        fun offset(index: Int) = (radius - index).toDouble() / HZ
        fun at(offset: Double): Double = scores.getOrElse((radius - offset * HZ).roundToInt()) { -1.0 }
        fun captioned(offset: Double): Boolean = coverage.getOrElse((radius - offset * HZ).roundToInt()) { 0.0 } in .04.. .90
        fun unique(offset: Double, distance: Double = 1.2, radius: Double = Double.POSITIVE_INFINITY): Double {
            val best = at(offset)
            var rival = -1.0
            for (i in scores.indices) if (abs(this.offset(i) - offset) > distance && abs(this.offset(i) - offset) <= radius)
                rival = max(rival, scores[i])
            return best - rival
        }
        fun nearPeakOffset(offset: Double): Double {
            val center = (radius - offset * HZ).roundToInt()
            val first = (center - 2 * HZ).coerceAtLeast(0)
            val last = (center + 2 * HZ).coerceAtMost(scores.lastIndex)
            return offset((first..last).maxBy { scores[it] })
        }
    }

    fun dialogue(cue: SubtitleCue): Boolean {
        val text = cue.text.replace(markup, "").trim()
        return cue.startSeconds.isFinite() && cue.endSeconds.isFinite() && cue.startSeconds >= 0 &&
            cue.endSeconds > cue.startSeconds && cue.endSeconds - cue.startSeconds <= 15 &&
            text.any(Char::isLetter) && !text.startsWith("♪") && !text.startsWith("♫") &&
            !(text.startsWith("[") && text.endsWith("]")) && !soundDescription.matches(text)
    }

    private fun smooth(raw: DoubleArray, radius: Int = 2): DoubleArray {
        val prefix = DoubleArray(raw.size + 1)
        raw.indices.forEach { prefix[it + 1] = prefix[it] + raw[it] }
        return DoubleArray(raw.size) { i ->
            val a = max(0, i - radius); val b = min(raw.size, i + radius + 1)
            (prefix[b] - prefix[a]) / (b - a)
        }
    }

    private fun phrase(raw: DoubleArray): DoubleArray {
        val out = raw.copyOf()
        var gap = -1
        for (i in out.indices) {
            if (out[i] < .5 && gap < 0) gap = i
            if (out[i] >= .5 && gap >= 0) {
                // Captions span phrases; phonetic pauses within them are not
                // missing dialogue. Close at most 320 ms, never scene silence.
                if (gap > 0 && i - gap <= 8) for (j in gap until i) out[j] = 1.0
                gap = -1
            }
        }
        return out
    }

    private fun curve(cues: List<SubtitleCue>, window: SpeechWindow, rate: Double,
                      cancelled: () -> Unit, radiusSeconds: Double = MAX_OFFSET): Curve {
        cancelled()
        val a = smooth(phrase(DoubleArray(window.speech.size / 2) { (window.speech[it * 2] + window.speech[it * 2 + 1]) / 2 }))
        val radius = (radiusSeconds * HZ).toInt()
        val start = window.start - radiusSeconds
        val raw = DoubleArray(a.size + 2 * radius)
        cues.forEach { cue ->
            run {
                val from = floor((cue.startSeconds * rate - start) * HZ).toInt().coerceIn(0, raw.size)
                val to = ceil((cue.endSeconds * rate - start) * HZ).toInt().coerceIn(0, raw.size)
                for (i in from until to) raw[i] = 1.0
            }
        }
        val b = smooth(phrase(raw))
        val sum = a.sum(); val energy = a.sumOf { it * it } - sum * sum / a.size
        val cross = AudioSubtitleAlignment.convolution(a.reversedArray(), b, cancelled)
        val prefix = DoubleArray(b.size + 1); val squared = DoubleArray(b.size + 1)
        b.indices.forEach { prefix[it + 1] = prefix[it] + b[it]; squared[it + 1] = squared[it] + b[it] * b[it] }
        val coverage = DoubleArray(2 * radius + 1)
        val scores = DoubleArray(2 * radius + 1) { shift ->
            if (shift % 256 == 0) cancelled()
            val s = prefix[shift + a.size] - prefix[shift]
            coverage[shift] = s / a.size
            val variance = squared[shift + a.size] - squared[shift] - s * s / a.size
            if (energy < 4 || variance < 4) -1.0 else
                ((cross[shift + a.size - 1] - sum * s / a.size) / sqrt(energy * variance)).coerceIn(-1.0, 1.0)
        }
        return Curve(window, scores, radius, coverage)
    }

    private fun usable(windows: List<SpeechWindow>, quick: Boolean = false): List<SpeechWindow> {
        if (quick) {
            // Split KNOWN runs, not a fixed media grid. A cold stream can supply
            // independent fitting/verification samples within eighteen seconds.
            // Longer history retains larger scenes and widely separated anchors.
            val duration = windows.sumOf { it.speech.size }.toDouble() / SPEECH_HZ
            var seconds = if (duration >= 60) 20 else if (duration >= 36) 12 else 6
            if (windows.sumOf { it.speech.size / (seconds * SPEECH_HZ) } < 3) seconds = 6
            val size = seconds * SPEECH_HZ
            return windows.sortedBy { it.start }.flatMap { run ->
                (0 until run.speech.size / size).map { i ->
                    SpeechWindow(run.start + i * seconds, run.speech.copyOfRange(i * size, (i + 1) * size))
                }
            }.filter { window ->
                val envelope = smooth(phrase(DoubleArray(window.speech.size / 2) {
                    (window.speech[it * 2] + window.speech[it * 2 + 1]) / 2
                }))
                window.start.isFinite() && window.start >= 0 && window.speech.all { it.isFinite() && it in 0.0..1.0 } &&
                    window.speech.average() in .04.. .96 &&
                    (1 until window.speech.size).count { abs(window.speech[it] - window.speech[it - 1]) > .4 } >= 2 &&
                    envelope.sumOf { it * it } - envelope.sum().pow(2) / envelope.size >= 4
            }.fold(mutableListOf()) { scenes, window ->
                if (scenes.isEmpty() || window.start >= scenes.last().start + scenes.last().speech.size.toDouble() / SPEECH_HZ - .02)
                    scenes.add(window)
                scenes
            }
        }
        return windows.sortedBy { it.start }
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
            }
    }

    fun match(cues: List<SubtitleCue>, windows: List<SpeechWindow>,
              completeReference: List<SubtitleCue>? = null, cancelled: () -> Unit = {}): Result =
        align(cues, windows, completeReference, false, cancelled)

    fun matchQuick(cues: List<SubtitleCue>, windows: List<SpeechWindow>,
                   completeReference: List<SubtitleCue>? = null, cancelled: () -> Unit = {}): Result =
        align(cues, windows, completeReference, true, cancelled)

    fun matchCurrent(cues: List<SubtitleCue>, window: SpeechWindow, cancelled: () -> Unit = {}): Result =
        align(cues, listOf(window), null, true, cancelled, offsetOnly = true)

    private fun align(cues: List<SubtitleCue>, windows: List<SpeechWindow>,
                      completeReference: List<SubtitleCue>?, quick: Boolean, cancelled: () -> Unit,
                      offsetOnly: Boolean = false): Result {
        if (cues.size !in 4..20000 || cues.any { !it.startSeconds.isFinite() || !it.endSeconds.isFinite() || it.endSeconds > 21600 })
            return Result(reason = "invalid_target_timeline")
        val allObserved = usable(windows, quick)
        val signal = cues.filter(::dialogue)
        // Bound expensive hypothesis fitting, not independent verification.
        val usable = if (allObserved.size <= 18) allObserved else
            (0 until 18).map { allObserved[it * (allObserved.size - 1) / 17] }
        val additional = allObserved.filterNot { scene -> usable.any { it === scene } }
        if (usable.size < 3) return Result(reason = "insufficient_independent_windows", windows = usable.size)
        val isTraining: (Int) -> Boolean = { if (quick) it % 3 != 2 else it % 2 == 0 }
        val training = usable.filterIndexed { i, _ -> isTraining(i) }
        val validation = usable.filterIndexed { i, _ -> !isTraining(i) }
        val span = usable.last().start - usable.first().start
        val shortScenes = usable.any { it.speech.size < 20 * SPEECH_HZ }
        data class Proposal(val rate: Double, val offset: Double, val score: Double, val margin: Double, val curves: List<Curve>)
        val proposals = mutableListOf<Proposal>()
        val rejected = mutableListOf<String>()
        val searched = mutableMapOf<Double, List<Curve>>()
        val candidateRates = (if (offsetOnly) listOf(1.0) else rates).toMutableList()
        var index = 0
        while (index < candidateRates.size) {
            val rate = candidateRates[index++]
            // Small FPS differences need enough elapsed media to distinguish them.
            if (rate != 1.0 && (usable.size < 5 || span * abs(rate - 1.0) < 1.0)) continue
            val curves = training.map { curve(signal, it, rate, cancelled) }
            searched[rate] = curves
            val aggregate = DoubleArray(curves.first().scores.size) { shift ->
                if (shift % 256 == 0) cancelled()
                var sum = 0.0; var count = 0
                for (candidate in curves) if (!quick || candidate.coverage[shift] in .04.. .90) {
                    sum += candidate.scores[shift]; count++
                }
                // An interval with no captions is not timing evidence: films
                // contain music and vocal effects that even neural VAD detects.
                // At least two independent captioned fitting scenes are needed.
                if (count < 2) -1.0 else sum / count
            }
            val peak = aggregate.indices.maxBy { aggregate[it] }
            if (peak == 0 || peak == aggregate.lastIndex) continue
            val offset = curves.first().offset(peak)
            var rival = -1.0
            for (i in aggregate.indices) if (abs(i - peak) > 1.2 * HZ) rival = max(rival, aggregate[i])
            val score = aggregate[peak]; val margin = score - rival
            val supported = if (quick) curves.filter { it.captioned(offset) } else curves
            // Short patterns have more accidental matches. Increase acceptance,
            // never relax confidence merely to satisfy the interaction deadline.
            if (score >= (if (shortScenes) .6 else .46) && margin >= (if (shortScenes) .10 else .055) &&
                supported.all { it.at(offset) >= (if (shortScenes) .5 else .35) })
                proposals.add(Proposal(rate, offset, score, margin, supported))
            else rejected.add("weak_fit:$rate:$offset:$score:$margin:${curves.map { it.at(offset) }}")
            if (!offsetOnly && rate == 1.0 && usable.size >= 5 && span >= 60) {
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
            val held = validation.map { curve(signal, it, proposal.rate, cancelled) }
                .filter { !quick || it.captioned(proposal.offset) }
            if (held.isEmpty()) { rejected.add("no_captioned_verification_scene"); continue }
            // Fitting establishes movie-wide uniqueness. A withheld short scene
            // checks that already proposed clock locally; asking each six-second
            // phrase to identify a whole film again discards correct joint fits.
            if (held.any { it.at(proposal.offset) < (if (shortScenes) .6 else .46) ||
                    it.unique(proposal.offset, radius = if (quick) 2.0 else Double.POSITIVE_INFINITY) < .04 ||
                    abs((if (quick) it.nearPeakOffset(proposal.offset) else it.offset(it.peak)) - proposal.offset) > .6 }) {
                rejected.add("held:${proposal.rate}:${held.map { listOf(it.at(proposal.offset), it.nearPeakOffset(proposal.offset)) }}")
                continue
            }
            // Hand-authored captions can span a pause beyond the spoken phrase.
            // Preserve a strong joint clock when a fitting scene has a broad
            // plateau; the withheld later scenes still require tight agreement.
            if (proposal.curves.any { curve ->
                val peak = if (quick) curve.nearPeakOffset(proposal.offset) else curve.offset(curve.peak)
                abs(peak - proposal.offset) > .65 && (!quick || curve.at(proposal.offset) < .70 || curve.at(peak) - curve.at(proposal.offset) > .12)
            }) {
                rejected.add("fitting_scene_disagreement:${proposal.rate}:${proposal.offset}:${proposal.curves.map { listOf(it.window.start, it.at(proposal.offset), it.nearPeakOffset(proposal.offset), it.at(it.nearPeakOffset(proposal.offset)) - it.at(proposal.offset)) }}"); continue
            }
            if (proposal.rate == 1.0) {
                // All these curves independently support this same candidate;
                // inspect their measured peaks for evidence against its slope.
                val observed = proposal.curves + held
                val x = observed.map { it.window.start }
                val y = observed.map { if (quick) it.nearPeakOffset(proposal.offset) else it.offset(it.peak) }
                val xm = x.average(); val ym = y.average()
                val denominator = x.sumOf { (it - xm).pow(2) }
                val slope = if (denominator > 0) x.indices.sumOf { (x[it] - xm) * (y[it] - ym) } / denominator else 0.0
                val residual = x.indices.maxOf { abs(y[it] - ym - slope * (x[it] - xm)) }
                // A short, consistently sloping sequence is evidence AGAINST a
                // constant offset even when it cannot yet verify a whole-film
                // drift model. Keep collecting instead of caching the wrong model.
                val observedDrift = abs(slope) * (x.max() - x.min())
                val projectedDrift = abs(slope) * cues.maxOf { it.endSeconds }
                val drifting = if (quick) observedDrift >= .75 && residual <= .08
                    else residual <= .15 && (observedDrift >= .5 || (observedDrift >= .16 && projectedDrift >= 1.0))
                if (drifting) {
                    rejected.add("unverified_drift:$observedDrift:$projectedDrift:$residual"); continue
                }
            }
            if (proposal.rate !in rates) {
                // A fitted slope is only safe for the WHOLE target when observed
                // scatter also bounds extrapolation, rather than merely fitting
                // the three scenes used to estimate it.
                val anchors = (proposal.curves + held).map { if (quick) it.nearPeakOffset(proposal.offset) else it.offset(it.peak) }
                val scatter = anchors.max() - anchors.min() + .08
                val extent = cues.maxOf { it.endSeconds } * proposal.rate
                if (scatter * max(1.0, extent / span) > 1.0) continue
            }
            val competing = proposals.any { other ->
                other !== proposal && other.score >= proposal.score - .03 &&
                    (usable.any { w -> abs((w.start - proposal.offset) / proposal.rate * other.rate + other.offset - w.start) > .8 } ||
                        listOf(cues.first().startSeconds, cues.maxOf { it.endSeconds }).any { time ->
                            abs(time * proposal.rate + proposal.offset - time * other.rate - other.offset) > .8
                        })
            }
            if (competing) return Result(reason = "ambiguous_timing_models", windows = usable.size)
            val score = min(proposal.score, held.map { it.at(proposal.offset) }.average())
            val correction = AudioSubtitleCorrection(proposal.offset, proposal.rate, score)
            if (!verifyAdditional(cues, correction, additional, cancelled, quick)) continue
            return Result(correction, "verified_${if (proposal.rate == 1.0) "offset" else "drift"}",
                usable.size, score, min(proposal.margin, held.minOf {
                    it.unique(proposal.offset, radius = if (quick) 2.0 else Double.POSITIVE_INFINITY)
                }))
        }
        // Edits cannot be located inside unobserved scenes. Only a complete
        // verified subtitle reference permits assigning every cue to a region.
        if (completeReference != null && usable.size >= 6) {
            val rejected = mutableListOf<String>()
            val pieces = searched.mapNotNull { (rate, fitted) ->
                var train = 0
                val local = usable.mapIndexed { index, window ->
                    if (isTraining(index)) fitted[train++] else curve(signal, window, rate, cancelled)
                }
                val candidate = split(cues, completeReference, local, rate, cancelled, isTraining) { rejected.add(it) }
                if (candidate != null && !verifyAdditional(cues, candidate.correction!!, additional, cancelled, quick)) {
                    rejected.add("known_scene_disagreement"); null
                } else candidate
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
        return Result(reason = "inconsistent_or_ambiguous_evidence:${rejected.take(3).joinToString(";")}", windows = usable.size,
            score = proposals.maxOfOrNull { it.score } ?: 0.0)
    }

    private fun verifyAdditional(cues: List<SubtitleCue>, correction: AudioSubtitleCorrection,
                                 windows: List<SpeechWindow>, cancelled: () -> Unit, quick: Boolean = false): Boolean {
        if (windows.isEmpty()) return true
        val corrected = correction.apply(cues).filter(::dialogue)
        // A small local FFT checks the already proposed clock on every remaining
        // known scene. This cannot vote away contradictions or fit another offset.
        return windows.all { window ->
            val local = curve(corrected, window, 1.0, cancelled, radiusSeconds = 1.6)
            (quick && !local.captioned(0.0)) || (local.at(0.0) >= .35 && abs(local.offset(local.peak)) <= .65)
        }
    }

    private fun split(cues: List<SubtitleCue>, reference: List<SubtitleCue>, curves: List<Curve>, rate: Double, cancelled: () -> Unit,
                      isTraining: (Int) -> Boolean,
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
            val training = (start until end).filter(isTraining).map { offsets[it] }
            val withheld = (start until end).filterNot(isTraining)
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
        fun fittedOffset(group: IntRange) = group.filter(isTraining).map { offsets[it] }.average()
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

    /** A current-dialogue proposal cannot override contradictory earlier audio.
     * Recent phrases have their own independent word verification; only earlier
     * informative windows are checked here, using the already proposed clock.
     */
    fun verifyEarlierClock(cues: List<SubtitleCue>, correction: AudioSubtitleCorrection,
                           audio: List<SpeechWindow>, position: Double, cancelled: () -> Unit = {}): Boolean =
        verifyAdditional(cues, correction, usable(audio, true).filter {
            it.start + it.speech.size.toDouble() / SPEECH_HZ <= position - 30
        }, cancelled, quick = true)

    /** Provider/catalogue affiliation alone is never evidence of correct timing. */
    fun verifyReference(reference: List<SubtitleCue>, audio: List<SpeechWindow>, cancelled: () -> Unit = {}): Boolean {
        val result = match(reference, audio, cancelled = cancelled)
        return result.correction?.let { it.regions.isEmpty() && abs(it.offset) <= .65 && abs(it.rate - 1.0) < .0003 && result.score >= .5 } == true
    }

    /** Test a supplied clock directly. Reference verification must not search for
     * another movie-wide offset or fit a rate that could make a wrong file agree.
     * Full timeline fitting remains independent of these selected-audio checks.
     */
    fun verifyReferenceQuick(reference: List<SubtitleCue>, audio: List<SpeechWindow>, cancelled: () -> Unit = {}): Boolean {
        val scenes = usable(audio, true)
        if (scenes.size < 3) return false
        val signal = reference.filter(::dialogue)
        if (signal.size < 4) return false
        val checks = scenes.map { scene ->
            cancelled()
            curve(signal, scene, 1.0, cancelled, radiusSeconds = 2.0)
        }
        val captioned = checks.filter { it.captioned(0.0) }
        return captioned.size >= 3 && captioned.all { local ->
            local.at(0.0) >= (if (local.window.speech.size < 20 * SPEECH_HZ) .55 else .46) &&
                (abs(local.offset(local.peak)) <= .65 || (local.at(0.0) >= .70 && local.at(local.offset(local.peak)) - local.at(0.0) <= .12))
        }
    }
}
