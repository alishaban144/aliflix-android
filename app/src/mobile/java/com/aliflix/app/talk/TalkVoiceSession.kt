package com.aliflix.app.talk

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

internal data class TalkLine(val role: String, val text: String, val sources: List<TalkReference> = emptyList())
internal data class TalkReference(val title: String, val url: String, val license: String)
internal data class TalkVoiceState(val phase: String = "Ready", val words: String = "ALIFLIX", val level: Float = 0f,
    val muted: Boolean = false, val keepHistory: Boolean = false, val lines: List<TalkLine> = emptyList(), val provider: String = "")

/** Remote microphone conversation only. This class never receives episode PCM,
 * subtitle cues, streaming URLs or credentials belonging to a playback source. */
internal class TalkVoiceSession(private val context: Context, private val scope: CoroutineScope) : AutoCloseable {
    private val prefs = context.getSharedPreferences("aliflix-talk", Context.MODE_PRIVATE)
    private fun savedLines():List<TalkLine> = runCatching {
        if(!prefs.getBoolean("keep-history",false)||prefs.getString("owner","")!=(FirebaseAuth.getInstance().currentUser?.uid?:"guest")) emptyList() else {
            val a=JSONArray(prefs.getString("history","[]"))
            (maxOf(0,a.length()-12) until a.length()).map { n ->
                val line=a.getJSONObject(n)
                TalkLine(line.getString("role"),line.getString("text"))
            }
        }
    }.getOrDefault(emptyList())
    private val _state = MutableStateFlow(TalkVoiceState(keepHistory=prefs.getBoolean("keep-history",false),lines=savedLines()))
    val state = _state.asStateFlow()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    @Volatile private var closed = false
    private var ws: WebSocket? = null
    private var capture: AudioRecord? = null
    private var captureJob: Job? = null
    private var outputJob: Job? = null
    private var output: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private class AudioBuffer {
        val chunks=Channel<Pair<Long,ByteArray>>(512)
        val bytes=java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var writing=false
    }
    private var audio: AudioBuffer? = null
    @Volatile private var epoch = 0L
    @Volatile private var sequence = 0L
    private var reconnects = 0
    private var reconnectJob: Job? = null
    private var readyWatchdog: Job? = null
    @Volatile private var permitted = false
    @Volatile private var speaking = false
    @Volatile private var muted = false
    @Volatile private var localVoice = false
    private var ephemeral: JSONObject? = null
    private var resumeHandle: String? = null
    @Volatile private var responseLanguage:String?=null
    internal val debugResponseLanguage get()=responseLanguage
    private var inputText = ""
    private var outputText = ""
    private var lastQuestion = ""
    private var assistantLine = -1
    private val tools = java.util.concurrent.ConcurrentHashMap<String,Job>()
    private val cancelledTools = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile internal var debugReadyGeneration=0L
    @Volatile internal var debugInterruptions = 0
    @Volatile internal var debugFirstAudioAt = 0L
    @Volatile internal var debugTurnCompleteAt = 0L
    @Volatile private var debugRenderedBaseline = 0L
    internal val debugRenderedSamples get() = synchronized(outputLock) { runCatching { (output?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L)-debugRenderedBaseline }.getOrDefault(0L) }
    internal val debugResumeAvailable get() = !resumeHandle.isNullOrBlank()
    internal val debugVoice get() = ephemeral?.optString("voice").orEmpty()
    internal val debugModel get() = ephemeral?.optString("model").orEmpty()
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
    private fun emit(value:JSONObject){val ticket=epoch;if(ws?.send(value.toString())!=true&&permitted)scope.launch{disconnected(ticket,false)}}
    private fun event(type: String) = JSONObject().put("type", type)

    suspend fun connect() {
        if (closed || permitted || _state.value.phase == "Connecting") return
        val account=FirebaseAuth.getInstance().currentUser?.uid?:"guest"
        if(prefs.getString("owner","")!=account){ephemeral=null;resumeHandle=null;responseLanguage=null;prefs.edit().remove("history").apply();update{it.copy(lines=emptyList())}}
        reconnects = 0; permitted = true; startedAt = SystemClock.elapsedRealtime()
        // Deliberately omit every TMDB text field. Only the viewer's own progress
        // and opaque identifiers are used to look up permitted, independent data.
        val progress = (context.applicationContext as AliflixApplication).playbackProgressStore.entries.value.values.maxByOrNull { it.updatedAtMillis }
        conversationContext = JSONObject().put("stickyLanguage",true).put("season", progress?.seasonNumber ?: 1).put("episode", progress?.episodeNumber ?: 1)
            .put("position", progress?.positionSeconds ?: 0).put("completed", progress?.let { talkPlaybackCompleted(it.positionSeconds,it.durationSeconds,it.completed) } == true)
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
        val library = (context.applicationContext as AliflixApplication).libraryStore
        val identities = (library.myList.value.map { it to "saved" } + library.likes.value.map { it to "favorite" } +
            library.recent.value.map { it to "watched" }).filter { it.first.imdbId?.matches(Regex("tt\\d{1,12}")) == true }
            .distinctBy { it.first.key }.take(8)
        conversationContext.put("library", JSONArray(identities.map { (item, kind) ->
            JSONObject().put("imdbId", item.imdbId).put("kind", kind).put("type", item.type.routeName) }))
        conversationContext.put("recentProgress",JSONArray((context.applicationContext as AliflixApplication)
            .playbackProgressStore.entries.value.values.sortedByDescending{it.updatedAtMillis}
            .filter{it.media.imdbId?.matches(Regex("tt\\d{1,12}"))==true}.take(8).map { p ->
                JSONObject().put("imdbId",p.media.imdbId).put("type",p.media.type.routeName)
                    .put("season",p.seasonNumber?:1).put("episode",p.episodeNumber?:1)
                    .put("position",p.positionSeconds).put("duration",p.durationSeconds)
                    .put("completed",talkPlaybackCompleted(p.positionSeconds,p.durationSeconds,p.completed))
            }))
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
    private suspend fun post(path: String, value: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val token = identity()
        val request = Request.Builder().url(BuildConfig.RECOMMENDATION_AI_BASE_URL.trimEnd('/')+"/v1/talk/"+path)
            .header("Authorization","Bearer $token").post(value.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if(response.code==401) prefs.edit().remove("token").putLong("expires",0).apply()
            if(response.code==429) throw IllegalStateException("Limited")
            require(response.isSuccessful) { "Live unavailable" }
            val source=requireNotNull(response.body).source();source.request(128001);require(source.buffer.size<=128000)
            JSONObject(source.buffer.readUtf8())
        }
    }
    private suspend fun openSocket(ticket: Long) {
        cancelledTools.clear()
        update { it.copy(phase=if(reconnects==0) "Connecting" else "Reconnecting") }
        try {
            if(ephemeral==null || ephemeral!!.optLong("expires")<System.currentTimeMillis()+10000) {
                responseLanguage?.let {conversationContext.put("responseLanguage",it)}
                val minted=post("session",JSONObject().put("context",conversationContext))
                if(ticket!=epoch||!permitted)return
                ephemeral=minted;resumeHandle=null
            }
            val live=checkNotNull(ephemeral)
            require(live.getString("model")=="gemini-3.8-live" && live.getString("voice")=="Aoede")
            if(ticket!=epoch || !permitted) return
            readyWatchdog?.cancel();readyWatchdog=scope.launch { delay(15000);if(ticket==epoch&&state.value.phase in listOf("Connecting","Reconnecting")) disconnected(ticket,false) }
            val address="wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
            ws=http.newWebSocket(Request.Builder().url(address).header("Authorization","Token "+live.getString("token")).build(),object:WebSocketListener(){
                override fun onOpen(webSocket:WebSocket,response:Response) {
                    if(ticket!=epoch||!permitted){webSocket.close(1000,null);return}
                    val setup=JSONObject(live.getJSONObject("setup").toString())
                    setup.put("sessionResumption",JSONObject().apply { resumeHandle?.let { put("handle",it) } })
                    webSocket.send(JSONObject().put("setup",setup).toString())
                }
                override fun onMessage(webSocket:WebSocket,text:String) { if(ticket==epoch&&permitted) {
                    if(text.length>1000000){webSocket.close(1009,null);return}
                    runCatching { receiveLive(JSONObject(text),ticket) }.onFailure { scope.launch { disconnected(ticket,false) } }
                } }
                override fun onMessage(webSocket:WebSocket,bytes:okio.ByteString) { onMessage(webSocket,bytes.utf8()) }
                override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { scope.launch {
                    if(ticket==epoch && response?.code in listOf(401,403)){ephemeral=null;resumeHandle=null}
                    disconnected(ticket,response?.code==429)
                } }
                override fun onClosed(webSocket:WebSocket,code:Int,reason:String) { scope.launch {
                    if(ticket==epoch && (reason.contains("resum",true)||reason.contains("handle",true))){ephemeral=null;resumeHandle=null}
                    disconnected(ticket,false)
                } }
            })
        } catch(cancelled:CancellationException){throw cancelled}
        catch(e:Exception){disconnected(ticket,e.message=="Limited")}
    }
    private fun cancelSocket(socket:WebSocket?) {
        if(socket!=null)http.dispatcher.executorService.execute {runCatching {socket.cancel()}}
    }
    private suspend fun disconnected(ticket:Long,limited:Boolean) {
        if(ticket!=epoch||!permitted)return
        update{it.copy(phase="Reconnecting",level=0f)}
        readyWatchdog?.cancel();stopAudio();cancelSocket(ws);ws=null;inputText="";outputText="";assistantLine=-1;finalReferences=emptyList()
        if(limited||++reconnects>3||SystemClock.elapsedRealtime()-startedAt>1200000) {
            suspendSession();update {it.copy(phase=if(limited) "Try again later" else "Reconnect")};return
        }
        val next=++epoch
        reconnectJob?.cancel();reconnectJob=scope.launch { delay((250L shl reconnects).coerceAtMost(2000));openSocket(next) }
    }
    private fun appendTranscript(old:String,chunk:String):String = when {
        chunk.startsWith(old)&&old.isNotEmpty()->chunk
        else->old+chunk
    }.takeLast(6000)
    private fun receiveLive(message:JSONObject,ticket:Long) {
        if(message.has("setupComplete")) { scope.launch(Dispatchers.Main) {
            if(ticket!=epoch||!permitted)return@launch
            readyWatchdog?.cancel();reconnects=0
            if(resumeHandle==null&&state.value.lines.isNotEmpty()) {
                val history=JSONArray(state.value.lines.takeLast(12).map { JSONObject().put("role",if(it.role=="assistant") "model" else "user").put("parts",JSONArray().put(JSONObject().put("text",it.text.take(800)))) })
                emit(JSONObject().put("clientContent",JSONObject().put("turns",history).put("turnComplete",false)))
            }
            try { startAudio(ticket);debugReadyGeneration=ticket;update{it.copy(phase="Listening",provider="gemini-3.8-live · Aoede")} }
            catch(_:Exception){suspendSession();update{it.copy(phase="Audio unavailable")}}
        } }
        message.optJSONObject("sessionResumptionUpdate")?.let { if(it.optBoolean("resumable"))it.optString("newHandle").takeIf(String::isNotBlank)?.let {handle->resumeHandle=handle} }
        message.optJSONObject("goAway")?.let { going -> scope.launch {
            val seconds=going.optString("timeLeft").removeSuffix("s").toDoubleOrNull()?:10.0
            val until=SystemClock.elapsedRealtime()+(seconds*1000).toLong().coerceIn(5000,25000)-2000
            while(speaking&&SystemClock.elapsedRealtime()<until)delay(50)
            disconnected(ticket,false)
        } }
        message.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { a->for(i in 0 until a.length()){val id=a.getString(i);cancelledTools.add(id);tools.remove(id)?.cancel()} }
        message.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls->
            for(i in 0 until calls.length()) {
                val call=calls.getJSONObject(i);val id=call.getString("id")
                if(call.optString("name")=="conversation_language") {
                    val args=call.optJSONObject("args")?:JSONObject()
                    responseLanguage=chooseTalkResponseLanguage(responseLanguage,args.optString("language"),args.optBoolean("explicitRequest"))
                    val result=JSONObject().put("language",responseLanguage?:"und").put("locked",responseLanguage!=null).put("scheduling","SILENT")
                        .put("instruction","Use this response language for every answer until the user explicitly requests a different one. Titles and quotations do not change it.")
                    emit(JSONObject().put("toolResponse",JSONObject().put("functionResponses",JSONArray().put(
                        JSONObject().put("id",id).put("name","conversation_language").put("response",result)))))
                    continue
                }
                if(call.optString("name")=="conversation_topic") {
                    val topic=validatedTalkTopic(call.optJSONObject("args")?.optString("topic").orEmpty())
                    if(topic!=null)update{it.copy(words=topic)}
                    emit(JSONObject().put("toolResponse",JSONObject().put("functionResponses",JSONArray().put(
                        JSONObject().put("id",id).put("name","conversation_topic").put("response",JSONObject().put("accepted",topic!=null).put("scheduling","SILENT"))))))
                    continue
                }
                if(call.optString("name") !in setOf("film_knowledge","viewing_context")||tools.size>=2)continue
                update{it.copy(phase=if(speaking)it.phase else "Thinking")}
                tools[id]=scope.launch {
                    val result=runCatching {
                        if(call.optString("name")=="viewing_context")post("viewing",JSONObject().put("session",ephemeral!!.getString("session")))
                        else post("knowledge",JSONObject().put("session",ephemeral!!.getString("session"))
                            .put("question",(inputText.ifBlank {lastQuestion}).take(1600)).put("request",call.getJSONObject("args")))
                    }
                        .getOrElse { JSONObject().put("unavailable",true).put("evidence",JSONArray()) }
                    if(ticket==epoch&&permitted&&id !in cancelledTools) {
                        val references=result.optJSONArray("evidence")
                        finalReferences=references?.let {a->(0 until a.length()).map {n->a.getJSONObject(n).let {TalkReference(it.getString("title"),it.getString("url"),it.getString("license"))}}}.orEmpty()
                        val response=JSONObject().put("id",id).put("name",call.getString("name")).put("response",result.put("scheduling","WHEN_IDLE"))
                        emit(JSONObject().put("toolResponse",JSONObject().put("functionResponses",JSONArray().put(response))))
                    }
                    tools.remove(id)
                }
            }
        }
        message.optJSONObject("serverContent")?.let { content->
            if(content.optBoolean("interrupted")) {
                debugInterruptions++;sequence++;interruptOutput();assistantLine=-1;outputText="";finalReferences=emptyList()
                tools.forEach {(id,job)->cancelledTools.add(id);job.cancel()};tools.clear()
                update{it.copy(phase=if(muted) "Muted" else "Listening")}
            }
            content.optJSONObject("inputTranscription")?.optString("text")?.let { inputText=appendTranscript(inputText,it);update{v->v.copy(phase=if(speaking) v.phase else "Thinking")} }
            content.optJSONObject("outputTranscription")?.optString("text")?.let { text->
                outputText=appendTranscript(outputText,text)
                if(inputText.isNotBlank()){lastQuestion=inputText;addLine(TalkLine("user",inputText));inputText=""}
                update { v->
                    val lines=v.lines.toMutableList();val line=TalkLine("assistant",outputText,finalReferences)
                    if(assistantLine in lines.indices)lines[assistantLine]=line else{lines.add(line);assistantLine=minOf(lines.lastIndex,23)}
                    v.copy(lines=lines.takeLast(24))
                }
            }
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts->
                if(parts.length()>0){endOfAudio=false;if(inputText.isNotBlank()){lastQuestion=inputText;addLine(TalkLine("user",inputText));inputText=""}}
                for(i in 0 until parts.length()) {
                val data=parts.getJSONObject(i).optJSONObject("inlineData")?:continue
                if(!data.optString("mimeType").startsWith("audio/pcm"))continue
                val bytes=Base64.decode(data.getString("data"),Base64.NO_WRAP)
                require(bytes.size<=192000&&bytes.size%2==0)
                val buffer=audio?:continue
                val reserved=buffer.bytes.addAndGet(bytes.size)
                if(reserved>4_800_000 || buffer.chunks.trySend(sequence to bytes).isFailure) {
                    buffer.bytes.addAndGet(-bytes.size)
                    if(BuildConfig.DEBUG)android.util.Log.w("AliflixTalk","Bounded output queue exhausted")
                    scope.launch {disconnected(ticket,false)};break
                }
            } }
            if(content.optBoolean("turnComplete")) {
                debugTurnCompleteAt=SystemClock.elapsedRealtime();endOfAudio=true;assistantLine=-1;outputText="";finalReferences=emptyList()
                update{it.copy(words=topicWords(lastQuestion,"",it.words))}
                persistHistory()
            }
        }
    }
    private fun addLine(line:TalkLine){if(line.text.isNotBlank())update{it.copy(lines=(it.lines+line).takeLast(24))}}
    private fun persistHistory(){if(state.value.keepHistory)prefs.edit().putString("history",JSONArray(state.value.lines.takeLast(12).map {JSONObject().put("role",it.role).put("text",it.text.take(1200))}).toString()).apply()}
    private fun sendPcm(bytes:ByteArray){emit(JSONObject().put("realtimeInput",JSONObject().put("audio",JSONObject().put("mimeType","audio/pcm;rate=16000").put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)))))}

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
        output = track; writtenSamples=0;debugRenderedBaseline=0;endOfAudio=false;outputEnergy=0.0;track.play()
        val buffer=AudioBuffer();val queue=buffer.chunks;audio=buffer
        outputJob = scope.launch(Dispatchers.IO) {
            for ((seq, bytes) in queue) {
                buffer.bytes.addAndGet(-bytes.size)
                if (seq != sequence || ticket != epoch) continue
                buffer.writing=true
                try {
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
                        else runCatching { track.write(bytes, at, minOf(4800, bytes.size - at), AudioTrack.WRITE_NON_BLOCKING) }.getOrDefault(-1).also { count->if(count>0)writtenSamples+=count/2 }
                    }
                    if (wrote < 0) break
                    if (wrote == 0) { delay(5); continue }
                    at += wrote
                }
                } finally {buffer.writing=false}
            }
        }
        record.startRecording()
        captureJob = scope.launch(Dispatchers.IO) {
            val samples=ShortArray(800)
            try { while(isActive&&permitted&&ticket==epoch) {
                val count=record.read(samples,0,samples.size,AudioRecord.READ_BLOCKING)
                if(!isActive||ticket!=epoch||!permitted)break
                if(count<=0)throw IllegalStateException("Audio input unavailable")
                if(SystemClock.elapsedRealtime()-startedAt>1200000){withContext(Dispatchers.Main){suspendSession()};break}
                var sum=0.0;for(i in 0 until count)sum+=samples[i].toDouble()*samples[i]
                val energy=sqrt(sum/count)/32768.0
                if(debugFirstAudioAt==0L && writtenSamples>0 && debugRenderedSamples>0)debugFirstAudioAt=SystemClock.elapsedRealtime()
                if(speaking&&endOfAudio&&!buffer.writing&&queue.isEmpty&&(track.playbackHeadPosition.toLong() and 0xffffffffL)>=writtenSamples){speaking=false;update{it.copy(phase=if(muted) "Muted" else "Listening")}}
                update{it.copy(level=((if(speaking)outputEnergy else if(muted)0.0 else energy)*12).toFloat().coerceIn(0f,1f))}
                if(muted)continue
                if((ws?.queueSize()?:0)>128000)throw IllegalStateException("Backpressure")
                val bytes=ByteArray(count*2);for(i in 0 until count){bytes[i*2]=samples[i].toByte();bytes[i*2+1]=(samples[i].toInt() shr 8).toByte()}
                // Gemini owns automatic VAD and turn-taking. AEC removes far-end
                // speech before continuous native input; no local speech gate.
                sendPcm(bytes)
            } }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(ticket==epoch)withContext(Dispatchers.Main){disconnected(ticket,false)}}
        }
    }
    private fun interruptOutput() {
        speaking = false; localVoice = false; outputEnergy = 0.0; endOfAudio = false
        audio?.let{buffer->while(true){val chunk=buffer.chunks.tryReceive().getOrNull()?:break;buffer.bytes.addAndGet(-chunk.second.size)}}
        synchronized(outputLock) { runCatching { output?.pause(); output?.flush(); writtenSamples = 0; debugRenderedBaseline = 0; output?.play() } }
    }
    internal val debugPlayedSamples get() = writtenSamples
    internal fun debugInterrupt(){check(BuildConfig.DEBUG);sequence++;interruptOutput();emit(JSONObject().put("realtimeInput",JSONObject().put("text","Stop speaking.")))}
    internal fun debugReconnect(){check(BuildConfig.DEBUG);update{it.copy(phase="Reconnecting")};cancelSocket(ws)}
    internal suspend fun debugInjectText(text:String){check(BuildConfig.DEBUG);muted=true;update{it.copy(muted=true)};if(!permitted)connect();withTimeout(20000){while(captureJob?.isActive!=true)delay(20)};lastQuestion=text;addLine(TalkLine("user",text));debugFirstAudioAt=0;debugTurnCompleteAt=0;debugRenderedBaseline=output?.playbackHeadPosition?.toLong()?.and(0xffffffffL)?:0L;endOfAudio=false;emit(JSONObject().put("realtimeInput",JSONObject().put("text",text)))}
    internal suspend fun debugInjectUtterance(pcm:ByteArray){check(BuildConfig.DEBUG&&pcm.size in 6400..960000&&pcm.size%2==0);muted=true;update{it.copy(muted=true)};if(!permitted)connect();withTimeout(20000){while(captureJob?.isActive!=true)delay(20)};debugFirstAudioAt=0;debugTurnCompleteAt=0;debugRenderedBaseline=output?.playbackHeadPosition?.toLong()?.and(0xffffffffL)?:0L;for(at in pcm.indices step 3200){sendPcm(pcm.copyOfRange(at,minOf(at+3200,pcm.size)));delay(100)};repeat(15){sendPcm(ByteArray(3200));delay(100)};emit(JSONObject().put("realtimeInput",JSONObject().put("audioStreamEnd",true)))}
    fun toggleMute(){muted=!muted;if(muted&&captureJob?.isActive==true)emit(JSONObject().put("realtimeInput",JSONObject().put("audioStreamEnd",true)));update{it.copy(muted=muted,phase=if(it.phase in listOf("Connecting","Reconnecting"))it.phase else if(muted) "Muted" else if(speaking) "Speaking" else "Listening")}}
    fun setHistory(keep:Boolean){prefs.edit().putBoolean("keep-history",keep).apply();update{it.copy(keepHistory=keep)};if(keep)persistHistory()else prefs.edit().remove("history").apply()}
    fun clearHistory(){
        sequence++;interruptOutput();val session=ephemeral?.optString("session")
        resumeHandle=null;ephemeral=null;inputText="";outputText="";assistantLine=-1;finalReferences=emptyList()
        prefs.edit().remove("history").apply();update{it.copy(lines=emptyList(),words="ALIFLIX")}
        val next=++epoch;readyWatchdog?.cancel();reconnectJob?.cancel();stopAudio();cancelSocket(ws);ws=null
        scope.launch {
            if(!session.isNullOrBlank())runCatching{post("close",JSONObject().put("session",session).put("forget",true))}
            if(permitted&&next==epoch)openSocket(next)
        }
    }
    private fun stopAudio() {
        captureJob?.cancel(); captureJob = null
        val record=capture;capture=null
        runCatching { record?.stop() };runCatching { record?.release() }
        val aec=echo;echo=null;runCatching { aec?.release() }
        val ns=noise;noise=null;runCatching { ns?.release() }
        audio?.chunks?.close();audio=null;outputJob?.cancel();outputJob=null
        synchronized(outputLock) {
            val track=output;output=null
            runCatching { track?.pause() };runCatching { track?.flush() };runCatching { track?.release() }
        }
        speaking=false;localVoice=false
        tools.values.forEach {it.cancel()};tools.clear()
    }
    fun suspendSession() {
        // Detach ownership before focus/routing callbacks can re-enter teardown.
        permitted=false;awaitingInterrupt=false;epoch++
        readyWatchdog?.cancel();readyWatchdog=null;reconnectJob?.cancel();reconnectJob=null
        val socket=ws;ws=null;runCatching {socket?.close(1000,"Paused")}
        stopAudio()
        val restoreMode=oldMode;oldMode=null
        val restoreDevice=oldCommunicationDevice;oldCommunicationDevice=null
        val restoreSpeaker=routedSpeaker;routedSpeaker=false
        if(restoreMode!=null) {
            runCatching {manager.abandonAudioFocusRequest(focus)}
            runCatching {
                if(android.os.Build.VERSION.SDK_INT>=31&&restoreSpeaker&&manager.mode==AudioManager.MODE_IN_COMMUNICATION&&
                    manager.communicationDevice?.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    if(restoreDevice!=null)manager.setCommunicationDevice(restoreDevice) else manager.clearCommunicationDevice()
                }
            }
            runCatching {if(manager.mode==AudioManager.MODE_IN_COMMUNICATION)manager.mode=restoreMode}
        }
        update {it.copy(phase="Resume",level=0f)}
    }
    override fun close() {
        if(closed)return
        closed=true
        val socket=ws
        suspendSession()
        http.dispatcher.executorService.execute {
            // TLS close can write close_notify. Activity destruction must never
            // do socket cancellation or connection-pool eviction on main.
            runCatching { socket?.cancel() }
            runCatching { http.dispatcher.cancelAll() }
            runCatching { http.connectionPool.evictAll() }
        }
        // OkHttp owns in-flight WebSocket callbacks. Shutting its executor down
        // here races their close/failure dispatch and can terminate the process.
    }
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

/** Model-generated semantic labels, never raw streaming transcription. */
internal fun validatedTalkTopic(value:String):String? {
    val text=value.replace(Regex("[\\p{Cc}\\p{Cf}]"),"").trim().take(48)
    val tokens=text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+" )).filter{it.isNotBlank()}
    val filler=setOf("okay","ok","just","yes","no","sure","alright","thanks","thank","you","hello","hallo","ja","nein","bitte","danke","نعم","حسنا","شكرا")
    if(tokens.isEmpty()||tokens.size>6||tokens.all{it in filler}||tokens.distinct().size==1&&tokens.size>1)return null
    return text.uppercase(Locale.ROOT)
}
internal fun topicWords(question:String,answer:String,previous:String="ALIFLIX"):String {
    // Local fallback is deliberately conservative; the native semantic tool can
    // identify arbitrary films and concepts without an extra model or request.
    val text=question.ifBlank{answer}.lowercase(Locale.ROOT)
    if(previous!="ALIFLIX")return previous
    val themes=listOf("time loop" to "TIME LOOP","time travel" to "TIME TRAVEL","science fiction" to "SCI-FI",
        "sci-fi" to "SCI-FI","zeitreise" to "ZEITREISE","السفر عبر الزمن" to "السفر عبر الزمن")
    return themes.firstOrNull{it.first in text}?.second?:previous
}

/** A foreign title or a mixed-language phrase cannot unlock an established language. */
internal fun chooseTalkResponseLanguage(current:String?,requested:String,explicitRequest:Boolean):String? {
    if(current!=null&&!explicitRequest)return current
    val code=requested.trim().lowercase(Locale.ROOT)
    return code.takeIf{it.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,2}"))}?:current
}
