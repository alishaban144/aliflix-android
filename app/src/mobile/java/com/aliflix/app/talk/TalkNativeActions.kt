package com.aliflix.app.talk

import android.content.Context
import android.content.Intent
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.SubtitleLanguage
import com.aliflix.app.player.PreferredVideoQuality
import com.aliflix.app.recommendation.AskTaste
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

internal data class TalkNativeResult(val value:JSONObject,val afterReply:(()->Unit)?=null)

/** One native action boundary. Mutations are idempotent and report the actual
 * store state. Navigation uses the app's own screens and existing confirmation
 * flows, after a short spoken reply; it never manufactures playback/downloads. */
internal class TalkNativeActions(private val context:Context,private val catalogue:TalkCatalogue,
    private val show:(List<TalkTitleCard>)->Unit) {
    private val app get()=context.applicationContext as AliflixApplication
    private data class Change(val card:TalkTitleCard,val action:String,val before:Boolean,val after:Boolean)
    private var lastChange:Change?=null
    private var lastBatch:List<Change> = emptyList()
    fun clearUndo(){lastChange=null;lastBatch=emptyList()}
    suspend fun execute(args:JSONObject):TalkNativeResult {
        val action=args.optString("action")
        if(action=="scene_context")return TalkNativeResult(TalkPlayerConnection.current()?.prepareTalkScene()?.metadata
            ?: JSONObject().put("status","no_active_scene"))
        if(action=="task_state")return TalkNativeResult(TalkTaskWork.get(context).state(args.optString("taskId")))
        if(action=="cancel_task")return TalkNativeResult(TalkTaskWork.get(context).cancel(args.getString("taskId")))
        if(action=="execute_task"||action=="play")return task(args)
        if(action=="player_state" && TalkPlayerConnection.current()!=null)
            return TalkNativeResult(TalkPlayerConnection.current()!!.talkCommand(args))
        if(action in talkPlayerCommands && action!="player_state")
            return task(JSONObject().put("commands",JSONArray().put(args)).put("_talkCallId",args.optString("_talkCallId")))
        if(action=="download_work")return TalkNativeResult(JSONObject().put("status","current").put("jobs",JSONArray(TalkDownloadWork.get(context).jobs.value.map{it.json()})))
        if(action=="cancel_download_work"){
            val id=args.getString("workId");val work=TalkDownloadWork.get(context)
            require(work.jobs.value.any{it.id==id}){"Unknown download work"}
            return TalkNativeResult(JSONObject().put("status",work.cancel(id)).put("workId",id))
        }
        if(action=="library_state")return libraryState(args)
        if(action=="batch_library")return batch(args)
        if(action=="undo")return undo()
        if(action=="set_setting"){val result=setting(args);clearUndo();return result}
        if(action=="open_screen"||action=="search")return navigation(args,null)
        if(action in setOf("downloads_state","pause_download","resume_download","remove_download"))return downloads(args)
        if(action=="player_state")
            return TalkNativeResult(talkPlayerAction(context,args))
        require(action in setOf("show_card","title_state","open_title","play","download","similar","save","unsave","favorite","unfavorite","dislike","undo_dislike")){"Unknown app action"}
        val cards=catalogue.find(args.optString("title"),args.optString("kind").takeIf{it in setOf("movie","tv")},args.optInt("year"),args.optInt("tmdbId"))
        if(cards.isEmpty())return TalkNativeResult(JSONObject().put("status","not_found").put("instruction","Catalogue search found no exact title. Ask briefly for the title or edition; do not claim the app cannot show cards."))
        show(cards)
        if(action=="show_card")return TalkNativeResult(JSONObject().put("status","displayed").put("cards",JSONArray(cards.map{it.json()})).put("instruction","Continue the substance of the discussion. Do not announce that cards are shown, displayed, visible or on screen. For a card-only request, a brief relevant title remark is enough."))
        if(cards.size!=1)return TalkNativeResult(JSONObject().put("status","choose_edition").put("cards",JSONArray(cards.map{it.json()})).put("instruction","The matching editions are displayed. Ask which one before changing the library or starting playback."))
        val card=cards.single();val media=card.media();val library=app.libraryStore
        fun current()=when(action){"save","unsave"->library.isInMyList(media);"favorite","unfavorite"->library.isLiked(media);"dislike","undo_dislike"->media.key in AskTaste(context).rejected();else->false}
        val before=current()
        if(action=="title_state")return TalkNativeResult(JSONObject().put("status","observed").put("card",card.json())
            .put("saved",library.isInMyList(media)).put("favorite",library.isLiked(media)).put("disliked",media.key in AskTaste(context).rejected()))
        if(action=="download")return TalkNativeResult(TalkDownloadWork.get(context).start(card,args))
        when(action){
            "save","unsave"->if(library.isInMyList(media)!=(action=="save"))library.toggleMyList(media)
            "favorite","unfavorite"->if(library.isLiked(media)!=(action=="favorite"))library.toggleLike(media)
            "dislike"->AskTaste(context).reject(media)
            "undo_dislike"->AskTaste(context).unreject(media)
            else->return navigation(args,card)
        }
        val after=current();lastBatch=emptyList();lastChange=if(before!=after)Change(card,action,before,after)else null
        return TalkNativeResult(JSONObject().put("status","completed").put("title",card.title).put("action",action)
            .put("changed",before!=after).put("previousValue",before)
            .put("saved",library.isInMyList(media)).put("favorite",library.isLiked(media)).apply{
                if(action in setOf("dislike","undo_dislike"))put("disliked",media.key in AskTaste(context).rejected())
            })
    }
    private suspend fun task(args:JSONObject):TalkNativeResult {
        val work=TalkTaskWork.get(context)
        val requestKey=args.optString("_talkCallId")
        work.existingRequest(requestKey)?.let{return TalkNativeResult(work.state(it))}
        val plan=if(args.optString("action")=="play")JSONObject().put("commands",JSONArray().put(args))else args
        val steps=talkTaskPlan(plan).toMutableList()
        if(steps.size>1&&steps.first().optString("action")=="play"&&steps.last().optString("action") !in setOf("stop_playback","open_cast","watch_together","verify_provider")&&
            steps.lastOrNull{it.optString("action") in setOf("play","pause","resume") }?.optString("action")=="play")
            steps.add(JSONObject().put("action","resume"))
        if(steps.none{it.optString("action")=="play"}&&steps.any{it.optString("action") in talkPlayerCommands}&&
            com.aliflix.app.player.NativePlaybackService.activeRequestId==null)
            return TalkNativeResult(JSONObject().put("status","no_active_playback").put("instruction","No active playback exists. Ask which title to play; do not invent a player or report task completion."))
        val identities=mutableListOf<TalkTitleCard>()
        val playbackMedia=mutableMapOf<String,com.aliflix.app.model.Media>()
        // Resolve all editions before the first mutation, including library steps.
        for(step in steps.filter{it.optString("action") in setOf("play","save","unsave","favorite","unfavorite")}) {
            val cards=catalogue.find(step.optString("title"),step.optString("kind").takeIf{it in setOf("movie","tv")},step.optInt("year"),step.optInt("tmdbId"))
            if(cards.size!=1)return TalkNativeResult(JSONObject().put("status",if(cards.isEmpty())"not_found" else "choose_edition").put("cards",JSONArray(cards.map{it.json()})))
            val card=cards.single();identities.add(card)
            step.put("tmdbId",card.id).put("kind",card.type.routeName).put("title",card.title)
            if(step.optString("action")=="play") {
                playbackMedia[card.key]=catalogue.playbackMedia(card)
                var episode=TalkEpisodeArguments.from(step);episode.requireKind(card.type)
                if(card.type==com.aliflix.app.model.MediaType.TV&&episode.episode!=null&&episode.season==null) {
                    val current=TalkPlayerConnection.current()?.talkSnapshot()
                    val season=current?.takeIf{it.optInt("id")==card.id&&it.optString("type")=="tv"}?.talkOptionalInteger("season")
                    if(season==null)return TalkNativeResult(JSONObject().put("status","choose_season").put("instruction","The episode number is known, but its season is not established. Ask only for the season; no playback or other plan steps have started."))
                    step.put("season",season);episode=TalkEpisodeArguments.from(step)
                }
                if(card.type==com.aliflix.app.model.MediaType.TV&&episode.season!=null&&episode.episode!=null) {
                    val repository=com.aliflix.app.data.MobileEpisodeRepository(context,com.aliflix.app.recommendation.RecommendationAiClient(com.aliflix.app.BuildConfig.RECOMMENDATION_AI_BASE_URL))
                    val available=repository.episodes(card.id,episode.season)
                    if(available.none{it.number==episode.episode})return TalkNativeResult(JSONObject().put("status","episode_unavailable").put("season",episode.season).put("episode",episode.episode))
                }
            }
        }
        val id=work.start(context,steps,identities.distinctBy{it.key},requestKey,playbackMedia)
        // A launch may stop this Activity. Its task continues independently and
        // the player bubble reads the same task id and actual completion state.
        val result=if(steps.first().optString("action")=="play")work.state(id).put("instruction","Playback is preparing in the native player. Do not claim it is playing yet; the task tracks actual playback and every requested step.")else work.await(id)
        return TalkNativeResult(result)
    }
    private fun undo():TalkNativeResult {
        if(lastBatch.isNotEmpty()){
            val changes=lastBatch;lastBatch=emptyList()
            if(changes.any{readFlag(it.card,it.action)!=it.after})return TalkNativeResult(JSONObject().put("status","changed_since_action"))
            changes.asReversed().forEach{writeFlag(it.card,it.action,it.before)}
            return TalkNativeResult(JSONObject().put("status","completed").put("undoneCount",changes.size))
        }
        val change=lastChange?:return TalkNativeResult(JSONObject().put("status","nothing_to_undo"))
        lastChange=null
        val media=change.card.media();val library=app.libraryStore;val taste=AskTaste(context)
        val current=when(change.action){"save","unsave"->library.isInMyList(media);"favorite","unfavorite"->library.isLiked(media);else->media.key in taste.rejected()}
        if(current!=change.after)return TalkNativeResult(JSONObject().put("status","changed_since_action").put("instruction","This item changed after your last command. Keep its newer state; explain briefly."))
        when(change.action){"save","unsave"->library.toggleMyList(media);"favorite","unfavorite"->library.toggleLike(media);else->if(change.before)taste.reject(media)else taste.unreject(media)}
        show(listOf(change.card))
        return TalkNativeResult(JSONObject().put("status","completed").put("undone",change.action).put("card",change.card.json()).put("saved",library.isInMyList(media)).put("favorite",library.isLiked(media))
            .apply{if(change.action in setOf("dislike","undo_dislike"))put("disliked",media.key in taste.rejected())})
    }
    private fun readFlag(card:TalkTitleCard,action:String):Boolean=when(action){
        "save","unsave"->app.libraryStore.isInMyList(card.media())
        "favorite","unfavorite"->app.libraryStore.isLiked(card.media())
        else->card.key in AskTaste(context).rejected()
    }
    private fun writeFlag(card:TalkTitleCard,action:String,value:Boolean){
        when(action){
            "save","unsave"->if(app.libraryStore.isInMyList(card.media())!=value)app.libraryStore.toggleMyList(card.media())
            "favorite","unfavorite"->if(app.libraryStore.isLiked(card.media())!=value)app.libraryStore.toggleLike(card.media())
            else->if(value)AskTaste(context).reject(card.media())else AskTaste(context).unreject(card.media())
        }
    }
    private suspend fun batch(args:JSONObject):TalkNativeResult {
        val commands=args.getJSONArray("commands");require(commands.length() in 1..24)
        // Resolve and validate the WHOLE plan before making its first change.
        val resolved=(0 until commands.length()).map{index->
            val command=commands.getJSONObject(index);val action=command.getString("action")
            require(action in setOf("save","unsave","favorite","unfavorite","dislike","undo_dislike"))
            val cards=catalogue.find(command.optString("title"),command.optString("kind").takeIf{it in setOf("movie","tv")},command.optInt("year"),command.optInt("tmdbId"))
            require(cards.size==1){"Resolve each exact edition before a batch"}
            cards.single() to action
        }
        require(resolved.map{(card,action)->card.key+":"+when(action){"save","unsave"->"saved";"favorite","unfavorite"->"liked";else->"rejected"}}.distinct().size==resolved.size){"Conflicting duplicate commands"}
        val changes=resolved.map{(card,action)->Change(card,action,readFlag(card,action),action in setOf("save","favorite","dislike"))}.filter{it.before!=it.after}
        try{changes.forEach{writeFlag(it.card,it.action,it.after)}}catch(error:Exception){changes.asReversed().filter{readFlag(it.card,it.action)==it.after}.forEach{writeFlag(it.card,it.action,it.before)};throw error}
        lastChange=null;lastBatch=changes
        return TalkNativeResult(JSONObject().put("status","completed").put("changedCount",changes.size)
            .put("results",org.json.JSONArray(resolved.map{(card,action)->card.json().put("action",action).put("value",readFlag(card,action))})))
    }
    private fun libraryState(args:JSONObject):TalkNativeResult {
        val library=app.libraryStore;val progress=app.playbackProgressStore.entries.value.values
        val collection=args.optString("stringValue","all")
        val rows=when(collection){"saved"->library.myList.value;"favorite"->library.likes.value;"watched"->library.recent.value;else->library.myList.value+library.likes.value+library.recent.value}.distinctBy{it.key}
        val offset=args.optInt("offset",0).coerceAtLeast(0)
        return TalkNativeResult(JSONObject().put("status","current").put("total",rows.size).put("nextOffset",(offset+40).takeIf{it<rows.size})
            .put("items",JSONArray(rows.drop(offset).take(40).map{media->JSONObject().put("id",media.id).put("kind",media.type.routeName).put("title",media.title).put("year",media.year)
                .put("saved",library.isInMyList(media)).put("favorite",library.isLiked(media)).put("completed",media.type==com.aliflix.app.model.MediaType.MOVIE&&progress.any{it.media.key==media.key&&it.completed})
                .put("started",progress.any{it.media.key==media.key&&it.positionSeconds>0}).put("episodesCompleted",progress.count{it.media.key==media.key&&it.completed})})))
    }
    private fun navigation(args:JSONObject,card:TalkTitleCard?):TalkNativeResult {
        val action=args.optString("action")
        if(action=="open_screen")require(args.optString("screen") in setOf("home","discover","ask","my_list","favorites","history","downloads","settings","account")){"Unknown screen"}
        if(action=="search")require(args.optString("query").isNotBlank()){ "A search query is required" }
        val command=JSONObject(args.toString())
        val intent=Intent(context,TalkTitleActivity::class.java).putExtra("assistantAction",command.toString())
        if(context !is android.app.Activity)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        card?.let{intent.putExtra("title",it.media().toJson().toString())}
        if(action=="download"&&card!=null){intent.putExtra("assistantDownloadKey",card.key);intent.putExtra("assistantDownloadSeason",args.optInt("season"));intent.putExtra("assistantDownloadEpisode",args.optInt("episode"))}
        return TalkNativeResult(JSONObject().put("status","ready").put("action",action).put("screen",args.optString("screen"))
            .put("instruction","The native app action is ready and will open immediately after this short reply. Say you are opening the requested screen, playback or download options. Do not claim a download finished, playback started or a setting changed before it happens.")){
                context.startActivity(intent)
            }
    }
    private fun setting(args:JSONObject):TalkNativeResult {
        val store=app.playerSettingsStore;val preferences=app.playbackProviderRepository
        fun enabled():Boolean{require(args.opt("enabled") is Boolean){"A boolean enabled value is required"};return args.getBoolean("enabled")}
        fun number(range:ClosedFloatingPointRange<Float> = -Float.MAX_VALUE..Float.MAX_VALUE)=args.talkSettingNumber(range)
        val setting=args.getString("setting")
        when(setting){
            "play_next_episode"->store.updatePlayNextEpisode(enabled())
            "audio_boost"->store.updateBoostAudio(enabled())
            "zoom"->store.updateResizeModeZoom(enabled())
            "auto_subtitles"->preferences.setAutoDisplaySubtitles(enabled())
            "playback_speed"->{val speed=number(.5f..2f);require(speed in listOf(.5f,.75f,1f,1.25f,1.5f,2f)){"Supported speeds: 0.5, 0.75, 1, 1.25, 1.5, 2"};store.updatePlaybackSpeed(speed)}
            "subtitle_size"->store.updateSubtitleFontSize(number())
            "subtitle_opacity"->store.updateSubtitleBackgroundOpacity(number())
            "subtitle_delay_seconds"->store.updateSubtitleDelayTenths(kotlin.math.round(number().coerceIn(-60f,60f)*10).toInt())
            "subtitle_vertical_offset"->store.updateSubtitleVerticalOffsetDp(kotlin.math.round(number().coerceIn(-200f,200f)).toInt())
            "preferred_quality"->store.updatePreferredVideoQuality(PreferredVideoQuality.valueOf(args.getString("stringValue").uppercase(java.util.Locale.ROOT)))
            "subtitle_language"->preferences.selectPreferredSubtitleLanguage(SubtitleLanguage.entries.first{it.code.equals(args.getString("stringValue"),true)})
            "provider"->{val provider=requireNotNull(PlaybackProvider.fromStoredValue(args.getString("stringValue")));require(provider.supportsGeneralPlayback);preferences.selectGeneralProvider(provider)}
            "recommendations_enabled"->app.recommendationStore.setEnabled(enabled())
            else->error("Unknown setting")
        }
        return TalkNativeResult(JSONObject().put("status","completed").put("setting",setting).put("current",talkAppContext(context).getJSONObject("settings")))
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun downloads(args:JSONObject):TalkNativeResult {
        val store=com.aliflix.app.downloads.OfflineDownloads.get(context)
        withTimeout(3500){store.loaded.first{it}}
        val action=args.getString("action")
        var entries=store.entries.value
        if(action!="downloads_state") {
            val episode=TalkEpisodeArguments.from(args)
            val cards=catalogue.find(args.optString("title"),args.optString("kind").takeIf{it in setOf("movie","tv")},args.optInt("year"),args.optInt("tmdbId"))
            if(cards.size!=1)return TalkNativeResult(JSONObject().put("status","choose_title").put("instruction","Ask which downloaded title or exact episode to change."))
            val card=cards.single()
            episode.requireKind(card.type)
            entries=entries.filter{it.selection.media.key==card.key&&episode.matches(it.selection.seasonNumber,it.selection.episodeNumber)}
            if(entries.size!=1)return TalkNativeResult(JSONObject().put("status",if(entries.isEmpty())"not_downloaded" else "choose_episode").put("instruction","This title has no matching download, or more than one episode matches. Ask for the exact episode or open Downloads; do not change unrelated files."))
            when(action){"pause_download"->store.pause(entries.single().id);"resume_download"->store.resume(entries.single());"remove_download"->store.remove(entries.single().id)}
        }
        return TalkNativeResult(JSONObject().put("status",if(action=="downloads_state")"current" else "command_sent").put("action",action)
            .put("downloads",JSONArray(entries.take(24).map{entry->JSONObject().put("title",entry.selection.media.title).put("season",entry.selection.seasonNumber).put("episode",entry.selection.episodeNumber).put("state",entry.download.state).put("percent",entry.percent).put("quality",entry.quality)})))
    }
}
