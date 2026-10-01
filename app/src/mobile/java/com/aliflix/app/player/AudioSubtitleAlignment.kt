package com.aliflix.app.player

import kotlin.math.*

internal const val SPEECH_HZ = 50
internal data class SpeechWindow(val start: Double, val speech: DoubleArray)
internal data class AudioSubtitleCorrection(val offset: Double, val rate: Double, val confidence: Double) {
    fun apply(cues: List<SubtitleCue>, manualDelay: Double = 0.0): List<SubtitleCue> = cues.mapNotNull {
        val end = it.endSeconds * rate + offset + manualDelay
        if (end <= 0) null else it.copy(startSeconds = max(0.0, it.startSeconds * rate + offset + manualDelay), endSeconds = end)
    }
}

/** FFsubsync speech-presence cross-correlation adapted for sparse passive playback samples.
 * Native radix-2 FFT; no words, languages, audio requests or audio retained on disk. See MIT notices.
 */
internal object AudioSubtitleAlignment {
    private val rates = doubleArrayOf(1.0, 25.0 / 24, 24.0 / 25, 25.0 / 23.976, 23.976 / 25, 24.0 / 23.976, 23.976 / 24)
    fun match(cues: List<SubtitleCue>, windows: List<SpeechWindow>): AudioSubtitleCorrection? {
        if (cues.size < 12 || windows.size < 2) return null
        val samples = windows.sortedBy { it.start }.take(6)
        if (samples.sumOf { it.speech.size } < 20 * SPEECH_HZ ||
            samples.zipWithNext().any { (a, b) -> a.start + a.speech.size.toDouble() / SPEECH_HZ > b.start + .01 }) return null
        val span = samples.last().start - samples.first().start
        val observedSpan = span + samples.last().speech.size.toDouble() / SPEECH_HZ
        val models = rates.toList().mapNotNull { rate ->
            // Even a short baseline can expose a likely frame-rate mismatch: assess it to
            // reject a tempting constant offset, but wait for a longer baseline to apply it.
            if (rate != 1.0 && abs(rate - 1) * observedSpan < .7) return@mapNotNull null
            val correlations = samples.map { correlations(cues, it, rate) ?: return@mapNotNull null }
            val combined = DoubleArray(correlations.first().size) { i ->
                val sum = correlations.sumOf { it[i] }
                // One poor intro/music sample must not poison later dialogue forever.
                // A trimmed fit needs at least three independent agreeing scenes.
                if (correlations.size >= 4) (sum - correlations.minOf { it[i] }) / (correlations.size - 1)
                else sum / correlations.size
            }
            val bestIndex = combined.indices.maxBy { combined[it] }
            if (bestIndex == 0 || bestIndex == combined.lastIndex) return@mapNotNull null
            val alternative = combined.indices.filter { abs(it - bestIndex) > SPEECH_HZ }.maxOf { combined[it] }
            if (combined[bestIndex] - alternative < .06) return@mapNotNull null
            val rejected = correlations.filter { it[bestIndex] < .40 || it.max() - it[bestIndex] > .07 }
            if (rejected.isNotEmpty() && (correlations.size < 4 || rejected.size > 1)) return@mapNotNull null
            // Strong contradictory evidence suggests another cut/episode, not noise.
            if (rejected.any { it.max() >= .60 && it.max() - it[bestIndex] > .07 }) return@mapNotNull null
            val offset = (120 * SPEECH_HZ - bestIndex).toDouble() / SPEECH_HZ
            val score = combined[bestIndex]
            if (score < .48) null else AudioSubtitleCorrection(offset, rate, score)
        }.sortedByDescending { it.confidence }
        val best = models.firstOrNull() ?: return null
        if (best.rate != 1.0 && (span < 120 || abs(best.rate - 1) * span < 2)) return null
        if (models.drop(1).any { best.confidence - it.confidence < .035 &&
                (abs(best.offset - it.offset + samples.first().start * (best.rate - it.rate)) > .5 ||
                    abs(best.rate - it.rate) * observedSpan > .8) }) return null
        return best
    }

    private fun correlations(cues: List<SubtitleCue>, window: SpeechWindow, rate: Double): DoubleArray? {
        val a = window.speech
        if (a.size < 500 || a.any { !it.isFinite() || it !in 0.0..1.0 }) return null
        val sum = a.sum()
        if (sum / a.size !in .12.. .90) return null
        val radius = 120 * SPEECH_HZ
        val start = window.start - 120
        val b = DoubleArray(a.size + radius * 2)
        cues.forEach { cue ->
            if (cue.text.isBlank() || cue.text.trim().startsWith("♪")) return@forEach
            val from = floor((cue.startSeconds * rate - start) * SPEECH_HZ).toInt().coerceIn(0, b.size)
            val to = ceil((cue.endSeconds * rate - start) * SPEECH_HZ).toInt().coerceIn(0, b.size)
            for (i in from until to) b[i] = 1.0
        }
        val cross = convolution(a.reversedArray(), b)
        val prefix = DoubleArray(b.size + 1)
        b.indices.forEach { prefix[it + 1] = prefix[it] + b[it] }
        val energy = a.sumOf { it * it } - sum * sum / a.size
        if (energy < 1) return null
        val scores = DoubleArray(radius * 2 + 1) { shift ->
            val s = prefix[shift + a.size] - prefix[shift]
            val variance = s - s * s / a.size
            if (variance < 1) -1.0 else (cross[shift + a.size - 1] - sum * s / a.size) / sqrt(energy * variance)
        }
        return scores
    }

    internal fun convolution(a: DoubleArray, b: DoubleArray): DoubleArray {
        var n = 1
        while (n < a.size + b.size - 1) n *= 2
        val ar = a.copyOf(n); val ai = DoubleArray(n)
        val br = b.copyOf(n); val bi = DoubleArray(n)
        fft(ar, ai, false); fft(br, bi, false)
        for (i in 0 until n) {
            val real = ar[i] * br[i] - ai[i] * bi[i]
            ai[i] = ar[i] * bi[i] + ai[i] * br[i]; ar[i] = real
        }
        fft(ar, ai, true)
        return ar.copyOf(a.size + b.size - 1)
    }
    private fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val v = imag[i]; imag[i] = imag[j]; imag[j] = v
            }
        }
        var length = 2
        while (length <= n) {
            val angle = (if (inverse) 2 else -2) * PI / length
            val wr = cos(angle); val wi = sin(angle)
            for (base in 0 until n step length) {
                var xr = 1.0; var xi = 0.0
                for (k in 0 until length / 2) {
                    val u = base + k; val v = u + length / 2
                    val r = real[v] * xr - imag[v] * xi; val im = real[v] * xi + imag[v] * xr
                    real[v] = real[u] - r; imag[v] = imag[u] - im
                    real[u] += r; imag[u] += im
                    val next = xr * wr - xi * wi; xi = xr * wi + xi * wr; xr = next
                }
            }
            length *= 2
        }
        if (inverse) for (i in 0 until n) { real[i] /= n; imag[i] /= n }
    }
}
