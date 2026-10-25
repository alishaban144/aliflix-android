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

internal data class TalkNativeResult(val value:JSONObject,val afterReply:(()->Unit)?=null)

/** One native action boundary. Mutations are idempotent and report the actual
 * store state. Navigation uses the app's own screens and existing confirmation
 * flows, after a short spoken reply; it never manufactures playback/downloads. */
internal class TalkNativeActions(private val context:Context,private val catalogue:TalkCatalogue,
    private val show:(List<TalkTitleCard>)->Unit) {
    private val app get()=context.applicationContext as AliflixApplication
    private data class Change(val card:TalkTitleCard,val action:String,val before:Boolean,val after:Boolean)
    private var lastChange:Change?=null
    fun clearUndo(){lastChange=null}
    suspend fun execute(args:JSONObject):TalkNativeResult {
        val action=args.optString("action")
        if(action=="undo")return undo()
        if(action=="set_setting"){lastChange=null;return setting(args)}
        if(action=="open_screen"||action=="search")return navigation(args,null)
        if(action in setOf("downloads_state","pause_download","resume_download","remove_download"))return downloads(args)
        if(action in setOf("player_state","pause","resume","seek","audio_language","subtitle_language"))
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
        when(action){
            "save","unsave"->if(library.isInMyList(media)!=(action=="save"))library.toggleMyList(media)
            "favorite","unfavorite"->if(library.isLiked(media)!=(action=="favorite"))library.toggleLike(media)
            "dislike"->AskTaste(context).reject(media)
            "undo_dislike"->AskTaste(context).unreject(media)
            else->return navigation(args,card)
        }
        val after=current();lastChange=if(before!=after)Change(card,action,before,after)else null
        return TalkNativeResult(JSONObject().put("status","completed").put("title",card.title).put("action",action)
            .put("changed",before!=after).put("previousValue",before)
            .put("saved",library.isInMyList(media)).put("favorite",library.isLiked(media)).apply{
                if(action in setOf("dislike","undo_dislike"))put("disliked",media.key in AskTaste(context).rejected())
            })
    }
    private fun undo():TalkNativeResult {
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
        fun number():Float{val n=args.getDouble("numberValue");require(n.isFinite()){ "A finite number is required" };return n.toFloat()}
        val setting=args.getString("setting")
        when(setting){
            "play_next_episode"->store.updatePlayNextEpisode(enabled())
            "audio_boost"->store.updateBoostAudio(enabled())
            "zoom"->store.updateResizeModeZoom(enabled())
            "auto_subtitles"->preferences.setAutoDisplaySubtitles(enabled())
            "playback_speed"->{val speed=number();require(speed in listOf(.5f,.75f,1f,1.25f,1.5f,2f)){"Supported speeds: 0.5, 0.75, 1, 1.25, 1.5, 2"};store.updatePlaybackSpeed(speed)}
            "subtitle_size"->store.updateSubtitleFontSize(number())
            "subtitle_opacity"->store.updateSubtitleBackgroundOpacity(number())
            "subtitle_delay_seconds"->store.updateSubtitleDelayTenths((number()*10).toInt())
            "subtitle_vertical_offset"->store.updateSubtitleVerticalOffsetDp(number().toInt())
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
            val cards=catalogue.find(args.optString("title"),args.optString("kind").takeIf{it in setOf("movie","tv")},args.optInt("year"))
            if(cards.size!=1)return TalkNativeResult(JSONObject().put("status","choose_title").put("instruction","Ask which downloaded title or exact episode to change."))
            val card=cards.single()
            entries=entries.filter{it.selection.media.key==card.key&&(args.optInt("season")==0||it.selection.seasonNumber==args.optInt("season"))&&(args.optInt("episode")==0||it.selection.episodeNumber==args.optInt("episode"))}
            if(entries.size!=1)return TalkNativeResult(JSONObject().put("status",if(entries.isEmpty())"not_downloaded" else "choose_episode").put("instruction","This title has no matching download, or more than one episode matches. Ask for the exact episode or open Downloads; do not change unrelated files."))
            when(action){"pause_download"->store.pause(entries.single().id);"resume_download"->store.resume(entries.single());"remove_download"->store.remove(entries.single().id)}
        }
        return TalkNativeResult(JSONObject().put("status",if(action=="downloads_state")"current" else "command_sent").put("action",action)
            .put("downloads",JSONArray(entries.take(24).map{entry->JSONObject().put("title",entry.selection.media.title).put("season",entry.selection.seasonNumber).put("episode",entry.selection.episodeNumber).put("state",entry.download.state).put("percent",entry.percent).put("quality",entry.quality)})))
    }
}
