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

/** FFsubsync's speech-presence FFT adapted to one contiguous current exchange.
 * This action estimates an offset only. A previously established rate can be retained,
 * but a short exchange cannot establish timing drift. See packaged MIT notices.
 */
internal object AudioSubtitleAlignment {
    fun matchCurrent(
        cues: List<SubtitleCue>,
        window: SpeechWindow,
        rate: Double = 1.0,
        manualDelay: Double = 0.0,
        checkCancelled: () -> Unit = {},
    ): AudioSubtitleCorrection? {
        checkCancelled()
        if (cues.size < 4 || !window.start.isFinite() || rate !in .95..1.05 || !manualDelay.isFinite()) return null
        val raw = window.speech
        if (raw.size !in 6 * SPEECH_HZ..12 * SPEECH_HZ || raw.any { !it.isFinite() || it !in 0.0..1.0 }) return null
        if (raw.average() !in .12.. .96) return null
        // Suppress isolated 20 ms VAD dropouts; keep the actual dialogue boundaries.
        val a = DoubleArray(raw.size) { i ->
            val left = max(0, i - 3); val right = min(raw.lastIndex, i + 3)
            var sum = 0.0
            for (j in left..right) sum += raw[j]
            sum / (right - left + 1)
        }
        val sum = a.sum()
        val energy = a.sumOf { it * it } - sum * sum / a.size
        if (energy < 8) return null // Continuous speech/silence cannot identify an offset.
        val radius = 120 * SPEECH_HZ
        val start = window.start - 120
        val b = DoubleArray(a.size + radius * 2)
        cues.forEach { cue ->
            checkCancelled()
            if (!cue.startSeconds.isFinite() || !cue.endSeconds.isFinite() || cue.endSeconds <= cue.startSeconds ||
                cue.text.isBlank() || cue.text.trim().startsWith("♪")) return@forEach
            val from = floor((cue.startSeconds * rate + manualDelay - start) * SPEECH_HZ).toInt().coerceIn(0, b.size)
            val to = ceil((cue.endSeconds * rate + manualDelay - start) * SPEECH_HZ).toInt().coerceIn(0, b.size)
            for (i in from until to) b[i] = 1.0
        }
        val cross = convolution(a.reversedArray(), b, checkCancelled)
        val prefix = DoubleArray(b.size + 1)
        b.indices.forEach { prefix[it + 1] = prefix[it] + b[it] }
        val scores = DoubleArray(radius * 2 + 1) { shift ->
            if (shift % 256 == 0) checkCancelled()
            val s = prefix[shift + a.size] - prefix[shift]
            val variance = s - s * s / a.size
            if (variance < 8) -1.0 else (cross[shift + a.size - 1] - sum * s / a.size) / sqrt(energy * variance)
        }
        val peak = scores.indices.maxBy { scores[it] }
        val best = scores[peak]
        if (peak == 0 || peak == scores.lastIndex || best < .48) return null
        // Compare all plausible subtitle positions, not just the currently visible
        // (possibly wrong) line. A nearest-line bias would silently choose bad offsets.
        val rival = scores.indices.filter { abs(it - peak) > SPEECH_HZ }.maxOf { scores[it] }
        if (best - rival < .08) return null
        var left = peak; var right = peak
        while (left > 0 && scores[left - 1] >= best - .02) left--
        while (right < scores.lastIndex && scores[right + 1] >= best - .02) right++
        if (right - left > .8 * SPEECH_HZ) return null
        val offset = (radius - peak).toDouble() / SPEECH_HZ
        return AudioSubtitleCorrection(offset, rate, best.coerceAtMost(1.0))
    }

    internal fun convolution(a: DoubleArray, b: DoubleArray, checkCancelled: () -> Unit = {}): DoubleArray {
        var n = 1
        while (n < a.size + b.size - 1) n *= 2
        val ar = a.copyOf(n); val ai = DoubleArray(n)
        val br = b.copyOf(n); val bi = DoubleArray(n)
        fft(ar, ai, false, checkCancelled); fft(br, bi, false, checkCancelled)
        for (i in 0 until n) {
            val real = ar[i] * br[i] - ai[i] * bi[i]
            ai[i] = ar[i] * bi[i] + ai[i] * br[i]; ar[i] = real
        }
        fft(ar, ai, true, checkCancelled)
        return ar.copyOf(a.size + b.size - 1)
    }
    private fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean, checkCancelled: () -> Unit) {
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
            checkCancelled()
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
