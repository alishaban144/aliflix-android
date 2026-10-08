package com.aliflix.app.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.*
import kotlinx.coroutines.*

internal data class PlayedDialoguePcm(val start: Double, val frames: List<ShortArray>)

/** Finite, already played PCM closes at EOF. A sync tap therefore requests a
 * complete local recognition result instead of depending on a live segment's
 * next silence boundary. Never opens the microphone or moves the real player.
 */
@androidx.annotation.RequiresApi(34)
internal object PlayedDialogueRecognition {
    suspend fun recognize(context: Context, language: String, pcm: PlayedDialoguePcm): List<HeardWord> =
        withTimeoutOrNull(7_500) {
            // The local service needs to finish releasing the continuous session.
            delay(180)
            var retryable = false
            var words = attempt(context, language, pcm) { retryable = dialogueRecognitionCanRetry(it) }
            if (words.isEmpty() && retryable) {
                delay(350)
                words = attempt(context, language, pcm) { }
            }
            words
        }.orEmpty()

    private suspend fun attempt(context: Context, language: String, pcm: PlayedDialoguePcm,
        onFailure: (Int) -> Unit): List<HeardWord> = withContext(Dispatchers.Main.immediate) {
            if (pcm.frames.size < 400 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext emptyList()
            val pipe = ParcelFileDescriptor.createPipe()
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val result = CompletableDeferred<List<HeardWord>>()
            val heard = mutableListOf<HeardWord>()
            val ready = CompletableDeferred<Boolean>()
            fun accept(bundle: Bundle) {
                val parts = bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java).orEmpty()
                heard.addAll(parts.zipWithNext().mapNotNull { (a, b) ->
                    val start = pcm.start + a.timestampMillis / 1000.0
                    val end = pcm.start + b.timestampMillis / 1000.0
                    if (start >= pcm.start + .4 && end > start && end - start <= 3 &&
                        end <= pcm.start + pcm.frames.size * .02) HeardWord(a.rawText, start, end) else null
                })
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { ready.complete(true) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onSegmentResults(segmentResults: Bundle) { accept(segmentResults) }
                override fun onEndOfSegmentedSession() { result.complete(heard.distinctBy { it.text to it.start }.sortedBy { it.start }) }
                override fun onResults(results: Bundle) { accept(results); onEndOfSegmentedSession() }
                override fun onError(error: Int) {
                    android.util.Log.i("AliflixAudioSync", "played_dialogue_recognition_error:$error")
                    ready.complete(false)
                    onFailure(error)
                    result.complete(emptyList())
                }
            })
            var writer: Job? = null
            try {
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
                writer = launch(Dispatchers.IO) {
                    try {
                        if (!ready.await()) return@launch
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                            val bytes = ByteArray(640)
                            for (frame in pcm.frames) {
                                ensureActive()
                                frame.forEachIndexed { i, value -> bytes[i * 2] = value.toByte(); bytes[i * 2 + 1] = (value.toInt() shr 8).toByte() }
                                output.write(bytes)
                            }
                        }
                    } catch (error: Exception) { if (isActive) result.complete(emptyList()) }
                }
                withTimeoutOrNull(7_500) { result.await() }.orEmpty()
            } finally {
                pipe.forEach { runCatching { it.close() } }
                writer?.cancel()
                recognizer.cancel(); recognizer.destroy()
            }
        }
}
