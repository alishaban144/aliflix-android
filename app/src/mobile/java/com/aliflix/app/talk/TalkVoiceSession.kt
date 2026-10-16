package com.aliflix.app.talk

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.aliflix.app.AliflixApplication
import com.aliflix.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

internal data class TalkLine(val role: String, val text: String, val sources: List<TalkReference> = emptyList())
internal data class TalkReference(val title: String, val url: String, val license: String)
internal data class TalkVoiceState(val phase: String = "Ready", val words: String = "ALIFLIX", val level: Float = 0f,
    val muted: Boolean = false, val spoilers: Boolean = false, val keepHistory: Boolean = false, val lines: List<TalkLine> = emptyList(), val provider: String = "")

/** Remote microphone conversation only. This class never receives episode PCM,
 * subtitle cues, streaming URLs or credentials belonging to a playback source. */
internal class TalkVoiceSession(private val context: Context, private val scope: CoroutineScope) : AutoCloseable {
    private val prefs = context.getSharedPreferences("aliflix-talk", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(TalkVoiceState(keepHistory = prefs.getBoolean("keep-history", false)))
    val state = _state.asStateFlow()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var capture: AudioRecord? = null
    private var captureJob: Job? = null
    private var outputJob: Job? = null
    private var output: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var audio: Channel<Pair<Long, ByteArray>>? = null
    @Volatile private var epoch = 0L
    @Volatile private var sequence = 0L
    private var reconnects = 0
    private var reconnectJob: Job? = null
    private var readyWatchdog: Job? = null
    @Volatile private var permitted = false
    @Volatile private var speaking = false
    @Volatile private var muted = false
    @Volatile private var localVoice = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val manager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { value -> if (value <= 0) suspendSession() }.build()
    private var oldMode: Int? = null
    private var oldCommunicationDevice: AudioDeviceInfo? = null
    private var routedSpeaker = false
    @Volatile private var outputEnergy = 0.0
    @Volatile private var endOfAudio = false
    @Volatile private var writtenSamples = 0L
    private var startedAt = 0L
    @Volatile private var awaitingInterrupt = false
    private val outputLock = Any()
    private var finalText = ""
    private var finalReferences: List<TalkReference> = emptyList()
    private var conversationContext = JSONObject()

    private fun update(block: (TalkVoiceState) -> TalkVoiceState) { _state.update(block) }
    fun permissionDenied() = update { it.copy(phase = "Microphone permission", words = "MICROPHONE") }
    private fun emit(value: JSONObject) { ws?.send(value.toString()) }
    private fun event(type: String) = JSONObject().put("type", type)

    suspend fun connect() {
        if (permitted || _state.value.phase == "Connecting") return
        reconnects = 0; permitted = true; startedAt = SystemClock.elapsedRealtime()
        // Deliberately omit every TMDB text field. Only the viewer's own progress
        // and opaque identifiers are used to look up permitted, independent data.
        val progress = (context.applicationContext as AliflixApplication).playbackProgressStore.entries.value.values.maxByOrNull { it.updatedAtMillis }
        conversationContext = JSONObject().put("season", progress?.seasonNumber ?: 1).put("episode", progress?.episodeNumber ?: 1)
            .put("position", progress?.positionSeconds ?: 0).put("completed", progress?.let { talkPlaybackCompleted(it.positionSeconds,it.durationSeconds,it.completed) } == true).put("spoilers", false)
        progress?.let { p -> conversationContext.put("id", p.media.id).put("type", p.media.type.routeName)
            p.media.imdbId?.takeIf { it.matches(Regex("tt\\d{1,12}")) }?.let { conversationContext.put("imdbId", it) } }
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            permitted = false; update { it.copy(phase = "Audio unavailable") }; return
        }
        oldMode = manager.mode
        if (android.os.Build.VERSION.SDK_INT >= 31) oldCommunicationDevice = manager.communicationDevice
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (android.os.Build.VERSION.SDK_INT >= 31 && manager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
            manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let {
                routedSpeaker = manager.setCommunicationDevice(it)
            }
        }
        if (tts == null) tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            // Never use a network-only Android voice for the final local fallback.
            tts?.voice = tts?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language }
        }.also { engine -> engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { if (!permitted || utteranceId != "talk-$epoch-$sequence") return; localVoice = true; speaking = true; update { it.copy(phase = "Speaking") } }
            override fun onDone(utteranceId: String?) { if (!permitted || utteranceId != "talk-$epoch-$sequence") return; localVoice = false; speaking = false; update { it.copy(phase = "Listening") } }
            @Deprecated("Platform callback") override fun onError(utteranceId: String?) { onDone(utteranceId) }
        }) }
        val library = (context.applicationContext as AliflixApplication).libraryStore
        val identities = (library.myList.value.map { it to "saved" } + library.likes.value.map { it to "favorite" } +
            library.recent.value.map { it to "watched" }).filter { it.first.imdbId?.matches(Regex("tt\\d{1,12}")) == true }
            .distinctBy { it.first.key }.take(8)
        conversationContext.put("library", JSONArray(identities.map { (item, kind) ->
            JSONObject().put("imdbId", item.imdbId).put("kind", kind).put("type", item.type.routeName) }))
        conversationContext.put("spoilers", _state.value.spoilers)
        val completedEpisodes = (context.applicationContext as AliflixApplication).playbackProgressStore.entries.value.values
            .filter { it.media.key == progress?.media?.key && talkPlaybackCompleted(it.positionSeconds,it.durationSeconds,it.completed) }
            .map { (it.seasonNumber ?: 1) to (it.episodeNumber ?: 1) }
        conversationContext.put("viewedRanges", JSONArray(compactTalkViewedRanges(completedEpisodes).map { (season, first, last) ->
            JSONObject().put("season",season).put("first",first).put("last",last) }))
        openSocket(++epoch)
    }
    private suspend fun identity(): String = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser; val owner = user?.uid ?: "guest"
        if (prefs.getString("owner", "").orEmpty().let { it.isNotBlank() && it != owner }) update { it.copy(lines = emptyList()) }
        if (prefs.getString("owner", "") == owner && prefs.getLong("expires", 0) > System.currentTimeMillis() + 600_000)
            return@withContext prefs.getString("token", "").orEmpty()
        val body = JSONObject(); user?.getIdToken(false)?.await()?.token?.let { body.put("idToken", it) }
        val request = Request.Builder().url(BuildConfig.RECOMMENDATION_AI_BASE_URL.trimEnd('/') + "/v1/party/identity")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val data = http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Connection unavailable" }; val source = requireNotNull(response.body).source()
            source.request(16_385); require(source.buffer.size <= 16_384); JSONObject(source.buffer.readUtf8())
        }
        prefs.edit().putString("owner", owner).putString("token", data.getString("token")).putLong("expires", data.getLong("expires")).apply()
        data.getString("token")
    }
    private suspend fun openSocket(ticket: Long) {
        sequence = 0; awaitingInterrupt = false;
        update { it.copy(phase = if (reconnects == 0) "Connecting" else "Reconnecting", level = 0f) }
        try {
            val token = identity(); if (!permitted || ticket != epoch) return
            readyWatchdog?.cancel()
            readyWatchdog = scope.launch {
                delay(20_000)
                if (ticket == epoch && permitted && _state.value.phase in listOf("Connecting","Reconnecting")) {
                    ws?.cancel(); disconnected(ticket,false)
                }
            }
            ws = http.newWebSocket(Request.Builder().url(BuildConfig.RECOMMENDATION_AI_BASE_URL.trimEnd('/') + "/v1/talk")
                .header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (ticket != epoch || !permitted) { webSocket.close(1000, null); return }
                    val history = JSONArray(_state.value.lines.takeLast(12).map { JSONObject().put("role", it.role).put("text", it.text.take(2000)) })
                    webSocket.send(event("start").put("context", conversationContext).put("keepHistory", _state.value.keepHistory).put("history", history).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (ticket != epoch || !permitted) return
                    if (text.length > 600_000) { webSocket.close(1009, null); return }
                    runCatching { receive(JSONObject(text), ticket) }.onFailure { update { it.copy(phase = "Connection interrupted") } }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) prefs.edit().remove("token").putLong("expires",0).apply()
                    scope.launch { disconnected(ticket, response?.code == 429) }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scope.launch {
                        if (ticket == epoch && reason == "Session moved") {
                            suspendSession(); update { it.copy(phase = "Opened elsewhere") }
                        } else disconnected(ticket, reason == "Session limit")
                    }
                }
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { disconnected(ticket, false) }
    }
    private suspend fun disconnected(ticket: Long, limited: Boolean) {
        if (ticket != epoch || !permitted) return
        stopAudio(); ws = null
        if (limited || ++reconnects > 3 || SystemClock.elapsedRealtime() - startedAt > 1_800_000) {
            suspendSession(); update { it.copy(phase = if (limited) "Try again later" else "Reconnect", words = "ALIFLIX") }; return
        }
        val next = ++epoch
        reconnectJob?.cancel(); reconnectJob = scope.launch { delay((500L shl reconnects).coerceAtMost(8000)); openSocket(next) }
    }
    private fun receive(message: JSONObject, ticket: Long) {
        val incoming = message.optLong("sequence", sequence)
        if (incoming < sequence) return
        when (message.optString("type")) {
            "ready" -> scope.launch {
                if (ticket != epoch || !permitted) return@launch
                readyWatchdog?.cancel(); readyWatchdog = null; reconnects = 0
                update { it.copy(phase = "Listening", provider = message.optJSONObject("plan")?.let { p -> "${p.optString("asr")} · ${p.optString("reasoning")} · ${p.optString("tts")}" }.orEmpty()) }
                message.optJSONArray("history")?.let { a -> if (_state.value.lines.isEmpty()) update { it.copy(lines = (0 until a.length()).map { i -> val line = a.getJSONObject(i); TalkLine(line.getString("role"), line.getString("text")) }) } }
                try { startAudio(ticket) } catch (_: Exception) { suspendSession(); update { it.copy(phase = "Audio unavailable") } }
            }
            "providers" -> update { it.copy(provider = message.optJSONObject("plan")?.let { p -> "${p.optString("asr")} · ${p.optString("reasoning")} · ${p.optString("tts")}" }.orEmpty()) }
            "interrupted" -> { awaitingInterrupt = false; sequence = incoming; interruptOutput(); update { it.copy(phase = "Listening") } }
            "thinking" -> { sequence = incoming; update { it.copy(phase = "Thinking", level = 0f) } }
            "transcript" -> if (message.optBoolean("final")) addLine(TalkLine("user", message.optString("text")))
            "answer" -> {
                sequence = incoming; finalText = message.getString("text")
                finalReferences = message.optJSONArray("references")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { TalkReference(it.getString("title"), it.getString("url"), it.getString("license")) } } }.orEmpty()
                addLine(TalkLine("assistant", finalText, finalReferences)); writtenSamples = 0; endOfAudio = false
                update { it.copy(words = message.optString("words").takeIf(String::isNotBlank) ?: "ALIFLIX") }
            }
            "audio" -> {
                if (awaitingInterrupt) return
                val bytes = Base64.decode(message.getString("data"), Base64.NO_WRAP)
                require(bytes.size <= 480_000 && bytes.size % 2 == 0 && message.optInt("sampleRate") == 24000)
                if (audio?.trySend(incoming to bytes)?.isFailure == true) { interruptOutput(); emit(event("interrupt")); update { it.copy(phase = "Audio interrupted") } }
            }
            "audio-end" -> endOfAudio = true
            "speak-local" -> scope.launch(Dispatchers.Main) {
                if (ticket != epoch || incoming != sequence) return@launch
                val language = message.optString("language", Locale.getDefault().language)
                val voice = tts?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == language }
                if (ttsReady && voice != null) {
                    tts?.voice = voice
                    tts?.speak(message.optString("text"), TextToSpeech.QUEUE_FLUSH, null, "talk-$epoch-$sequence")
                } else update { it.copy(phase = "Voice unavailable") }
            }
            "listening" -> update { it.copy(phase = "Listening") }
            "cleared" -> update { it.copy(lines = emptyList(), words = "ALIFLIX") }
            "error" -> {
                val limited = message.optString("code") in listOf("QUOTA", "SESSION_LIMIT")
                if (captureJob?.isActive != true || limited) scope.launch(Dispatchers.Main) {
                    suspendSession(); update { it.copy(phase = if (limited) "Try again later" else "Reconnect", level = 0f) }
                } else update { it.copy(phase = "Try again", level = 0f) }
            }
        }
    }
    private fun addLine(line: TalkLine) { if (line.text.isNotBlank()) update { it.copy(lines = (it.lines + line).takeLast(12)) } }

    @SuppressLint("MissingPermission")
    private fun startAudio(ticket: Long) {
        if (captureJob?.isActive == true) return
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(12_800, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)).build()
        require(record.state == AudioRecord.STATE_INITIALIZED)
        capture = record
        echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(12_000, AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)).build()
        output = track; track.play()
        val queue = Channel<Pair<Long, ByteArray>>(64); audio = queue
        outputJob = scope.launch(Dispatchers.IO) {
            for ((seq, bytes) in queue) {
                if (seq != sequence || ticket != epoch) continue
                speaking = true; update { it.copy(phase = "Speaking") }
                var squared = 0.0
                for (i in 0 until bytes.size - 1 step 2) {
                    val sample = ((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toDouble() / 32768.0
                    squared += sample * sample
                }
                outputEnergy = sqrt(squared / (bytes.size / 2).coerceAtLeast(1))
                var at = 0
                while (at < bytes.size && seq == sequence && isActive && ticket == epoch) {
                    val wrote = synchronized(outputLock) {
                        if (!isActive || ticket != epoch || seq != sequence || awaitingInterrupt) -1
                        else runCatching { track.write(bytes, at, minOf(4800, bytes.size - at), AudioTrack.WRITE_NON_BLOCKING) }.getOrDefault(-1)
                    }
                    if (wrote < 0) break
                    if (wrote == 0) { delay(5); continue }
                    at += wrote; writtenSamples += wrote / 2
                }
            }
        }
        record.startRecording()
        captureJob = scope.launch(Dispatchers.IO) {
            val samples = ShortArray(320); val preRoll = ArrayDeque<ByteArray>(); val vad = TalkTurnDetector()
            // A separate instance of the already bundled WebRTC detector rejects
            // fan/room noise; subtitle synchronization's detector is untouched.
            val speechDetector = runCatching { VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.AGGRESSIVE) }.getOrNull()
            var lastLevel = 0L
            try { while (isActive && permitted && ticket == epoch) {
                val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) throw IllegalStateException("Audio input unavailable")
                if (SystemClock.elapsedRealtime() - startedAt > 1_800_000) { withContext(Dispatchers.Main) { suspendSession() }; break }
                var sum = 0.0; for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
                val energy = sqrt(sum / count) / 32768.0
                val now = SystemClock.elapsedRealtime()
                if (speaking && !localVoice && endOfAudio && queue.isEmpty && (track.playbackHeadPosition.toLong() and 0xffffffffL) >= writtenSamples) {
                    speaking = false; update { it.copy(phase = "Listening") }
                }
                if (now - lastLevel > 50) { lastLevel = now; update { it.copy(level = ((if (speaking) outputEnergy else energy) * 12).toFloat().coerceIn(0f, 1f)) } }
                if (muted) { if (vad.reset()) emit(event("activity").put("active", false)); preRoll.clear(); continue }
                val bytes = ByteArray(count * 2); for (i in 0 until count) { bytes[i * 2] = samples[i].toByte(); bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte() }
                if (count < samples.size) java.util.Arrays.fill(samples,count,samples.size,0.toShort())
                val speech = speechDetector?.isSpeech(samples) != false
                val activity = vad.accept(if (speech) energy else 0.0, now, speaking && echo?.enabled != true, if (speaking) outputEnergy else 0.0)
                if (activity == TalkTurnDetector.START) {
                    awaitingInterrupt = true; interruptOutput(); emit(event("activity").put("active", true)); preRoll.forEach { ws?.send(it.toByteString()) }; preRoll.clear()
                }
                if (vad.active) {
                    if ((ws?.queueSize() ?: 0) > 128_000) { throw IllegalStateException("Backpressure") }
                    ws?.send(bytes.toByteString())
                } else { preRoll.addLast(bytes); while (preRoll.size > 10) preRoll.removeFirst() }
                if (activity == TalkTurnDetector.END) emit(event("activity").put("active", false))
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == epoch) withContext(Dispatchers.Main) { disconnected(ticket,false) } }
            finally { runCatching { speechDetector?.close() } }
        }
    }
    private fun interruptOutput() {
        speaking = false; localVoice = false; outputEnergy = 0.0; endOfAudio = false; tts?.stop()
        while (audio?.tryReceive()?.isSuccess == true) Unit
        synchronized(outputLock) { runCatching { output?.pause(); output?.flush(); writtenSamples = 0; output?.play() } }
    }
    internal val debugPlayedSamples get() = writtenSamples
    internal fun debugInterrupt() { check(BuildConfig.DEBUG); awaitingInterrupt = true; interruptOutput(); emit(event("interrupt")) }
    internal fun debugReconnect() { check(BuildConfig.DEBUG); ws?.cancel() }
    internal suspend fun debugInjectUtterance(pcm: ByteArray) {
        check(BuildConfig.DEBUG && pcm.size in 6400..960000 && pcm.size % 2 == 0)
        muted = true; update { it.copy(muted = true) }
        if (!permitted) connect()
        withTimeout(20_000) { state.first { it.phase == "Listening" } }
        awaitingInterrupt = true; interruptOutput(); emit(event("activity").put("active", true))
        for (at in pcm.indices step 3200) {
            ws?.send(pcm.copyOfRange(at, minOf(at + 3200, pcm.size)).toByteString()); delay(100)
        }
        emit(event("activity").put("active", false))
    }
    fun toggleMute() { muted = !muted; update { it.copy(muted = muted, phase = if (muted) "Muted" else "Listening") } }
    fun setSpoilers(allowed: Boolean) { update { it.copy(spoilers = allowed) }; conversationContext.put("spoilers", allowed); emit(event("spoilers").put("allowed", allowed)) }
    fun setHistory(keep: Boolean) { prefs.edit().putBoolean("keep-history", keep).apply(); update { it.copy(keepHistory = keep) }; emit(event("privacy").put("keepHistory", keep)) }
    fun clearHistory() { interruptOutput(); emit(event("clear")); update { it.copy(lines = emptyList(), words = "ALIFLIX") } }
    private fun stopAudio() {
        captureJob?.cancel(); captureJob = null; runCatching { capture?.stop() }; capture?.release(); capture = null
        echo?.release(); echo = null; noise?.release(); noise = null
        audio?.close(); audio = null; outputJob?.cancel(); outputJob = null
        synchronized(outputLock) { runCatching { output?.pause(); output?.flush(); output?.release() }; output = null }
        tts?.stop(); speaking = false; localVoice = false
    }
    fun suspendSession() {
        permitted = false; awaitingInterrupt = false; epoch++; readyWatchdog?.cancel(); readyWatchdog = null; reconnectJob?.cancel(); ws?.close(1000, "Paused"); ws = null
        stopAudio(); manager.abandonAudioFocusRequest(focus)
        if (android.os.Build.VERSION.SDK_INT >= 31 && routedSpeaker && manager.mode == AudioManager.MODE_IN_COMMUNICATION &&
            manager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            oldCommunicationDevice?.let { runCatching { manager.setCommunicationDevice(it) } } ?: manager.clearCommunicationDevice()
        }
        routedSpeaker = false; oldCommunicationDevice = null
        if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) oldMode?.let { manager.mode = it }; oldMode = null
        update { it.copy(phase = "Resume", level = 0f) }
    }
    override fun close() { suspendSession(); tts?.shutdown(); tts = null; http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}

/** Adaptive energy gate with attack, hysteresis, hangover and bounded utterances.
 * AEC/VOICE_COMMUNICATION removes the assistant's playback before this detector. */
internal class TalkTurnDetector {
    var active = false; private set
    private var floor = .002; private var attack = 0; private var lastVoice = 0L; private var start = 0L
    fun accept(level: Double, now: Long, echoUncontrolled: Boolean, farEnd: Double = 0.0): Int {
        val threshold = maxOf(.009, floor * if (echoUncontrolled) 5.0 else 3.0, if (echoUncontrolled) maxOf(.025,farEnd*.8) else 0.0)
        val voiced = level > threshold
        if (!active && !voiced) floor = floor * .98 + level.coerceAtMost(.02) * .02
        if (!active) { attack = if (voiced) attack + 1 else 0
            if (attack >= 3) { active = true; start = now; lastVoice = now; return START }
        } else {
            if (voiced) lastVoice = now
            if (now - lastVoice >= 750 || now - start >= 25_000) { reset(); return END }
        }
        return NONE
    }
    fun reset(): Boolean { val was = active; active = false; attack = 0; return was }
    companion object { const val NONE = 0; const val START = 1; const val END = 2 }
}

/** Library completion starts at 80%; that flag alone is not a spoiler licence. */
internal fun talkPlaybackCompleted(position: Double, duration: Double, completed: Boolean): Boolean =
    completed && position.isFinite() && duration.isFinite() && duration > 0 && position >= duration - 2.0

/** Compact only actually completed episodes; a skipped episode never fills a gap. */
internal fun compactTalkViewedRanges(episodes: List<Pair<Int,Int>>): List<Triple<Int,Int,Int>> {
    val ranges = mutableListOf<Triple<Int,Int,Int>>()
    for ((season, episode) in episodes.filter { it.first in 0..1000 && it.second in 1..10000 }
        .distinct().sortedWith(compareBy({ it.first },{ it.second }))) {
        val last = ranges.lastOrNull()
        if (last != null && last.first == season && last.third + 1 == episode)
            ranges[ranges.lastIndex] = Triple(season,last.second,episode)
        else ranges.add(Triple(season,episode,episode))
    }
    return ranges.takeLast(120)
}
