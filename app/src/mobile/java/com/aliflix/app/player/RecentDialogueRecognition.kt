package com.aliflix.app.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal data class HeardWord(val text: String, val start: Double, val end: Double, val endReliable: Boolean = true,
                              val playedThrough: Double = end, val measuredEnd: Boolean = false, val startReliable: Boolean = true)

/** A terminal token cannot expose decode-ahead: wait until the actual PCM
 * supplied for its completed recognition segment has passed the playhead. */
internal fun playedRecognitionWords(words: List<HeardWord>, position: Double): List<HeardWord> =
    words.filter { it.playedThrough <= position }

/** Android reports word onsets, not word durations. Keep a terminal token for
 * lexical matching, explicitly marking its unknown end; never manufacture an
 * ending that could certify a subtitle offset. */
@androidx.annotation.RequiresApi(34)
internal fun recognizedDialogueWords(parts: List<RecognitionPart>, start: Double, playedEnd: Double): List<HeardWord> =
    parts.mapIndexedNotNull { index, part ->
        val from = start + part.timestampMillis / 1000.0
        val next = parts.getOrNull(index + 1)?.let { start + it.timestampMillis / 1000.0 }
        if (from < start + .4 || from > playedEnd) return@mapIndexedNotNull null
        val known = next != null && next > from && next <= playedEnd && next - from <= 3
        HeardWord(part.rawText, from, if (known) next else from, endReliable = known,
            playedThrough = if (known) next else playedEnd)
    }

/** Optional Android 14+ local decoder of selected audio, never the microphone.
 * Bounded continuous sessions retain quiet phrase context. Completed
 * words stay in memory. A sync tap can separately analyze a finite played sample
 * without opening the microphone or altering video playback.
 */
@androidx.annotation.RequiresApi(34)
internal class RecentDialogueRecognition(private val context: Context, private val language: String) : AutoCloseable {
    private data class Frame(val start: Double, val pcm: ShortArray, val generation: Long, val boundary: Long, val voiced: Boolean)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayBlockingQueue<Frame>(256)
    private val words = mutableListOf<HeardWord>()
    private val history = mutableListOf<HeardWord>()
    private var historyGeneration = -1L
    private var recordedWord: HeardWord? = null
    private var recordedBoundary = -1L
    @Volatile private var running = true
    @Volatile private var probePaused = false
    @Volatile private var failed = false
    private val sessionTokens = java.util.concurrent.atomic.AtomicLong()
    private val token get() = sessionTokens.get()
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var pipe: Array<ParcelFileDescriptor>? = null
    @Volatile private var generation = -1L
    @Volatile private var boundary = -1L
    @Volatile private var lastOffered = Double.NaN
    @Volatile private var decodedWindows = 0
    @Volatile private var errorCode = 0
    @Volatile private var restart = false
    @Volatile private var retryAfter = 0L
    @Volatile private var retryCount = 0

    private fun recover(error: Int) {
        errorCode = error
        retryCount = (retryCount + 1).coerceAtMost(4)
        retryAfter = android.os.SystemClock.elapsedRealtime() + dialogueRecognitionRetryDelay(error, retryCount)
        restart = true
        val recoveryToken = sessionTokens.incrementAndGet() // Fence all callbacks from the failed session.
        android.util.Log.i("AliflixAudioSync", "local_recognition_retry:error=$error,attempt=$retryCount")
        main.post { if (recoveryToken == token) stopSession() }
    }
    private val thread = Thread({ collectFrames() }, "aliflix-local-dialogue").apply { isDaemon = true; start() }

    fun pauseForFiniteRecognition(paused: Boolean) {
        probePaused = paused
        if (paused) {
            queue.clear(); restart = true
            val pauseToken = sessionTokens.incrementAndGet()
            pipe?.forEach { runCatching { it.close() } }
            if (Looper.myLooper() == Looper.getMainLooper()) stopSession()
            else main.post { if (pauseToken == token) stopSession() }
        }
    }

    fun offer(start: Double, pcm: ShortArray, generation: Long, boundary: Long, voiced: Boolean) {
        if (!running || failed || probePaused) return
        if (!lastOffered.isFinite() && queue.isEmpty() && !voiced) return
        lastOffered = start
        if (!queue.offer(Frame(start, pcm.copyOf(), generation, boundary, voiced))) {
            // A decoder burst must not disable sync for the rest of the film.
            // Discard the incomplete session instead of bridging dropped audio.
            queue.clear()
            synchronized(this) { words.clear() }
            recover(-2)
        }
    }
    @Synchronized fun current(position: Double, expectedGeneration: Long, expectedBoundary: Long): List<HeardWord> {
        if (!running || failed || generation != expectedGeneration || boundary != expectedBoundary ||
            !lastOffered.isFinite() || position - lastOffered > 1.0) return emptyList()
        return words.filter { it.start >= position - 30 && it.playedThrough <= position &&
            it.start.isFinite() && (it.end > it.start || !it.endReliable && it.end == it.start) }.takeLast(160)
    }
    @Synchronized fun recordPlayed(position: Double, expectedGeneration: Long, expectedBoundary: Long) {
        if (generation != expectedGeneration || boundary != expectedBoundary) return
        if (historyGeneration != expectedGeneration) { history.clear(); historyGeneration = expectedGeneration }
        val played = playedRecognitionWords(words, position)
        if (played.lastOrNull() == recordedWord && expectedBoundary == recordedBoundary) return
        recordedWord = played.lastOrNull(); recordedBoundary = expectedBoundary
        history.addAll(played)
        val retained = history.distinctBy { it.text to it.start }.sortedBy { it.start }.takeLast(1_500)
        history.clear(); history.addAll(retained)
    }

    @Synchronized fun observedHistory(position: Double, expectedGeneration: Long): List<HeardWord> =
        if (historyGeneration != expectedGeneration) emptyList() else playedRecognitionWords(history, position).takeLast(1_500)

    @Synchronized fun diagnostics(): String = "words=${words.size},localFailed=$failed,localError=$errorCode,localGeneration=$generation,localBoundary=$boundary,localEnd=$lastOffered,localWindows=$decodedWindows,localRetries=$retryCount"

    private fun collectFrames() {
        var last = Double.NaN
        var start = Double.NaN
        var session = -1L
        var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
        val bytes = ByteArray(640)
        try {
            while (running && !failed) {
                val frame = queue.take()
                val discontinuity = frame.generation != generation || frame.boundary != boundary ||
                    !last.isFinite() || abs(frame.start - last - .02) > .003
                last = frame.start
                if (restart && (!frame.voiced || android.os.SystemClock.elapsedRealtime() < retryAfter)) {
                    sessionTokens.incrementAndGet()
                    runCatching { output?.close() }; output = null
                    continue
                }
                if (output == null && !frame.voiced) continue
                if (output == null || restart || discontinuity || frame.start - start >= 45) {
                    // Fence the old listener before closing its descriptor.
                    session = sessionTokens.incrementAndGet()
                    runCatching { output?.close() }
                    val descriptors = ParcelFileDescriptor.createPipe()
                    pipe?.forEach { runCatching { it.close() } }
                    pipe = descriptors
                    restart = false
                    generation = frame.generation; boundary = frame.boundary
                    if (discontinuity) synchronized(this) {
                        words.clear()
                        if (historyGeneration != frame.generation) { history.clear(); historyGeneration = frame.generation }
                    }
                    start = frame.start
                    val activeSession = session
                    val activeStart = start
                    val ready = CountDownLatch(1)
                    main.post { if (running && activeSession == token) startSession(descriptors[0], activeSession, activeStart, ready) else ready.countDown() }
                    if (!ready.await(4, TimeUnit.SECONDS)) { recover(-1); continue }
                    if (failed || restart || activeSession != token) continue
                    output = ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1])
                    decodedWindows++
                }
                frame.pcm.forEachIndexed { index, value -> bytes[index * 2] = value.toByte(); bytes[index * 2 + 1] = (value.toInt() shr 8).toByte() }
                try { output?.write(bytes) } catch (error: java.io.IOException) {
                    if (!running) break
                    if (!restart) recover(-2)
                    runCatching { output?.close() }; output = null
                }
            }
        } catch (_: InterruptedException) { }
        catch (_: Exception) { if (running) { failed = true; errorCode = -3 } }
        finally { runCatching { output?.close() }; main.post { stopSession() } }
    }
    private fun startSession(source: ParcelFileDescriptor, session: Long, start: Double, completed: CountDownLatch) {
        stopRecognizer()
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) { failed = true; completed.countDown(); return }
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle) { completed.countDown() }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onError(error: Int) {
                        if (session == token) {
                            errorCode = error
                            // Silence may finish a local session. A later voiced
                            // frame starts another without waiting in the UI.
                            if (dialogueRecognitionCanRetry(error)) recover(error)
                            else { failed = true; stopSession() }
                        }
                        completed.countDown()
                    }
                    override fun onResults(results: Bundle) { accept(results); restartIfCurrent(); completed.countDown() }
                    override fun onPartialResults(partialResults: Bundle) = Unit
                    override fun onEvent(eventType: Int, params: Bundle) = Unit
                    override fun onSegmentResults(segmentResults: Bundle) = accept(segmentResults)
                    override fun onEndOfSegmentedSession() { restartIfCurrent(); completed.countDown() }
                    private fun restartIfCurrent() { if (session == token) restart = true }
                    private fun accept(bundle: Bundle) {
                        if (!running || failed || session != token) return
                        val parts = bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java).orEmpty()
                        val added = recognizedDialogueWords(parts, start, lastOffered + .02)
                        if (added.isEmpty()) return
                        retryCount = 0; errorCode = 0
                        synchronized(this@RecentDialogueRecognition) {
                            // Prefer a fresh completed interior, retaining words
                            // outside it, including clipped edges from overlap.
                            words.removeAll { it.start >= added.first().start && it.start <= added.last().start }
                            words.addAll(added)
                            val unique = words.filter { it.end >= lastOffered - 32 }.distinctBy { it.text to it.start }.sortedBy { it.start }.takeLast(180)
                            words.clear(); words.addAll(unique)

                        }
                    }
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                    .putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
                    .putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_CONFIDENCE, true)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE))
            }
        } catch (_: Exception) { failed = true; completed.countDown(); stopSession() }
    }
    private fun stopRecognizer() { runCatching { recognizer?.cancel() }; runCatching { recognizer?.destroy() }; recognizer = null }
    private fun stopSession() { stopRecognizer(); pipe?.forEach { runCatching { it.close() } }; pipe = null }
    override fun close() {
        running = false; sessionTokens.incrementAndGet()
        pipe?.forEach { runCatching { it.close() } }
        thread.interrupt(); queue.clear()
        synchronized(this) { words.clear(); history.clear() }
        if (Looper.myLooper() == Looper.getMainLooper()) stopSession() else main.post { stopSession() }
    }
}
