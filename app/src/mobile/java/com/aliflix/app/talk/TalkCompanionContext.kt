package com.aliflix.app.talk

import android.content.Context
import com.aliflix.app.AliflixApplication
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.Media
import org.json.JSONArray
import org.json.JSONObject

/** User state and identifiers; catalogue descriptions are never model context. */
internal fun talkLibraryIdentity(item:Media,kind:String)=JSONObject().put("id",item.id)
    .put("type",item.type.routeName).put("kind",kind).apply {
        item.imdbId?.takeIf{it.matches(Regex("tt\\d{1,12}"))}?.let{put("imdbId",it)}
    }

/** Read-only, current app context. No playback URLs, credentials or destructive actions. */
internal fun talkAppContext(context:Context):JSONObject {
    val app=context.applicationContext as AliflixApplication
    val settings=app.playerSettingsStore.settings.value
    return JSONObject().put("version",BuildConfig.VERSION_NAME).put("platform","Android mobile").put("currentDate",java.time.LocalDate.now().toString())
        .put("technicalLimitations",JSONArray(listOf(
            "Live voice and fresh research require an internet connection; service capacity, provider outages and quota can delay or prevent a lookup.",
            "Microphone input is captured only in an active permitted Talk session. Leaving/backgrounding releases capture; muted and text mode do not stream microphone audio. No camera, arbitrary screen, other apps or OS control is exposed to Talk.",
            "Conversation context is bounded and compressed. Archived history is retained only when enabled; recall is not unlimited and may lose details. Read actual app state rather than assuming memory.",
            "Knowledge and catalogue metadata can be incomplete or stale. Tavily supplies current web evidence, TMDB supplies identity/facts; missing or conflicting information needs an honest qualification. Model reasoning can be mistaken.",
            "Actions are restricted to the actual supported native tools. Playback/source availability, audio tracks and subtitles vary. Download preparation and transfer have separate observed states. Requested subtitles and quality must be verified. Native source/account verification and guarded confirmations still need the viewer.",
            "Undo restores the last library change or validated batch made through this conversation, and checks for intervening edits. It does not undo navigation, playback or arbitrary manual changes."
        )))
        .put("library",JSONObject().put("favorites",app.libraryStore.likes.value.size)
            .put("myList",app.libraryStore.myList.value.size).put("recent",app.libraryStore.recent.value.size))
        .put("settings",JSONObject().put("preferredQuality",settings.preferredVideoQuality.label)
            .put("playNextEpisode",settings.playNextEpisode).put("playbackSpeed",settings.playbackSpeed)
            .put("subtitleDelaySeconds",settings.subtitleDelaySeconds).put("subtitleSizeSp",settings.subtitleFontSizeSp)
            .put("subtitleBackgroundOpacity",settings.subtitleBackgroundOpacity).put("audioBoost",settings.boostAudio)
            .put("zoom",settings.resizeModeZoom).put("subtitleVerticalOffset",settings.subtitleVerticalOffsetDp)
            .put("autoSubtitles",app.playbackProviderRepository.preferences.value.autoDisplaySubtitles)
            .put("subtitleLanguage",app.playbackProviderRepository.preferences.value.preferredSubtitleLanguage.code)
            .put("provider",app.playbackProviderRepository.preferences.value.effectiveGeneralProvider.name)
            .put("recommendationsEnabled",app.recommendationStore.enabled.value))
        .put("capabilities",JSONObject()
            .put("discovery",JSONObject().put("tool","recommendations").put("descriptionsAndSimilarity","inside_chat").put("voiceChoices",8).put("chatDefaultChoices",12).put("chatMaximumChoices",24).put("mediaType","inferred_from_request").put("seriesStatus",JSONArray(listOf("returning","ended"))).put("filtersLocation","Discover beside All, Movies and Series").put("results","each_reply_owns_its_collection"))
            .put("titleCards",JSONObject().put("tool","show_title_cards").put("identity","movie:id or tv:id").put("automaticForDiscussedTitles",true))
            .put("downloads",JSONObject().put("tool","app_control").put("action","download").put("execution","background_preparation_then_media3_service")
                .put("requiredForSeries",JSONArray(listOf("season","episode"))).put("quality",JSONArray(listOf("low","medium","high"))).put("subtitleLanguage","supported BCP-47 code or off")
                .put("progressAction","download_work").put("cancelAction","cancel_download_work").put("verification","native source verification when required").put("queuedIsCompleted",false))
            .put("voice",JSONObject().put("action","set_voice").put("choices",JSONArray(VOICES)).put("takesEffect","new_session_before_acknowledgement"))
            .put("library",JSONArray(listOf("title_state","library_state","save","unsave","favorite","unfavorite","dislike","undo_dislike","batch_library","undo")))
            .put("player",JSONArray((talkPlayerCommands+setOf("play","execute_task","task_state","cancel_task","scene_context")).toList()))
            .put("scene",JSONObject().put("tool","scene_context").put("evidence","Recent player video frames, selected timed captions, played dialogue and exact viewing boundary; ephemeral and bounded")
                .put("uses",JSONArray(listOf("explain_current_scene","explain_dialogue","translate_visible_line","recap_recent_events","replay_scene"))).put("futureSpoilers",false))
            .put("navigation",JSONArray(listOf("home","discover","ask","my_list","favorites","history","downloads","settings","account","talk_voices","talk_privacy","recent_chats","agent_tasks")))
            .put("settings",JSONArray(listOf("play_next_episode","audio_boost","zoom","auto_subtitles","playback_speed","subtitle_size","subtitle_opacity","subtitle_delay_seconds","subtitle_vertical_offset","preferred_quality","subtitle_language","provider","recommendations_enabled")))
            .put("agent",JSONObject().put("tool","agent_workspace").put("background",true).put("explicitMonitoringConsent",true).put("scope","Aliflix only")))
        .put("activePlayer",TalkPlayerConnection.current()?.talkSnapshot())
        .put("tasks",TalkTaskWork.get(context).state())
        .put("orchestration",JSONObject().put("action","execute_task").put("maximumSteps",16).put("ordered",true)
            .put("instruction","Submit every requested operation in commands in dependency order. Playback first, source/audio before subtitles, sync after subtitles, final seek after sync if requested. Every step gets an observed result. Independent later steps still run after an unavailable feature. Tasks survive navigation and speech interruption; cancel_task explicitly cancels remaining work. A new target never receives an old task's remaining actions. Use task_state for queued work. Never claim queued playback is playing."))
        .put("navigation",JSONObject().put("ask","Discover > Ask > text conversation").put("library","My Space")
            .put("provider","Player > More > Streaming source").put("audioSubtitles","Player > More > Audio & subtitles")
            .put("watchTogether","Player > More > Watch Together").put("backgroundWork","Talk > Background work"))
        .put("boundaries",JSONObject().put("syncWithAudio","Local subtitle timing; no translation or subtitle generation")
            .put("myList","Saved intent, not evidence of watching").put("completedSeries","Read actual episode progress")
            .put("providerAvailability","Verify the requested source and tracks; website availability alone is insufficient")
            .put("undo","Last Talk library edit or validated batch, checked for intervening edits"))
        .put("settingValues",JSONObject().put("playback_speed",JSONArray(listOf(.5,.75,1.0,1.25,1.5,2.0))).put("preferred_quality",JSONArray(listOf("AUTO","LOW")))
            .put("providers",JSONArray(com.aliflix.app.model.MobilePlaybackProvider.entries.map{it.name})).put("subtitle_languages",JSONArray(com.aliflix.app.model.SubtitleLanguage.entries.map{it.code})))
        .put("instruction","These are real native capabilities and current state. For a user action request, USE app_control or show_title_cards. Use canonical id/type/year from conversation for the exact established edition. Read title_state instead of guessing library membership. undo restores only the last Talk library change. Only confirm completion from the action result. Open real native options or account verification when required. Questions about features do not authorize unrelated changes.")
}

/** A turn-level safety net when Gemini omits the optional visual tool. Never use raw first words. */
internal fun talkUtteranceTopic(value:String):String? {
    val text=value.trim().replace(Regex("\\s+")," ")
    if(text.isBlank())return null
    // Explicitly named subjects beat a previous topic; keep follow-ups and filler stable.
    val titlePatterns=listOf(
        "(?i)(?:ending|end|atmosphere|plot|story|themes?|characters?) (?:of|in|to) (.+)",
        "(?i)(?:discuss|talk about|tell me about|explain|analyse|analyze|what about|how about) (.+)",
        "(?i)(?:why is|is|have you seen|do you know) (.+?)(?: a | an | worth | good| scary| available|\\?|$)",
        "(?i)(?:ende|handlung|atmosph[aä]re) (?:von|in) (.+)",
        "(?i)(?:erkl[aä]re|erz[aä]hl mir von|sprechen wir [uü]ber) (.+)",
        "(?:نهاية|قصة|شخصيات|أحداث) (?:فيلم |مسلسل )?(.+)",
        "(?:حدثني عن|نتحدث عن|اشرح فيلم|اشرح مسلسل) (.+)"
    )
    val quoted=Regex("[\"“«]([^\"”»]{2,60})[\"”»]").find(text)?.groupValues?.get(1)
    val candidate=quoted?:titlePatterns.firstNotNullOfOrNull{Regex(it).find(text)?.groupValues?.get(1)}
    if(candidate!=null) {
        val title=candidate.replace(Regex("(?i)^(?:the (?:movie|film|series)|(?:movie|film|series))\\s+"),"")
            .split(Regex("(?i)[.!?;,]|\\s+(?:season|episode|staffel|folge|in one|without|with no|answer|please|auf deutsch|بالعربي|الموسم|الحلقة)\\b"))[0]
            .replace(Regex("\\s*\\(?(?:18|19|20)\\d{2}\\)?$"),"").trim(' ','.','?','!')
        val generic=Regex("(?i)\\b(that|this|it|same|more|something|anything|a movie|a film|movies|films|series|ending|final photograph|earlier|previously|later|again|time loop|sci.?fi)\\b|هذا|ذلك|نفس|المزيد")
        if(title.length in 2..48 && !generic.containsMatchIn(title))validatedTalkTopic(title)?.let{return it}
    }
    val themes=listOf(
        "time loop" to "TIME LOOP","time travel" to "TIME TRAVEL","science fiction" to "SCI-FI","sci-fi" to "SCI-FI","scifi" to "SCI-FI",
        "zeitreise" to "ZEITREISE","السفر عبر الزمن" to "السفر عبر الزمن","خيال علمي" to "خيال علمي",
        "recommend" to "DISCOVERY","empfehl" to "ENTDECKEN","اقترح" to "اكتشاف",
        "favorite" to "FAVORITES","favourite" to "FAVORITES","my list" to "MY LIST","مفضل" to "المفضلة",
        "subtitle" to "SUBTITLES","untertitel" to "UNTERTITEL","ترجمة" to "الترجمة",
        "download" to "DOWNLOADS","watch together" to "WATCH TOGETHER","streaming source" to "STREAMING",
        "horror" to "HORROR","thriller" to "THRILLER","comedy" to "COMEDY","romance" to "ROMANCE"
    )
    val lower=text.lowercase(java.util.Locale.ROOT)
    return themes.firstOrNull{it.first in lower}?.second
}
