package com.aliflix.app.player

import kotlin.math.*

internal const val SPEECH_HZ = 50
internal data class SpeechWindow(val start: Double, val speech: DoubleArray)
internal data class TimingRegion(val firstCue: Int, val offset: Double)
internal data class AudioSubtitleCorrection(
    val offset: Double, val rate: Double, val confidence: Double,
    val regions: List<TimingRegion> = emptyList(),
    val model: String = if (rate == 1.0) "offset" else "drift",
) {
    fun apply(cues: List<SubtitleCue>, manualDelay: Double = 0.0): List<SubtitleCue> = cues.mapIndexedNotNull { index, cue ->
        val shift = regions.lastOrNull { it.firstCue <= index }?.offset ?: offset
        val end = cue.endSeconds * rate + shift + manualDelay
        val start = max(0.0, cue.startSeconds * rate + shift + manualDelay)
        if (end <= start) null else cue.copy(startSeconds = start, endSeconds = end)
    }
}

/** Cancellable native FFT primitive shared by the adaptive synchroniser.
 * Timing hypotheses and acceptance live in AdaptiveSubtitleSynchronizer.
 */
internal object AudioSubtitleAlignment {
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
