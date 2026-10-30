package com.aliflix.app.talk

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.player.NativePlayerActivity
import com.aliflix.app.player.nativeJson
import com.aliflix.app.player.nativePhoneLaunchOptions
import com.aliflix.app.player.withPlaybackRoomConsent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

private fun talkTaskAccount() = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: "guest"

/** Tasks belong to the application, so navigation or a spoken interruption cannot drop a step. */
internal class TalkTaskWork private constructor(private val app: AliflixApplication) {
    companion object {
        @Volatile private var instance: TalkTaskWork? = null
        fun get(context: Context): TalkTaskWork = instance ?: synchronized(this) {
            instance ?: TalkTaskWork(context.applicationContext as AliflixApplication).also { instance = it }
        }
    }
    private val execution = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val requests = linkedMapOf<String,String>()
    fun existingRequest(key:String)=requests[key].takeIf{key.isNotBlank()}
    private val conversations = mutableMapOf<String,TalkConversationHandoff>()
    fun conversation(id:String)=conversations[id]
    private val _tasks = MutableStateFlow<List<JSONObject>>(emptyList())
    val tasks = _tasks.asStateFlow()
    private var owner = talkTaskAccount()
    init {
        runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val next = auth.currentUser?.uid ?: "guest"
            if (owner != next) {
                jobs.values.toList().forEach { it.cancel() }; jobs.clear(); requests.clear(); conversations.clear(); TalkConversationRoutes.clear(); _tasks.value = emptyList(); owner = next
            }
        } }
    }
    private fun update(id: String, change: (JSONObject) -> Unit) {
        _tasks.value = _tasks.value.map { old -> if (old.optString("taskId") != id) old else JSONObject(old.toString()).also(change) }
    }
    fun state(id: String? = null): JSONObject = if (id.isNullOrBlank()) JSONObject().put("status", "current").put("tasks", JSONArray(_tasks.value))
        else _tasks.value.firstOrNull { it.optString("taskId") == id }?.let { JSONObject(it.toString()) }
            ?: JSONObject().put("status", "not_found")
    fun cancel(id: String): JSONObject {
        val job = jobs[id] ?: return state(id)
        job.cancel()
        update(id) { it.put("status", "cancelled") }
        return state(id)
    }
    suspend fun await(id: String): JSONObject { jobs[id]?.join(); return state(id) }

    fun start(context: Context, steps: List<JSONObject>, cards: List<TalkTitleCard>, requestKey:String="", playbackMedia:Map<String,com.aliflix.app.model.Media> = emptyMap()): String {
        existingRequest(requestKey)?.let{return it}
        require(jobs.size < 8) { "Finish or cancel an active task first" }
        val id = UUID.randomUUID().toString()
        if(requestKey.isNotBlank()){requests[requestKey]=id;while(requests.size>64)requests.remove(requests.keys.first())}
        val handoff=when(context){is TalkVoiceActivity->context.voice.handoff();is NativePlayerActivity->context.talkHandoff();else->null}
        if(handoff!=null)conversations[id]=handoff
        conversations.keys.retainAll(_tasks.value.map{it.optString("taskId")}.toSet()+id)
        val activity = WeakReference(context as? Activity)
        val account = talkTaskAccount()
        _tasks.value = (_tasks.value.filter { it.optString("taskId") in jobs } + _tasks.value.filter { it.optString("taskId") !in jobs }.takeLast(8) + JSONObject().put("taskId", id).put("status", "queued")
            .put("conversationId",handoff?.state?.conversationId)
            .put("commands", talkTaskJson(steps)).put("results", JSONArray()).put("cancellable",true))
        val job = app.accountScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            val results = JSONArray()
            try {
                execution.withLock {
                    update(id) { it.put("status", "running") }
                    var expected: String? = null
                    var targetChanged = false
                    var subtitlesReady = true
                    var audioReady = true
                    val native = TalkNativeActions(app, TalkCatalogue { TalkCardContext(known = cards) }) {}
                    suspend fun playerStep(host:TalkPlayerHost,command:JSONObject):JSONObject {
                        var result=host.talkCommand(command)
                        if(result.optString("status")=="verification_required") {
                            update(id){it.put("status","awaiting_user_input")}
                            host.talkCommand(JSONObject().put("action","verify_provider").put("expectedContentKey",expected))
                            withTimeout(300_000) {
                                while(host.talkSnapshot().optBoolean("verificationRequired")) {
                                    check(host.talkSnapshot().optString("contentKey")==expected){"Playback changed"}
                                    delay(200)
                                }
                            }
                            update(id){it.put("status","running")}
                            result=host.talkCommand(command)
                        }
                        return result
                    }
                    if (steps.none { it.optString("action") == "play" } && steps.any { it.optString("action") in talkPlayerCommands } && TalkPlayerConnection.current() == null) {
                        val owner = requireNotNull(activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }) { "Open the player to continue this task" }
                        owner.startActivity(Intent(owner, NativePlayerActivity::class.java).putExtra("talkTaskId", id), nativePhoneLaunchOptions(owner))
                    }
                    for ((index, command) in steps.withIndex()) {
                        ensureActive()
                        check(account == talkTaskAccount()) { "Account changed" }
                        val action = command.getString("action")
                        update(id) { it.put("currentStep", index) }
                        val result = if (targetChanged) JSONObject().put("status", "skipped_target_changed")
                        else if (action == "sync_subtitles" && !subtitlesReady) JSONObject().put("status", "skipped_subtitle_unavailable")
                        else if (action == "sync_subtitles" && !audioReady) JSONObject().put("status", "skipped_audio_unavailable")
                        else if (action == "resume" && !audioReady) JSONObject().put("status", "blocked_audio_unavailable")
                        else if (action == "resume" && !subtitlesReady) JSONObject().put("status", "blocked_subtitle_unavailable")
                        else try {
                            if (action == "play") {
                                val card = cards.single { it.id == command.getInt("tmdbId") && it.type.routeName == command.getString("kind") }
                                val requested = TalkEpisodeArguments.from(command)
                                requested.requireKind(card.type)
                                val media=playbackMedia[card.key]?:card.media()
                                val base = PlaybackSelection(media, seasonNumber = requested.season, episodeNumber = requested.episode)
                                val unfinished = app.playbackProgressStore.entries.value.values.filter { it.media.key == card.key && !it.completed && it.positionSeconds > 0 }
                                    .maxByOrNull { it.updatedAtMillis }
                                val resumed = if (card.type == MediaType.TV && requested.season == null && requested.episode == null && unfinished != null)
                                    base.copy(seasonNumber = unfinished.seasonNumber, episodeNumber = unfinished.episodeNumber) else base
                                val selection = resumed.copy(source = app.playbackProviderRepository.preferences.value.sourceFor(media))
                                expected = selection.key.substringBefore(":via:")
                                val owner = requireNotNull(activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }) { "Open Aliflix to start playback" }
                                val approved = CompletableDeferred<Unit>()
                                withPlaybackRoomConsent(owner, selection) {
                                    app.libraryStore.markPlayed(media)
                                    owner.startActivity(Intent(owner, NativePlayerActivity::class.java)
                                        .putExtra("selection", selection.nativeJson()).putExtra("talkTaskId", id)
                                        .putExtra("talkPreparePaused",steps.size>1)
                                        .putExtra("playTapElapsedMs", android.os.SystemClock.elapsedRealtime()), nativePhoneLaunchOptions(owner))
                                    approved.complete(Unit)
                                }
                                withTimeout(90_000) { approved.await() }
                                val host = TalkPlayerConnection.await(expected,id)
                                playerStep(host,JSONObject().put("action", if(steps.size>1)"await_prepared" else "await_playback").put("expectedContentKey", expected))
                            } else if (action in talkPlayerCommands) {
                                val host = TalkPlayerConnection.await(expected)
                                if (expected == null) expected = host.talkSnapshot().optString("contentKey")
                                playerStep(host,JSONObject(command.toString()).put("expectedContentKey", expected))
                            } else native.execute(command).value
                        } catch (_: TimeoutCancellationException) { JSONObject().put("status", "timed_out") }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { JSONObject().put("status", "failed").put("reason", error.message.orEmpty().take(180)) }
                        if(action=="skip_intro"&&command.optBoolean("ifAvailable")&&result.optString("status")=="intro_unavailable")
                            result.put("status","not_applicable").put("originalStatus","intro_unavailable")
                        if (action == "subtitle_language") subtitlesReady = talkTaskSuccess(result.optString("status"))
                        if (action == "audio_language") audioReady = talkTaskSuccess(result.optString("status"))
                        if (action == "play" && !talkTaskSuccess(result.optString("status"))) targetChanged = true
                        if (result.optString("status") in setOf("playback_changed", "no_active_playback")) targetChanged = true
                        // Episode selection intentionally establishes the next content identity.
                        if (action in setOf("select_episode", "next_episode") && talkTaskSuccess(result.optString("status"))) expected = result.optString("contentKey", expected.orEmpty())
                        results.put(JSONObject().put("step", index).put("action", action).put("result", result))
                        update(id) { it.put("results", JSONArray(results.toString())) }
                    }
                    val succeeded = (0 until results.length()).count { talkTaskSuccess(results.getJSONObject(it).getJSONObject("result").optString("status")) }
                    val needsInput=(0 until results.length()).any{results.getJSONObject(it).getJSONObject("result").optString("status")=="user_input_required"}
                    update(id) { it.put("status", if(needsInput)"awaiting_user_input" else if (succeeded == steps.size) "completed" else if (succeeded > 0) "partial" else "failed")
                        .put("completedSteps", succeeded).put("instruction", "Report each actual result. Never omit an unavailable or failed requested step, or equate queued with completed.") }
                }
            } catch (cancelled: CancellationException) {
                update(id) { it.put("status", "cancelled") }; throw cancelled
            } catch (error: Exception) { update(id) { it.put("status", "failed").put("reason", error.message.orEmpty().take(180)) } }
            finally { jobs.remove(id);update(id){it.put("cancellable",false)} }
        }
        jobs[id] = job
        job.start()
        return id
    }
}
