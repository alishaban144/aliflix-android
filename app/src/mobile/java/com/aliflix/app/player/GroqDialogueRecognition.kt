package com.aliflix.app.player

import com.aliflix.app.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A user-requested fallback through the existing backend. Audio only: never
 * send media URLs, subtitles, titles, cookies, or account credentials. No model
 * or API key is shipped in the APK. Each sample keeps its original media clock. */
internal object GroqDialogueRecognition {
    private val executor = Executors.newFixedThreadPool(2) {
        Thread(it, "aliflix-dialogue-recovery").apply { isDaemon = true }
    }

    suspend fun recognize(pcm: PlayedDialoguePcm, speech: List<SpeechWindow>, onWords: (List<List<HeardWord>>) -> Unit) = supervisorScope {
        val collected = mutableListOf<List<HeardWord>>()
        dialogueRecoverySamples(pcm, speech).map { sample -> launch {
            try {
                val result = withTimeoutOrNull(7_000) { read(sample) }.orEmpty()
                if (result.isNotEmpty()) { collected.add(result); onWords(collected.toList()) }
                android.util.Log.i("AliflixAudioSync", "groq_dialogue:words=${result.size},start=${sample.start}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.i("AliflixAudioSync", "groq_dialogue_unavailable:${error.javaClass.simpleName}")
            }
        } }.joinAll()
    }

    private suspend fun read(sample: PlayedDialoguePcm): List<HeardWord> = suspendCancellableCoroutine { continuation ->
        val connection = (URL(BuildConfig.RECOMMENDATION_AI_BASE_URL.trimEnd('/') + "/v3/subtitles/audio-timing")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 3_000; readTimeout = 6_500
            setRequestProperty("Content-Type", "audio/wav")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Aliflix/${BuildConfig.VERSION_NAME}")
        }
        val task = executor.submit {
            try {
                if (!continuation.isActive) return@submit
                val body = dialogueWav(sample, normalizeQuietAudio = sample.frames.size < 1_000)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                check(connection.responseCode == 200)
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4_096)
                    while (output.size() <= 65_536 && continuation.isActive) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                check(bytes.size <= 65_536)
                val words = parseGroqDialogue(JSONObject(bytes.toString(Charsets.UTF_8)), sample)
                if (continuation.isActive) continuation.resume(words)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { connection.disconnect() }
        }
        continuation.invokeOnCancellation { task.cancel(true); connection.disconnect() }
    }
}

/** Choose one contiguous clip using already measured speech, before uploading.
 * Completed exchanges outrank a quiet/music tail; equally useful clips favor
 * recent dialogue. Speech never supplies an expected subtitle offset. */
internal fun dialogueRecoverySample(pcm: PlayedDialoguePcm, speech: List<SpeechWindow>): PlayedDialoguePcm? {
    if (pcm.frames.size < 200) return null
    val length = minOf(1_000, pcm.frames.size)
    val latest = pcm.frames.size - length
    val decisions = DoubleArray(pcm.frames.size) { -1.0 }
    for (window in speech) for (i in window.speech.indices) {
        val frame = kotlin.math.round((window.start + i.toDouble() / SPEECH_HZ - pcm.start) / .02).toInt()
        if (frame in decisions.indices) decisions[frame] = window.speech[i]
    }
    fun score(start: Int): Double {
        var spoken = 0; var known = 0; var run = 0; var phrases = 0
        var first = -1; var last = -1
        fun finish(at: Int) {
            if (run in 20..450) { phrases++; if (first < 0) first = at - run; last = at }
            run = 0
        }
        for (i in start until start + length) {
            if (decisions[i] >= 0) known++
            if (decisions[i] >= .5) { spoken++; run++ } else finish(i)
        }
        finish(start + length)
        if (known < length * .8 || spoken < 150 || spoken > length * .85 || phrases < 2 || last - first < 350) return 0.0
        return minOf(phrases, 8) * 2.0 + minOf(spoken / 50.0, 10.0)
    }
    var best = latest; var bestScore = score(latest)
    // Two-second sampling bounds scoring work to sixty short windows.
    for (start in latest downTo 0 step 100) {
        val candidate = score(start)
        if (candidate > bestScore) { best = start; bestScore = candidate }
    }
    return PlayedDialoguePcm(pcm.start + best * .02, pcm.frames.subList(best, best + length))
}

/** Two network slots, at most six clips and 120 seconds including overlaps.
 * Shift the strongest exchange once to avoid an ASR segment cutting a word;
 * keep every response independent instead of concatenating conflicting words. */
internal fun dialogueRecoverySamples(pcm: PlayedDialoguePcm, speech: List<SpeechWindow>): List<PlayedDialoguePcm> {
    val first = dialogueRecoverySample(pcm, speech) ?: return emptyList()
    val length = first.frames.size
    val latest = pcm.frames.size - length
    val best = kotlin.math.round((first.start - pcm.start) / .02).toInt().coerceIn(0, latest)
    // Look both before and after the best detector window. Moving only
    // forward can repeatedly crop the same opening words from a sentence.
    val broad = listOf(best, (best - 100).coerceAtLeast(0), (best + 100).coerceAtMost(latest)).distinct().map { start ->
        PlayedDialoguePcm(pcm.start + start * .02, pcm.frames.subList(start, start + length))
    }
    // Short earlier dialogue may be swallowed by a long musical ASR segment.
    // Bound a separate clip around measured speech, rather than spending the
    // remaining requests on the quiet tail. Only actual PCM supplies its clock.
    val decisions = DoubleArray(pcm.frames.size) { -1.0 }
    for (window in speech) for (i in window.speech.indices) {
        val at = kotlin.math.round((window.start + i.toDouble() / SPEECH_HZ - pcm.start) / .02).toInt()
        if (at in decisions.indices) decisions[at] = window.speech[i]
    }
    val phrases = mutableListOf<IntRange>()
    var begin = -1
    for (i in 0..decisions.size) {
        if (i < decisions.size && decisions[i] >= .5) { if (begin < 0) begin = i }
        else if (begin >= 0) {
            if (i - begin in 20..450) {
                val previous = phrases.lastOrNull()
                if (previous != null && begin - previous.last <= 100 && i - previous.first <= 850)
                    phrases[phrases.lastIndex] = previous.first until i
                else phrases.add(begin until i)
            }
            begin = -1
        }
    }
    val separate = phrases.filter { range ->
        range.count { decisions[it] >= .5 } >= 75 && kotlin.math.abs((range.first + range.last) / 2 - best - length / 2) >= 1_500
    }.maxByOrNull { range ->
        // Favor separation for independently checking an edition rate, while
        // retaining enough actual speech to prevent a music-only request.
        kotlin.math.abs((range.first + range.last) / 2 - best - length / 2)
    }
    val focused = separate?.let { range ->
        val from = (range.first - 75).coerceIn(0, pcm.frames.size - 200)
        val until = maxOf(range.last + 76, from + 200).coerceAtMost(pcm.frames.size)
        listOf(from, (from - 30).coerceAtLeast(0), (from + 30).coerceAtMost(until - 200)).distinct().map { start ->
            PlayedDialoguePcm(pcm.start + start * .02, pcm.frames.subList(start, minOf(until, start + 1_000)))
        }
    }.orEmpty()
    val fallback = (listOf(latest, 0) + (latest downTo 0 step length).toList()).map { start ->
        PlayedDialoguePcm(pcm.start + start * .02, pcm.frames.subList(start, start + length))
    }
    return (broad + focused + fallback).distinctBy { it.start }.take(6)
}

internal fun dialogueWav(sample: PlayedDialoguePcm, normalizeQuietAudio: Boolean = false): ByteArray {
    require(sample.start.isFinite() && sample.frames.size in 200..1_000 && sample.frames.all { it.size == 320 })
    val dataSize = sample.frames.size * 640
    val peak = if (normalizeQuietAudio) sample.frames.maxOf { frame -> frame.maxOf { kotlin.math.abs(it.toInt()) } } else 0
    // A bounded gain on a private recognition copy helps quiet dialogue; never
    // amplify beyond four times, clip, or change the actual playback samples.
    val gain = if (peak > 0) (.85 * Short.MAX_VALUE / peak).coerceIn(1.0, 4.0) else 1.0
    return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataSize)
        sample.frames.forEach { frame -> frame.forEach { putShort((it * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()) } }
    }.array()
}

internal fun parseGroqDialogue(json: JSONObject, sample: PlayedDialoguePcm): List<HeardWord> {
    val duration = sample.frames.size * .02
    val rows = json.optJSONArray("words") ?: return emptyList()
    val recognized = (0 until minOf(rows.length(), 240)).mapNotNull { index ->
        val word = rows.optJSONObject(index) ?: return@mapNotNull null
        val text = word.optString("text").trim()
        val start = word.optDouble("start", Double.NaN); val end = word.optDouble("end", Double.NaN)
        if (text.isBlank() || text.length > 80 || !start.isFinite() || !end.isFinite() ||
            start < 0 || end <= start || end > duration || end - start > 2 && start >= .4) null
        else {
            // Keep a cropped/padded opening word as lexical context only. Its
            // unknown onset cannot certify a clock, but dropping it can hide a
            // complete phrase whose later word endings were actually measured.
            val ending = end <= duration - .4 && end - start <= 2
            HeardWord(text, sample.start + start, sample.start + end,
                startReliable = start >= .4 && end - start <= 2,
                endReliable = ending, measuredEnd = ending)
        }
    }
    // Whisper sometimes pads a word ending beyond the next measured onset.
    // Bound that overlap by the onset already measured in this same sample;
    // never invent a missing ending or join different transcripts here.
    val normalized = mutableListOf<HeardWord>()
    recognized.forEachIndexed { index, word ->
        val next = recognized.getOrNull(index + 1)
        val end = if (next != null && next.start > word.start && next.start < word.end) next.start else word.end
        val previous = normalized.lastOrNull()
        val regressed = previous != null && word.start < previous.start
        val start = if (regressed) previous!!.end else word.start
        // Keep lexical order. An overlapping backward onset is not measured
        // evidence: only a later reliable phrase ending may certify its clock.
        normalized.add(word.copy(start = start, end = maxOf(start, end),
            playedThrough = end, startReliable = word.startReliable && !regressed,
            endReliable = word.endReliable && end > start, measuredEnd = word.measuredEnd && end > start))
    }
    return normalized
}
