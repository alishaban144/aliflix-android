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
internal val VOICES=listOf("Aoede","Kore","Puck","Charon")
internal data class TalkVoiceState(val phase: String = "Ready", val words: String = "ALIFLIX", val level: Float = 0f, val voice:String="Aoede",
    val muted: Boolean = false, val keepHistory: Boolean = false, val lines: List<TalkLine> = emptyList(), val provider: String = "", val textMode:Boolean=false, val sending:Boolean=false,
    val conversationId:String=java.util.UUID.randomUUID().toString())

/** Remote microphone conversation only. This class never receives episode PCM,
 * subtitle cues, streaming URLs or credentials belonging to a playback source. */
internal class TalkVoiceSession(private val context: Context, private val scope: CoroutineScope, private val restoreHistory:Boolean=true) : AutoCloseable {
    private val prefs = context.getSharedPreferences("aliflix-talk", Context.MODE_PRIVATE)
    private var sessionOwner=FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
    private val accountListener=FirebaseAuth.AuthStateListener { auth ->
        val owner=auth.currentUser?.uid ?: "guest"
        if(owner!=sessionOwner)scope.launch {
            textSend?.cancel();suspendSession();sessionOwner=owner
            ephemeral=null;backendSession=null;resumeHandle=null;userTurn.incrementAndGet()
            chatId=java.util.UUID.randomUUID().toString();inputText="";lastQuestion="";outputText="";assistantLine=-1
            update { it.copy(lines=emptyList(),sending=false,phase="Ready",words="ALIFLIX",conversationId=chatId) }
            history.refresh()
        }
    }
    private val preferenceListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key=="keep-history")update { it.copy(keepHistory=restoreHistory&&prefs.getBoolean("keep-history",true)) }
        if(key=="voice") {
            val voice=prefs.getString("voice","Aoede").orEmpty()
            if(voice in VOICES&&voice!=state.value.voice) {
                // A remote choice takes effect without starting a background mic.
                pauseSession();ephemeral=null;backendSession=null;resumeHandle=null
                update { it.copy(voice=voice) }
            }
        }
    }
    private fun savedLines():List<TalkLine> = runCatching {
        if(!prefs.getBoolean("keep-history",false)||prefs.getString("owner","")!=(FirebaseAuth.getInstance().currentUser?.uid?:"guest")) emptyList() else {
            val a=JSONArray(prefs.getString("history","[]"))
            (maxOf(0,a.length()-12) until a.length()).map { n ->
                val line=a.getJSONObject(n)
                TalkLine(line.getString("role"),line.getString("text"))
            }
        }
    }.getOrDefault(emptyList())
    private val _state = MutableStateFlow(TalkVoiceState(keepHistory=restoreHistory&&prefs.getBoolean("keep-history",true),lines=emptyList(),voice=prefs.getString("voice","Aoede")?.takeIf{it in VOICES}?:"Aoede"))
    val state = _state.asStateFlow()
    val history=TalkAccountArchive.get(context)
    private var chatId=state.value.conversationId
    private val userTurn=java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var inputTurnActive=false
    @Volatile private var socketReady=false
    private var responseTurn=-1L
    private var textSend:Job?=null
    private var archiveReadSequence=0L
    private fun beginUserTurn() {
        userTurn.incrementAndGet();responseTurn=-1L;turnHasAudio=false;inputTurnActive=true;continueAfterReconnect=false
        debugRecommendationCount=0;debugRecommendationYears=emptyList();debugRecommendationIssue=""
        tools.values.forEach { it.cancel() };tools.clear()
        sequence++;interruptOutput();assistantLine=-1;outputText="";finalReferences=emptyList()
        update { it.copy(sending=true) }
    }
    fun setTextMode(enabled:Boolean) {
        if(enabled==state.value.textMode)return
        persistHistory();suspendSession();update { it.copy(textMode=enabled,muted=enabled) };muted=enabled
    }
    fun sendText(text:String) {
        val question=text.trim().take(1600);if(question.isBlank())return
        archiveReadSequence++
        textSend?.cancel()
        textSend=scope.launch {
            val replacesPending=state.value.sending||speaking||tools.isNotEmpty()||state.value.phase in listOf("Connecting","Reconnecting")
            // Live responses have no client turn ID. A new socket gives typed
            // replacements a hard boundary, including queued tool calls and
            // completions that may arrive after the old generation ended.
            if(replacesPending) {
                suspendSession();ephemeral=null;resumeHandle=null
            }
            beginUserTurn();inputText=question;commitQuestion();persistHistory()
            try {
                if(!permitted)connect()
                withTimeout(20000){while(!socketReady)delay(30)}
                answerDeadline.awaiting(70000)
                emit(JSONObject().put("clientContent",JSONObject().put("turns",JSONArray().put(JSONObject().put("role","user").put("parts",JSONArray().put(JSONObject().put("text",question))))).put("turnComplete",true)))
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){update { it.copy(phase="Reconnect",sending=false) }}
        }
    }
    fun stopAnswer() {
        archiveReadSequence++
        textSend?.cancel();textSend=null
        suspendSession();userTurn.incrementAndGet();inputTurnActive=false
        ephemeral=null;resumeHandle=null;outputText="";assistantLine=-1;finalReferences=emptyList()
        update { it.copy(sending=false,phase="Ready",words="ALIFLIX") }
    }
    fun retryLastMessage() {
        val question=state.value.lines.lastOrNull { it.role=="user" }?.text ?: return
        // Retry replaces the failed turn instead of adding duplicate user bubbles.
        stopAnswer()
        update { it.copy(lines=it.lines.dropLastWhile { line -> line.role!="user" }.dropLast(1)) }
        sendText(question)
    }
    fun newChat() {
        archiveReadSequence++
        textSend?.cancel();textSend=null
        persistHistory();suspendSession();userTurn.incrementAndGet();inputTurnActive=false
        chatId=java.util.UUID.randomUUID().toString();ephemeral=null;backendSession=null;resumeHandle=null
        inputText="";lastQuestion="";outputText="";assistantLine=-1;responseLanguage=null
        update { it.copy(lines=emptyList(),words="ALIFLIX",sending=false,conversationId=chatId) }
    }
    fun openChat(id:String) {
      pauseSession();val ticket=++archiveReadSequence;val owner=sessionOwner
      update { it.copy(textMode=true,muted=true,phase="Opening chat") }
      scope.launch {
        val chat=history.read(id)
        if(ticket!=archiveReadSequence||owner!=sessionOwner||closed)return@launch
        if(chat==null){update { it.copy(phase="Ready") };return@launch}
        newChat();chatId=chat.id;muted=true;update { it.copy(lines=chat.lines,words=chat.title.take(32),textMode=true,muted=true,conversationId=chatId) }
        if(state.value.textMode)connect()
      }
    }

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
    private val answerDeadline=TalkResponseDeadline(scope) {
        if(permitted&&!speaking) {
            if(stalledRecoveries++<1){debugStallRecoveries++;commitQuestion();continueAfterReconnect=true;scope.launch{disconnected(epoch,false)}}
            else {suspendSession();update{it.copy(phase="Reconnect",level=0f)}}
        }
    }
    @Volatile private var permitted = false
    @Volatile private var speaking = false
    @Volatile private var muted = false
    @Volatile private var localVoice = false
    private var ephemeral: JSONObject? = null
    private var backendSession:String?=null
    private var resumeHandle: String? = null
    @Volatile private var responseLanguage:String?=null
    internal val debugResponseLanguage get()=responseLanguage
    private var inputText = ""
    private var outputText = ""
    private var lastQuestion = ""
    private var assistantLine = -1
    private val tools = java.util.concurrent.ConcurrentHashMap<String,Job>()
    internal val debugToolCalls=java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val cancelledTools = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile internal var debugReadyGeneration=0L
    @Volatile internal var debugInterruptions = 0
    @Volatile internal var debugFirstAudioAt = 0L
    @Volatile internal var debugTurnCompleteAt = 0L
    @Volatile private var debugRenderedBaseline = 0L
    internal val debugRenderedSamples get() = synchronized(outputLock) { runCatching { (output?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L)-debugRenderedBaseline }.getOrDefault(0L) }
    internal val debugResumeAvailable get() = !resumeHandle.isNullOrBlank()
    internal var debugConnectionIssue=""
    internal var debugStallRecoveries=0
    internal var debugKnowledgeRequest=""
    @Volatile internal var debugKnowledgeAppliedAt=0L
    internal val debugVoice get() = ephemeral?.optString("voice").orEmpty()
    internal val debugModel get() = ephemeral?.optString("model").orEmpty()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { value ->
            when(value) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching {output?.setVolume(.25f)}
                AudioManager.AUDIOFOCUS_GAIN -> runCatching {output?.setVolume(1f)}
                AudioManager.AUDIOFOCUS_LOSS,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {suspendSession();update{it.copy(phase="Audio unavailable",level=0f)}}
            }
        }.build()
    private var oldMode: Int? = null
    private var oldCommunicationDevice: AudioDeviceInfo? = null
    private var routedSpeaker = false
    @Volatile private var outputEnergy = 0.0
    @Volatile private var endOfAudio = false
    @Volatile private var writtenSamples = 0L
    @Volatile private var primingFrom = 0L
    @Volatile private var primingBegan = 0L
    internal val debugUnderruns get()=runCatching{output?.underrunCount?:0}.getOrDefault(0)
    internal var debugEpisodeEvidence=""
    internal var debugWebEvidenceCount=0
    internal var debugRecommendationCount=0
    internal var debugRecommendationIssue=""
    internal var debugRecommendationYears:List<Int> = emptyList()
    @Volatile private var lastOutputAt=0L
    private var stalledRecoveries=0
    private var continueAfterReconnect=false
    private var turnHasAudio=false
    private var startedAt = 0L
    @Volatile private var awaitingInterrupt = false
    private val outputLock = Any()
    private val knownFilmTopics = linkedSetOf<String>()
    private fun rememberFilmTopic(title:String){validatedTalkTopic(title)?.let{synchronized(knownFilmTopics){knownFilmTopics.add(it);while(knownFilmTopics.size>16)knownFilmTopics.remove(knownFilmTopics.first())}}}
    private var finalReferences: List<TalkReference> = emptyList()
    private var conversationContext = JSONObject()

    init {
        FirebaseAuth.getInstance().addAuthStateListener(accountListener)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val owner=FirebaseAuth.getInstance().currentUser?.uid ?: "guest"
        if(restoreHistory&&!prefs.getBoolean("archive-migrated:$owner",false)) {
            val legacy=savedLines()
            if(legacy.isNotEmpty())history.save(TalkChat(java.util.UUID.randomUUID().toString(),legacy.firstOrNull { it.role=="user" }?.text?.take(80)?:"Conversation",System.currentTimeMillis(),legacy))
            prefs.edit().putBoolean("archive-migrated:$owner",true).apply()
        }
    }

    private fun update(block: (TalkVoiceState) -> TalkVoiceState) { _state.update(block) }
    fun permissionDenied() = update { it.copy(phase = "Microphone permission", words = "MICROPHONE") }
    private fun emit(value:JSONObject){val ticket=epoch;if(ws?.send(value.toString())!=true&&permitted)scope.launch{disconnected(ticket,false)}}
    private fun event(type: String) = JSONObject().put("type", type)

    suspend fun connect() {
        if (closed || permitted || _state.value.phase == "Connecting") return
        val account=FirebaseAuth.getInstance().currentUser?.uid?:"guest"
        if(sessionOwner!=account)return
        reconnects = 0; permitted = true; startedAt = SystemClock.elapsedRealtime()
        // Deliberately omit every TMDB text field. Only the viewer's own progress
        // and opaque identifiers are used to look up permitted, independent data.
        val progress = (context.applicationContext as AliflixApplication).playbackProgressStore.entries.value.values.maxByOrNull { it.updatedAtMillis }
        conversationContext = JSONObject().put("companionVersion",3).put("voice",state.value.voice).put("stickyLanguage",true).put("season", progress?.seasonNumber ?: 1).put("episode", progress?.episodeNumber ?: 1)
            .put("position", progress?.positionSeconds ?: 0).put("completed", progress?.let { talkPlaybackCompleted(it.positionSeconds,it.durationSeconds,it.completed) } == true)
        progress?.let { p -> conversationContext.put("id", p.media.id).put("type", p.media.type.routeName)
            p.media.imdbId?.takeIf { it.matches(Regex("tt\\d{1,12}")) }?.let { conversationContext.put("imdbId", it) } }
        if(!state.value.textMode) {
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
        }
        val library = (context.applicationContext as AliflixApplication).libraryStore
        val identities = (library.likes.value.map { it to "favorite" } + library.myList.value.map { it to "saved" } +
            library.recent.value.map { it to "watched" }).distinctBy { it.first.key }.take(48)
        conversationContext.put("library", JSONArray(identities.map { (item, kind) -> talkLibraryIdentity(item,kind) }))
        val rejected=com.aliflix.app.recommendation.AskTaste(context).rejected().take(16).mapNotNull{key->val p=key.split(':');p.getOrNull(1)?.toIntOrNull()?.let{id->JSONObject().put("id",id).put("type",p[0])}}
        conversationContext.put("rejected",JSONArray(rejected))
        conversationContext.put("libraryCounts",JSONObject().put("favorite",library.likes.value.size)
            .put("saved",library.myList.value.size).put("watched",library.recent.value.size))
        if(state.value.keepHistory)conversationContext.put("recentSuggestions",JSONArray(readSuggestions()))
        conversationContext.put("recentProgress",JSONArray((context.applicationContext as AliflixApplication)
            .playbackProgressStore.entries.value.values.sortedByDescending{it.updatedAtMillis}
            .take(8).map { p ->
                talkLibraryIdentity(p.media,"watched").apply{remove("kind")}
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
    private suspend fun post(path: String, value: JSONObject, timeout:Long=12000, expectedOwner:String?=null): JSONObject = withContext(Dispatchers.IO) {
        if(expectedOwner!=null)check(FirebaseAuth.getInstance().currentUser?.uid==expectedOwner)
        val token = identity()
        if(expectedOwner!=null)check(FirebaseAuth.getInstance().currentUser?.uid==expectedOwner)
        val request = Request.Builder().url(BuildConfig.RECOMMENDATION_AI_BASE_URL.trimEnd('/')+"/v1/talk/"+path)
            .header("Authorization","Bearer $token").post(value.toString().toRequestBody("application/json".toMediaType())).build()
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val toolCall=http.newBuilder().callTimeout(timeout,TimeUnit.MILLISECONDS).build().newCall(request)
            continuation.invokeOnCancellation{toolCall.cancel()}
            toolCall.enqueue(object:Callback {
                override fun onFailure(call:Call,e:java.io.IOException){if(continuation.isActive)continuation.resumeWith(Result.failure(e))}
                override fun onResponse(call:Call,response:Response){response.use {
                    try {
                        if(it.code==401)prefs.edit().remove("token").putLong("expires",0).apply()
                        if(it.code==410&&path=="renew")backendSession=null
                        val source=requireNotNull(it.body).source();source.request(128001);require(source.buffer.size<=128000)
                        val payload=JSONObject(source.buffer.readUtf8())
                        check(it.isSuccessful){payload.optString("error").takeIf { value -> value.matches(Regex("[A-Z0-9_]{3,80}")) }?.let { code -> "$code (HTTP ${it.code})" } ?: "HTTP ${it.code}"}
                        if(continuation.isActive)continuation.resumeWith(Result.success(payload))
                    }catch(e:Throwable){if(continuation.isActive)continuation.resumeWith(Result.failure(e))}
                }}
            })
        }
    }
    private suspend fun openSocket(ticket: Long) {
        cancelledTools.clear()
        awaitingInterrupt=false
        update { it.copy(phase=if(reconnects==0) "Connecting" else "Reconnecting") }
        try {
            if(ephemeral==null || ephemeral!!.optLong("expires")<System.currentTimeMillis()+10000) {
                responseLanguage?.let {conversationContext.put("responseLanguage",it)}
                val renewing=backendSession
                val minted=if(renewing!=null)post("renew",JSONObject().put("session",renewing).apply{responseLanguage?.let{put("responseLanguage",it)}})
                    else post("session",JSONObject().put("context",conversationContext))
                if(ticket!=epoch||!permitted)return
                ephemeral=minted;backendSession=minted.getString("session");resumeHandle=null
            }
            val live=checkNotNull(ephemeral)
            require(live.getString("model")=="gemini-3.8-live" && live.getString("voice")==state.value.voice)
            if(ticket!=epoch || !permitted) return
            readyWatchdog?.cancel();readyWatchdog=scope.launch { delay(15000);if(ticket==epoch&&state.value.phase in listOf("Connecting","Reconnecting")) disconnected(ticket,false) }
            val address="wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
            val requestedHandle=resumeHandle
            ws=http.newWebSocket(Request.Builder().url(address).header("Authorization","Token "+live.getString("token")).build(),object:WebSocketListener(){
                override fun onOpen(webSocket:WebSocket,response:Response) {
                    if(ticket!=epoch||!permitted){webSocket.close(1000,null);return}
                    val setup=JSONObject(live.getJSONObject("setup").toString())
                    setup.put("sessionResumption",JSONObject().apply { requestedHandle?.let { put("handle",it) } })
                    webSocket.send(JSONObject().put("setup",setup).toString())
                }
                override fun onMessage(webSocket:WebSocket,text:String) { if(ticket==epoch&&permitted) {
                    if(text.length>1000000){webSocket.close(1009,null);return}
                    val message=runCatching { JSONObject(text) }.getOrNull() ?: return
                    scope.launch(Dispatchers.Main.immediate) {
                        if(ticket==epoch&&permitted)runCatching { receiveLive(message,ticket,!requestedHandle.isNullOrBlank()) }.onFailure { disconnected(ticket,false) }
                    }
                } }
                override fun onMessage(webSocket:WebSocket,bytes:okio.ByteString) { onMessage(webSocket,bytes.utf8()) }
                override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { scope.launch {
                    if(BuildConfig.DEBUG)debugConnectionIssue="socket:${response?.code?:0}:${t.javaClass.simpleName}"
                    if(ticket==epoch && response?.code in listOf(401,403)){ephemeral=null;resumeHandle=null}
                    disconnected(ticket,response?.code==429)
                } }
                override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {
                    // onClosed requires BOTH peers to close. Without this acknowledgement
                    // a remote graceful close can leave the UI waiting indefinitely.
                    webSocket.close(if(code==1005)1000 else code,null)
                    scope.launch {
                        if(BuildConfig.DEBUG)debugConnectionIssue="close:$code"
                        if(ticket==epoch&&(code==1008||reason.contains("resum",true)||reason.contains("handle",true))){ephemeral=null;resumeHandle=null}
                        disconnected(ticket,false)
                    }
                }
                override fun onClosed(webSocket:WebSocket,code:Int,reason:String) { scope.launch {
                    if(ticket==epoch && (code==1008||reason.contains("resum",true)||reason.contains("handle",true))){ephemeral=null;resumeHandle=null}
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
        // A consumed one-use token cannot open a fresh session without a valid
        // resumption handle, especially after its one-minute start window.
        // Mint the same model/voice again and replay bounded dialogue instead.
        if(resumeHandle.isNullOrBlank())ephemeral=null
        val next=++epoch
        reconnectJob?.cancel();reconnectJob=scope.launch { delay((250L shl reconnects).coerceAtMost(2000));openSocket(next) }
    }
    private fun appendTranscript(old:String,chunk:String):String = when {
        chunk.startsWith(old)&&old.isNotEmpty()->chunk
        else->old+chunk
    }.takeLast(6000)
    private fun receiveLive(message:JSONObject,ticket:Long,resuming:Boolean) {
        if(message.has("setupComplete")) { scope.launch(Dispatchers.Main) {
            if(ticket!=epoch||!permitted)return@launch
            readyWatchdog?.cancel();reconnects=0;socketReady=true
            if(continueAfterReconnect){
                continueAfterReconnect=false
                scope.launch {delay(750);if(ticket==epoch&&permitted&&!speaking)emit(JSONObject().put("clientContent",JSONObject().put("turns",JSONArray().put(JSONObject().put("role","user").put("parts",JSONArray().put(JSONObject().put("text","Continue answering the latest user question in the restored dialogue. If audio was interrupted, continue from the last complete sentence. Keep the established language, title and spoiler boundaries. Avoid repeating completed speech or acknowledging this reconnection."))))).put("turnComplete",true)))}
            }
            if(state.value.lines.isNotEmpty()) {
                val restored=if(textSend?.isActive==true&&state.value.lines.lastOrNull()?.role=="user")state.value.lines.dropLast(1) else state.value.lines
                val history=talkContinuityTurns(restored,resuming)
                emit(JSONObject().put("clientContent",JSONObject().put("turns",history).put("turnComplete",false)))
            }
            try { if(!state.value.textMode)startAudio(ticket);debugReadyGeneration=ticket;update{it.copy(phase="Listening",provider="gemini-3.8-live · ${it.voice}")} }
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
            val names=(0 until calls.length()).map{calls.getJSONObject(it).optString("name")}
            for(i in 0 until calls.length()) {
                val call=calls.getJSONObject(i);val id=call.getString("id")
                val controlScheduling=talkControlScheduling(names,i,speaking||outputText.isNotBlank(),tools.isNotEmpty())
                if(BuildConfig.DEBUG)debugToolCalls.merge(call.optString("name"),1){a,b->a+b}
                if(call.optString("name")=="conversation_language") {
                    val args=call.optJSONObject("args")?:JSONObject()
                    responseLanguage=chooseTalkResponseLanguage(responseLanguage,args.optString("language"),args.optBoolean("explicitRequest"),inputText.ifBlank{lastQuestion})
                    val result=JSONObject().put("language",responseLanguage?:"und").put("locked",responseLanguage!=null)
                        .put("instruction","Use this response language for every answer until the user explicitly requests a different one. Titles and quotations do not change it. Continue answering the user's question directly; never acknowledge this control.")
                    emit(JSONObject().put("toolResponse",JSONObject().put("functionResponses",JSONArray().put(
                        talkFunctionResponse(id,"conversation_language",result,controlScheduling)))))
                    continue
                }
                if(call.optString("name") in setOf("aliflix_app","remember_recommendations")) {
                    val result=if(call.optString("name")=="aliflix_app")talkAppContext(context) else {
                        val a=call.optJSONObject("args")?.optJSONArray("titles")?:JSONArray()
                        val titles=(0 until minOf(a.length(),8)).mapNotNull{validatedTalkTopic(a.optString(it))}
                        if(state.value.keepHistory)prefs.edit().putString("suggestions",JSONArray((titles+readSuggestions()).distinct().take(24)).toString())
                            .putLong("suggestions-at",System.currentTimeMillis()).apply()
                        JSONObject().put("accepted",true)
                    }
                    val scheduling=if(call.optString("name")=="aliflix_app")"WHEN_IDLE" else "SILENT"
                    emit(JSONObject().put("toolResponse",JSONObject().put("functionResponses",JSONArray().put(
                        talkFunctionResponse(id,call.getString("name"),result,scheduling)))))
                    continue
                }
                if(call.optString("name")=="conversation_topic") {
                    val topic=validatedTalkTopic(call.optJSONObject("args")?.optString("topic").orEmpty())
                    if(topic!=null)update{it.copy(words=topic)}
                    emit(JSONObject().put("toolResponse",JSONObject().put("functionResponses",JSONArray().put(
                        talkFunctionResponse(id,"conversation_topic",JSONObject().put("accepted",topic!=null).put("instruction","This changes only the visual theme. Continue the user's question directly, without announcing this control."),controlScheduling)))))
                    continue
                }
                if(call.optString("name")=="film_knowledge") {
                    if(BuildConfig.DEBUG)debugKnowledgeRequest=call.optJSONObject("args")?.toString().orEmpty()
                    val title=call.optJSONObject("args")?.optString("title").orEmpty();rememberFilmTopic(title)
                    validatedTalkTopic(title)?.let{topic->update{it.copy(words=topic)}}
                }
                if(call.optString("name") !in setOf("film_knowledge","viewing_context","web_research","recommendations")||tools.size>=2) {
                    emit(JSONObject().put("toolResponse",JSONObject().put("functionResponses",JSONArray().put(talkFunctionResponse(id,call.optString("name"),JSONObject().put("unavailable",true).put("retryable",true),"WHEN_IDLE")))))
                    continue
                }
                update{it.copy(phase=if(speaking)it.phase else "Thinking")}
                val toolRequestId=java.util.UUID.randomUUID().toString()
                val toolTurn=userTurn.get()
                val toolQuestion=inputText.ifBlank { lastQuestion }
                val toolSession=ephemeral!!.getString("session")
                if(call.optString("name") in setOf("web_research","recommendations")) answerDeadline.awaiting(70000)
                tools[id]=scope.launch {
                    val result=runCatching {
                        if(call.optString("name")=="viewing_context") {
                            val args=call.optJSONObject("args")?:JSONObject()
                            val library=(context.applicationContext as AliflixApplication).libraryStore
                            val collection=args.optString("collection","taste")
                            val items=when(collection) {
                                "saved"->library.myList.value.map{it to "saved"}
                                "watched"->library.recent.value.map{it to "watched"}
                                "favorite"->library.likes.value.map{it to "favorite"}
                                else->(library.likes.value.map{it to "favorite"}+library.myList.value.map{it to "saved"}+
                                    library.recent.value.map{it to "watched"}).distinctBy{it.first.key}
                            }
                            val offset=args.optInt("offset",0).coerceIn(0,100000)
                            post("viewing",JSONObject().put("session",toolSession)
                                .put("items",JSONArray(items.drop(offset).take(8).map{(m,k)->talkLibraryIdentity(m,k)}))
                                .put("page",JSONObject().put("collection",collection).put("offset",offset).put("total",items.size)))
                        }
                        else if(call.optString("name") in setOf("web_research","recommendations")) post(if(call.optString("name")=="web_research")"research" else "recommendations",
                            JSONObject().put("session",toolSession).put("requestId",toolRequestId).put("question",toolQuestion.take(1600)).put("request",call.getJSONObject("args")),70000)
                        else post("knowledge",JSONObject().put("session",toolSession)
                            .put("question",toolQuestion.take(1600)).put("request",call.getJSONObject("args")))
                    }
                        .getOrElse { error ->
                            if(BuildConfig.DEBUG&&call.optString("name")=="recommendations")debugRecommendationIssue=error.javaClass.simpleName+":"+error.message.orEmpty().take(120)
                            if(error is CancellationException) {
                                withContext(NonCancellable) {runCatching {post("cancel",JSONObject().put("session",toolSession).put("requestId",toolRequestId))}}
                                throw error
                            }
                            JSONObject().put("unavailable",true).put("evidence",JSONArray()).put("instruction","This lookup is unavailable. Do not pretend you browsed or invent facts. Continue naturally with reliable existing knowledge.")
                        }
                    if(ticket==epoch&&permitted&&toolTurn==userTurn.get()&&id !in cancelledTools) {
                        if(BuildConfig.DEBUG){
                            if(call.optString("name")=="film_knowledge")debugEpisodeEvidence=result.optJSONArray("evidence")?.toString().orEmpty()
                            if(call.optString("name")=="web_research")debugWebEvidenceCount=result.optJSONArray("evidence")?.length()?:0
                            if(call.optString("name")=="recommendations"){
                                val rows=result.optJSONArray("results")?:JSONArray();debugRecommendationCount=rows.length()
                                if(rows.length()==0&&debugRecommendationIssue.isBlank())debugRecommendationIssue="Empty results; unavailable="+result.optBoolean("unavailable")+" available="+result.optBoolean("available")
                                debugRecommendationYears=(0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("year")?.toIntOrNull() }
                            }
                        }
                        result.optJSONArray("results")?.let{a->for(n in 0 until minOf(8,a.length()))rememberFilmTopic(a.optJSONObject(n)?.optString("title").orEmpty())}
                        val references=result.optJSONArray("evidence")
                        finalReferences=references?.let {a->(0 until a.length()).map {n->a.getJSONObject(n).let {TalkReference(it.getString("title"),it.getString("url"),it.optString("license",it.optString("attribution","Source")))}}}.orEmpty()
                        // Episode facts must inform this answer, not arrive after an
                        // ungrounded recap has already finished. Lookup remains async.
                        val response=talkFunctionResponse(id,call.getString("name"),result,if(call.optString("name")=="film_knowledge"||!turnHasAudio)"INTERRUPT" else "WHEN_IDLE")
                        if(BuildConfig.DEBUG&&call.optString("name")=="film_knowledge")debugKnowledgeAppliedAt=SystemClock.elapsedRealtime()
                        emit(JSONObject().put("toolResponse",JSONObject().put("functionResponses",JSONArray().put(response))))
                    }
                    tools.remove(id)
                    if(toolTurn==userTurn.get()&&tools.isEmpty()&&!turnHasAudio)answerDeadline.awaiting(12000)
                }
            }
        }
        message.optJSONObject("serverContent")?.let { content->
            if(content.optBoolean("interrupted")) {
                debugInterruptions++;sequence++;interruptOutput();assistantLine=-1;outputText="";finalReferences=emptyList()
                // FunctionResponse INTERRUPT can also interrupt model output.
                // Gemini's explicit toolCallCancellation IDs own tool cancellation;
                // a late audio interruption must not cancel a newer lookup.
                update{it.copy(phase=if(muted) "Muted" else "Listening")}
                return@let
            }
            content.optJSONObject("inputTranscription")?.optString("text")?.let { if(!inputTurnActive)beginUserTurn();if(inputText.isBlank())turnHasAudio=false;inputText=appendTranscript(inputText,it);answerDeadline.awaiting();update{v->v.copy(phase=if(speaking) v.phase else "Thinking")} }
            content.optJSONObject("outputTranscription")?.optString("text")?.let { text->
                responseTurn=userTurn.get()
                inputTurnActive=false;answerDeadline.clear()
                outputText=appendTranscript(outputText,text)
                commitQuestion()
                update { v->
                    val lines=v.lines.toMutableList();val line=TalkLine("assistant",outputText,finalReferences)
                    if(assistantLine in lines.indices)lines[assistantLine]=line else{lines.add(line);assistantLine=minOf(lines.lastIndex,63)}
                    v.copy(lines=lines.takeLast(64))
                }
            }
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts->
                if(parts.length()>0){endOfAudio=false;commitQuestion()}
                for(i in 0 until parts.length()) {
                val data=parts.getJSONObject(i).optJSONObject("inlineData")?:continue
                if(!data.optString("mimeType").startsWith("audio/pcm"))continue
                val bytes=Base64.decode(data.getString("data"),Base64.NO_WRAP)
                if(bytes.isNotEmpty())lastOutputAt=SystemClock.elapsedRealtime()
                if(bytes.isNotEmpty()){turnHasAudio=true;answerDeadline.clear()}
                if(bytes.isNotEmpty())responseTurn=userTurn.get()
                require(bytes.size<=192000&&bytes.size%2==0)
                val buffer=audio?:continue
                val reserved=buffer.bytes.addAndGet(bytes.size)
                if(reserved>4_800_000 || buffer.chunks.trySend(sequence to bytes).isFailure) {
                    buffer.bytes.addAndGet(-bytes.size)
                    if(BuildConfig.DEBUG)android.util.Log.w("AliflixTalk","Bounded output queue exhausted")
                    scope.launch {disconnected(ticket,false)};break
                }
            } }
            // Native generation completion can precede turnComplete while tools
            // settle. It is a finished audio stream, never a stalled answer.
            if(content.optBoolean("generationComplete")){endOfAudio=true;if(turnHasAudio)answerDeadline.clear()}
            if(content.optBoolean("turnComplete")&&responseTurn==userTurn.get()&&(turnHasAudio||outputText.isNotBlank())) {
                update{it.copy(words=completedTalkTopic(outputText,it.words,synchronized(knownFilmTopics){knownFilmTopics.toList()}))}
                inputTurnActive=false;update { it.copy(sending=false,phase=if(it.textMode)"Listening" else it.phase) }
                debugTurnCompleteAt=SystemClock.elapsedRealtime();endOfAudio=true;assistantLine=-1;outputText="";finalReferences=emptyList()
                commitQuestion()
                persistHistory()
            }
        }
    }
    private fun readSuggestions():List<String> = runCatching {
        if(System.currentTimeMillis()-prefs.getLong("suggestions-at",0)>7*86400000L)emptyList() else {
            val a=JSONArray(prefs.getString("suggestions","[]"));(0 until minOf(a.length(),24)).map{a.getString(it)}
        }
    }.getOrDefault(emptyList())
    private fun commitQuestion() {
        if(inputText.isBlank())return
        stalledRecoveries=0
        lastQuestion=inputText;inputText="";turnHasAudio=false
        explicitRequestedTalkLanguage(lastQuestion)?.let{responseLanguage=it}
        addLine(TalkLine("user",lastQuestion))
        val subject=topicWords(lastQuestion,"",state.value.words);rememberFilmTopic(subject)
        update{it.copy(words=subject)}
    }
    private fun addLine(line:TalkLine){if(line.text.isNotBlank())update{it.copy(lines=(it.lines+line).takeLast(64))}}
    private fun persistHistory(){
        if(!state.value.keepHistory||state.value.lines.isEmpty()||sessionOwner!=(FirebaseAuth.getInstance().currentUser?.uid?:"guest"))return
        val title=state.value.lines.firstOrNull { it.role=="user" }?.text?.take(80) ?: "Conversation"
        history.save(TalkChat(chatId,title,System.currentTimeMillis(),state.value.lines))
    }
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
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(24_000, AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 3)).build()
        output = track; writtenSamples=0;primingFrom=0;primingBegan=0;debugRenderedBaseline=0;endOfAudio=false;outputEnergy=0.0
        val buffer=AudioBuffer();val queue=buffer.chunks;audio=buffer
        outputJob = scope.launch(Dispatchers.IO) {
            try {for ((seq, bytes) in queue) {
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
                    if (wrote < 0) {if(seq==sequence&&ticket==epoch&&isActive)withContext(Dispatchers.Main){disconnected(ticket,false)};break}
                    if (wrote == 0) { delay(5); continue }
                    at += wrote
                    if(primingBegan==0L)primingBegan=SystemClock.elapsedRealtime()
                    // A bounded 120ms reservoir absorbs packet jitter; short
                    // completed replies start immediately. Playback callbacks
                    // never wait for network or research work.
                    if(track.playState!=AudioTrack.PLAYSTATE_PLAYING && (writtenSamples-primingFrom>=2880||endOfAudio))
                        synchronized(outputLock){if(seq==sequence&&ticket==epoch)runCatching{track.play()}}
                }
                } finally {buffer.writing=false}
            }}catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(ticket==epoch&&permitted)withContext(Dispatchers.Main){disconnected(ticket,false)}}
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
                if(writtenSamples>primingFrom&&track.playState!=AudioTrack.PLAYSTATE_PLAYING&&(endOfAudio||primingBegan>0&&SystemClock.elapsedRealtime()-primingBegan>=120))synchronized(outputLock){runCatching{track.play()}}
                if(speaking&&endOfAudio&&!buffer.writing&&queue.isEmpty&&(track.playbackHeadPosition.toLong() and 0xffffffffL)>=writtenSamples){
                    synchronized(outputLock){runCatching{track.pause()};primingFrom=writtenSamples;primingBegan=0}
                    speaking=false;update{it.copy(phase=if(muted) "Muted" else "Listening")}
                }
                if(speaking&&!endOfAudio&&tools.isEmpty()&&!buffer.writing&&queue.isEmpty&&lastOutputAt>0&&SystemClock.elapsedRealtime()-lastOutputAt>5000&&(track.playbackHeadPosition.toLong() and 0xffffffffL)>=writtenSamples){
                    if(stalledRecoveries++<2){debugStallRecoveries++;continueAfterReconnect=true;withContext(Dispatchers.Main){disconnected(ticket,false)}}
                    else withContext(Dispatchers.Main){suspendSession();update{it.copy(phase="Reconnect",level=0f)}}
                    break
                }
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
        synchronized(outputLock) { runCatching { output?.pause(); output?.flush(); writtenSamples = 0;primingFrom=0;primingBegan=0; debugRenderedBaseline = 0 } }
    }
    internal val debugPlayedSamples get() = writtenSamples
    fun selectVoice(voice:String) {
        if(voice !in VOICES||voice==state.value.voice||closed)return
        val oldSession=ephemeral?.optString("session")
        suspendSession();ephemeral=null;backendSession=null;resumeHandle=null
        update{it.copy(voice=voice,phase="Ready")}
        prefs.edit().putString("voice",voice).apply()
        // An explicit voice choice creates a fresh constrained token. In-memory
        // dialogue is carried over; reconnects never select another voice.
        scope.launch {connect()}
        if(!oldSession.isNullOrBlank())scope.launch {runCatching{post("close",JSONObject().put("session",oldSession))}}
    }
    internal fun debugInterrupt(){check(BuildConfig.DEBUG);sequence++;interruptOutput();emit(JSONObject().put("realtimeInput",JSONObject().put("text","Stop speaking.")))}
    internal fun debugReconnect(){check(BuildConfig.DEBUG);update{it.copy(phase="Reconnecting")};cancelSocket(ws)}
    internal suspend fun debugInjectText(text:String){check(BuildConfig.DEBUG);muted=true;update{it.copy(muted=true)};if(!permitted)connect();withTimeout(20000){while(captureJob?.isActive!=true)delay(20)};beginUserTurn();inputText=text;commitQuestion();answerDeadline.awaiting();debugFirstAudioAt=0;debugTurnCompleteAt=0;debugRenderedBaseline=output?.playbackHeadPosition?.toLong()?.and(0xffffffffL)?:0L;endOfAudio=false;emit(JSONObject().put("realtimeInput",JSONObject().put("text",text)))}
    internal suspend fun debugInjectUtterance(pcm:ByteArray){check(BuildConfig.DEBUG&&pcm.size in 6400..960000&&pcm.size%2==0);muted=true;update{it.copy(muted=true)};if(!permitted)connect();withTimeout(20000){while(captureJob?.isActive!=true)delay(20)};debugFirstAudioAt=0;debugTurnCompleteAt=0;debugRenderedBaseline=output?.playbackHeadPosition?.toLong()?.and(0xffffffffL)?:0L;for(at in pcm.indices step 3200){sendPcm(pcm.copyOfRange(at,minOf(at+3200,pcm.size)));delay(100)};repeat(15){sendPcm(ByteArray(3200));delay(100)};emit(JSONObject().put("realtimeInput",JSONObject().put("audioStreamEnd",true)))}
    fun toggleMute(){muted=!muted;if(muted&&captureJob?.isActive==true)emit(JSONObject().put("realtimeInput",JSONObject().put("audioStreamEnd",true)));update{it.copy(muted=muted,phase=if(it.phase in listOf("Connecting","Reconnecting"))it.phase else if(muted) "Muted" else if(speaking) "Speaking" else "Listening")}}
    fun setHistory(keep:Boolean){prefs.edit().putBoolean("keep-history",keep).apply();update{it.copy(keepHistory=keep)};if(keep)persistHistory()else prefs.edit().remove("history").remove("suggestions").remove("suggestions-at").apply()}
    fun clearHistory(){
        history.clear();userTurn.incrementAndGet();chatId=java.util.UUID.randomUUID().toString();
        synchronized(knownFilmTopics){knownFilmTopics.clear()}
        sequence++;interruptOutput();val session=ephemeral?.optString("session")
        resumeHandle=null;ephemeral=null;backendSession=null;inputText="";outputText="";assistantLine=-1;finalReferences=emptyList()
        prefs.edit().remove("history").remove("suggestions").remove("suggestions-at").apply();update{it.copy(lines=emptyList(),words="ALIFLIX",conversationId=chatId)}
        val next=++epoch;readyWatchdog?.cancel();reconnectJob?.cancel();stopAudio();cancelSocket(ws);ws=null
        scope.launch {
            if(!session.isNullOrBlank())runCatching{post("close",JSONObject().put("session",session).put("forget",true))}
            if(permitted&&next==epoch)openSocket(next)
        }
    }
    private fun stopAudio() {
        answerDeadline.clear()
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
        persistHistory();socketReady=false;permitted=false;awaitingInterrupt=false;epoch++
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
        update {it.copy(phase="Resume",level=0f,sending=false)}
    }
    fun pauseSession() { archiveReadSequence++;textSend?.cancel();textSend=null;suspendSession() }
    override fun close() {
        if(closed)return
        closed=true
        FirebaseAuth.getInstance().removeAuthStateListener(accountListener)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        textSend?.cancel()
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
    val filler=setOf("earlier","previously","later","again","before","after","okay","ok","just","yes","no","sure","alright","thanks","thank","you","tell","me","more","continue","please","one","two","sentence","brief","briefly","succinctly","quickly","shortly","in","a","an","the","detail","details","about","that","this","short","answer","weiter","kurz","satz","فقط","باختصار","جملة","hello","hallo","ja","nein","bitte","danke","نعم","حسنا","شكرا")
    if(tokens.isEmpty()||tokens.size>6||tokens.all{it in filler}||tokens.distinct().size==1&&tokens.size>1)return null
    return text.uppercase(Locale.ROOT)
}
internal fun topicWords(question:String,answer:String,previous:String="ALIFLIX"):String =
    talkUtteranceTopic(question.ifBlank{answer})?:previous

/** A foreign title or a mixed-language phrase cannot unlock an established language. */
internal fun chooseTalkResponseLanguage(current:String?,requested:String,explicitRequest:Boolean,userText:String):String? {
    if(current!=null&&(!explicitRequest||!explicitTalkLanguageRequest(userText,requested)))return current
    val code=requested.trim().lowercase(Locale.ROOT)
    return code.takeIf{it.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,2}"))}?:current
}

/** Resolve a completed referential answer against bounded known film identities.
 * Streaming words never drive the display; ambiguous multi-title replies retain
 * the model's established semantic topic. */
internal fun completedTalkTopic(answer:String,previous:String,knownTitles:Collection<String>):String {
    fun normalized(v:String)=" "+v.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()+" "
    val text=normalized(answer)
    return knownTitles.filter{text.contains(normalized(it))}.singleOrNull()?:previous
}

/** Model tool arguments alone cannot authorize changing an established language. */
private val talkLanguageNames=mapOf("en" to "english|englisch|الإنجليزية|الانجليزية|الإنجليزي|الانجليزي", "de" to "german|deutsch|الألمانية|الالمانية", "ar" to "arabic|arabisch|العربية|بالعربي|عربي", "es" to "spanish|español|spanisch|الإسبانية", "fr" to "french|français|französisch|الفرنسية", "it" to "italian|italiano|italienisch", "pt" to "portuguese|português", "ja" to "japanese|日本語", "ko" to "korean|한국어", "ru" to "russian|русский", "zh" to "chinese|中文", "tr" to "turkish|türkçe", "hi" to "hindi|हिन्दी", "nl" to "dutch|nederlands", "pl" to "polish|polski")
private val negatedTalkLanguage=Regex("(?i)(?:do not|don't|never|nicht|لا)\\s+(?:switch|change|speak|answer|wechsel|sprich|تتحدث|تغير)")
private val talkLanguageRequests=talkLanguageNames.mapValues{(_,language)->
    val en="(?:answer|respond|reply|speak|talk|continue|switch|use|communicate)(?:\\s+(?:to me|from now on|now|only|please|again)){0,3}\\s+(?:(?:in|to|using)\\s+)?(?:$language)"
    val de="(?:antworte|antworten|sprich|sprechen|rede|wechsel)(?:\\s+(?:ab jetzt|bitte|nun|nur)){0,3}\\s+(?:auf\\s+)?(?:$language)"
    val ar="(?:أجب|اجب|تحدث|تكلم|رد)(?:\\s+(?:الآن|الان|من الآن|فقط)){0,3}\\s+(?:ب(?:اللغة)?\\s*)?(?:$language)"
    val romance="(?:habla|responde|contesta|continúa|cambia|parle|réponds|répondez|parla|rispondi|continua)(?:\\s+(?:solo|ahora|por favor|maintenant)){0,3}\\s+(?:(?:en|in)\\s+)?(?:$language)"
    Regex("(?i)(?:$en|$de|$ar|$romance)")
}
internal fun explicitTalkLanguageRequest(text:String,code:String):Boolean =
    !negatedTalkLanguage.containsMatchIn(text)&&talkLanguageRequests[code.lowercase(Locale.ROOT).substringBefore('-')]?.containsMatchIn(text)==true
internal fun explicitRequestedTalkLanguage(text:String):String? =
    if(negatedTalkLanguage.containsMatchIn(text))null else talkLanguageRequests.entries.firstOrNull{it.value.containsMatchIn(text)}?.key
