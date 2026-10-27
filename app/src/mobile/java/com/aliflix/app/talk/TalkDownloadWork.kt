@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.talk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.media3.exoplayer.offline.Download
import com.aliflix.app.AliflixApplication
import com.aliflix.app.BuildConfig
import com.aliflix.app.data.MobileEpisodeRepository
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.downloads.*
import com.aliflix.app.model.*
import com.aliflix.app.recommendation.RecommendationAiClient
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

internal data class TalkDownloadJob(val id:String,val card:TalkTitleCard,val season:Int?,val episode:Int?,val quality:String,val subtitles:String,
    val stage:String="Queued",val progress:Float?=null,val detail:String="",val nativeSeen:Boolean=false) {
    val active get()=stage !in setOf("Completed","Cancelled","Failed","Verification required")
    fun json()=JSONObject().put("id",id).put("card",card.json()).put("season",season).put("episode",episode)
        .put("quality",quality).put("subtitles",subtitles).put("stage",stage).put("progress",progress).put("detail",detail).put("nativeSeen",nativeSeen)
}

internal fun talkDownloadSubtitleLanguage(value:String):String {
    val code=value.trim().lowercase(java.util.Locale.ROOT)
    if(code in setOf("","off","none"))return ""
    require(SubtitleLanguage.entries.any{it.code.equals(code,true)}){"Unsupported subtitle language"}
    return code
}

/** Device-owned preparation, then the existing Media3 foreground download
 * service. A voice turn/socket never owns or cancels an explicitly queued job.
 * Pending specifications survive process death; source URLs never enter Talk. */
internal class TalkDownloadWork private constructor(private val app:AliflixApplication) : Application.ActivityLifecycleCallbacks {
    private val prefs=app.getSharedPreferences("talk-download-work",0)
    private val scope=app.accountScope
    private var owner=FirebaseAuth.getInstance().currentUser?.uid?:"guest"
    private var ownerRevision=0L
    private val mutable=MutableStateFlow<List<TalkDownloadJob>>(emptyList())
    val jobs=mutable.asStateFlow()
    private val running=mutableMapOf<String,Job>()
    private val preparationOwners=mutableMapOf<String,WeakReference<ComponentActivity>>()
    private var activity=WeakReference<ComponentActivity>(null)
    private val store by lazy{OfflineDownloads.get(app)}
    init {
        restore()
        app.registerActivityLifecycleCallbacks(this)
        FirebaseAuth.getInstance().addAuthStateListener{auth->
            val next=auth.currentUser?.uid?:"guest"
            if(next!=owner){ownerRevision++;running.values.toList().forEach{it.cancel()};running.clear();preparationOwners.clear();owner=next;restore();pump()}
        }
        scope.launch {combine(store.entries,store.loaded){_,loaded->loaded}.collect{loaded->
            if(!loaded)return@collect
            val entries=store.entries.value
            var changed=false
            mutable.value=mutable.value.map{job->
                val saved=entries.firstOrNull{it.id==job.id}
                if(saved==null){
                    if(job.nativeSeen&&job.stage in setOf("Downloading","Paused","Waiting for network","Completed")){changed=true;job.copy(stage="Cancelled",progress=null)}else job
                }else if(job.stage in setOf("Cancelled","Failed","Verification required"))job else {
                    val next=adopt(job,saved)
                    changed=changed||next.stage!=job.stage||!job.nativeSeen
                    next
                }
            }
            if(changed)persist()
            pump()
        }}
    }
    private fun restore(){
        mutable.value=runCatching{
            val rows=JSONArray(prefs.getString(owner,"[]"))
            (0 until rows.length()).map{rows.getJSONObject(it)}.map{row->
                val card=parseTalkTitles(JSONArray().put(row.getJSONObject("card")),1).single()
                val stage=row.getString("stage")
                TalkDownloadJob(row.getString("id"),card,row.optInt("season").takeIf{row.has("season")&&!row.isNull("season")},row.optInt("episode").takeIf{it>0},row.getString("quality"),row.getString("subtitles"),
                    if(stage in setOf("Resolving","Captions","Starting","Queued"))"Queued" else stage,nativeSeen=row.optBoolean("nativeSeen")||stage in setOf("Downloading","Paused","Waiting for network","Completed"))
            }
        }.getOrDefault(emptyList())
    }
    private fun persist(){prefs.edit().putString(owner,JSONArray(mutable.value.map{it.json()}).toString()).apply()}
    private fun adopt(job:TalkDownloadJob,saved:SavedDownload):TalkDownloadJob {
        val stage=when(saved.download.state){Download.STATE_COMPLETED->"Completed";Download.STATE_STOPPED->"Paused";Download.STATE_FAILED->"Failed";Download.STATE_QUEUED->"Waiting for network";else->"Downloading"}
        return job.copy(stage=stage,progress=saved.percent.takeIf{it>=0}?.div(100),nativeSeen=true)
    }
    private fun update(id:String,block:(TalkDownloadJob)->TalkDownloadJob){mutable.update{rows->rows.map{if(it.id==id)block(it)else it}};persist()}
    suspend fun start(card:TalkTitleCard,args:JSONObject):JSONObject {
        val ticket=owner;val revision=ownerRevision
        awaitAccount(ticket,revision)
        val requested=TalkEpisodeArguments.from(args);requested.requireKind(card.type)
        val season=requested.season
        val episode=requested.episode
        if(card.type==MediaType.TV&&(season==null||episode==null))return JSONObject().put("status","needs_episode").put("instruction","Ask for the exact season and episode. Do not silently choose an episode or download the whole series.")
        val tier=when(args.optString("quality").lowercase()){"low","lowest"->DownloadQualityTier.LOW;"high","highest"->DownloadQualityTier.HIGH;"medium"->DownloadQualityTier.MEDIUM;"","auto"->store.preferredTier;else->error("Unknown download quality")}
        val preferences=app.playbackProviderRepository.preferences.value
        val language=talkDownloadSubtitleLanguage(if(args.has("subtitleLanguage"))args.getString("subtitleLanguage")
            else if(preferences.autoDisplaySubtitles)preferences.preferredSubtitleLanguage.code else "")
        val selection=PlaybackSelection(card.media(),seasonNumber=season,episodeNumber=episode)
        val id=playbackProgressKey(selection)
        withTimeout(5000){store.loaded.first{it}}
        requireOwner(ticket,revision)
        val existing=store.entries.value.firstOrNull{it.id==id}
        if(existing!=null)return JSONObject().put("status","already_present").put("state",existing.download.state).put("quality",existing.quality).put("subtitleLanguage",existing.playback.subtitleLanguage)
        val previous=mutable.value.firstOrNull{it.id==id&&it.active}
        if(previous!=null)return JSONObject().put("status","already_preparing").put("work",previous.json())
        check(mutable.value.count{it.active}<8){"Eight downloads are already being prepared"}
        val job=TalkDownloadJob(id,card,season,episode,tier.name,language)
        val retained=mutable.value.filterNot{it.id==id}+job
        // A history bound must never hide a still-running transfer or resolver.
        mutable.value=retained.filter{it.active}+retained.filterNot{it.active}.takeLast(24);persist();pump()
        return JSONObject().put("status","queued").put("work",job.json()).put("instruction","Preparation is queued inside Talk. Confirm only that it is being prepared; subtitles and source are not yet verified, and the file is not downloaded. Progress and cancellation are available in Background work.")
    }
    fun cancel(id:String):String {
        val job=mutable.value.firstOrNull{it.id==id}?:return "not_found"
        if(job.stage=="Completed")return "already_completed"
        running.remove(id)?.cancel()
        preparationOwners.remove(id)
        if(job.stage in setOf("Starting","Downloading","Paused","Waiting for network"))store.remove(id)
        update(id){it.copy(stage="Cancelled",progress=null)}
        return "cancelled"
    }
    fun pause(id:String){store.pause(id)}
    fun resume(id:String){store.entries.value.firstOrNull{it.id==id}?.let{store.resume(it)}}
    fun verify(id:String){
        val current=activity.get()?:return
        mutable.value.firstOrNull{it.id==id}?:return
        val ticket=owner;val revision=ownerRevision
        scope.launch{
            val session=DownloadSession.get(current)
            val verification=session.verifications.values.firstOrNull{(selection,_)->playbackProgressKey(selection)==id}?.second
            val approved=verification!=null&&com.aliflix.app.player.SerienstreamBrowserSession.approve(current,session.host,verification)
            requireOwner(ticket,revision)
            if(approved)retry(id)else update(id){it.copy(stage="Queued",detail="")}.also{pump()}
        }
    }
    private fun requireOwner(ticket:String,revision:Long){if(owner!=ticket||ownerRevision!=revision)throw CancellationException("Account changed")}
    private suspend fun awaitAccount(ticket:String,revision:Long){
        val snapshots=com.aliflix.app.account.AccountLocalSnapshotStore(app)
        withTimeout(5000){while(true){requireOwner(ticket,revision);val expected=if(ticket=="guest")"guest" else "user:$ticket";if(snapshots.activeScope==expected)break;delay(20)}}
    }
    fun retry(id:String){val job=mutable.value.firstOrNull{it.id==id}?:return;if(!job.active){update(id){it.copy(stage="Queued",detail="",progress=null)};pump()}}
    private fun pump(){
        if(!store.loaded.value)return
        val hostActivity=activity.get()?.takeUnless{it.isDestroyed||it.isFinishing}?:return
        for(job in mutable.value.filter{it.stage=="Queued"&&it.id !in running}.take((2-running.size).coerceAtLeast(0))){
            // A process/activity can disappear just after Media3 accepted a job.
            // Adopt that transfer before resolving or enqueueing it again.
            store.entries.value.firstOrNull{it.id==job.id}?.let{saved->update(job.id){adopt(it,saved)}}
            if(mutable.value.firstOrNull{it.id==job.id}?.stage!="Queued")continue
            val ticket=owner;val revision=ownerRevision
            val work=scope.launch(start=CoroutineStart.LAZY){
                try {
                    awaitAccount(ticket,revision)
                    update(job.id){it.copy(stage="Resolving")}
                    if(job.card.type==MediaType.TV){
                        val episodes=MobileEpisodeRepository(app,RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL)).episodes(job.card.id,requireNotNull(job.season))
                        require(episodes.any{it.number==job.episode}){"Episode unavailable"}
                    }
                    val media=job.card.media()
                    val provider=if(media.isJapaneseAnime&&media.type==MediaType.TV)MobilePlaybackProvider.ANIWORLD else if(media.type==MediaType.TV)MobilePlaybackProvider.SERIENSTREAM else MobilePlaybackProvider.FILMO
                    val selection=PlaybackSelection(media,source=app.playbackProviderRepository.preferences.value.sourceFor(media,provider),seasonNumber=job.season,episodeNumber=job.episode)
                    val prepared=prepareDownload(hostActivity,DownloadSession.get(hostActivity).host,selection,job.subtitles,allowVerification=false)
                    ensureActive();requireOwner(ticket,revision)
                    val quality=requireNotNull(preferredDownloadQuality(prepared,DownloadQualityTier.valueOf(job.quality))){"Quality unavailable"}
                    require(quality.height>0){"Source quality could not be verified"}
                    update(job.id){it.copy(stage="Captions",detail=quality.label)}
                    val request=prepared.downloadRequest(quality,job.subtitles,job.subtitles.isNotBlank())
                    ensureActive();requireOwner(ticket,revision)
                    update(job.id){it.copy(stage="Starting")}
                    store.enqueue(listOf(request))
                    ensureActive();update(job.id){it.copy(stage="Downloading")}
                }catch(cancelled:CancellationException){throw cancelled}
                catch(_:com.aliflix.app.player.SerienstreamVerificationRequired){if(owner==ticket)update(job.id){it.copy(stage="Verification required",detail="Streaming source")}}
                catch(error:Exception){if(owner==ticket)update(job.id){it.copy(stage="Failed",detail=error.message.orEmpty().replace(Regex("https?://\\S+"),"").take(110))}}
                finally{if(owner==ticket&&running[job.id]===coroutineContext[Job]){running.remove(job.id);preparationOwners.remove(job.id);pump()}}
            }
            preparationOwners[job.id]=WeakReference(hostActivity);running[job.id]=work;work.start()
        }
    }
    override fun onActivityResumed(value:Activity){if(value is ComponentActivity){activity=WeakReference(value);pump()}}
    override fun onActivityDestroyed(value:Activity){
        if(activity.get()===value)activity.clear()
        preparationOwners.filterValues{it.get()===value}.keys.toList().forEach{id->running.remove(id)?.cancel();preparationOwners.remove(id);update(id){if(it.stage in setOf("Queued","Resolving","Captions","Starting"))it.copy(stage="Queued")else it}}
        pump()
    }
    override fun onActivityCreated(value:Activity,state:Bundle?)=Unit
    override fun onActivityStarted(value:Activity)=Unit
    override fun onActivityPaused(value:Activity)=Unit
    override fun onActivityStopped(value:Activity)=Unit
    override fun onActivitySaveInstanceState(value:Activity,state:Bundle)=Unit
    companion object {
        @Volatile private var instance:TalkDownloadWork?=null
        fun get(context:android.content.Context)=instance?:synchronized(this){instance?:TalkDownloadWork(context.applicationContext as AliflixApplication).also{instance=it}}
    }
}
