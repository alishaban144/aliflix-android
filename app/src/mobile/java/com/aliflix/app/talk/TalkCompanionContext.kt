package com.aliflix.app.talk

import android.content.Context
import com.aliflix.app.AliflixApplication
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.Media
import org.json.JSONArray
import org.json.JSONObject

/** Only user-owned state and opaque identifiers leave the device; no catalogue prose. */
internal fun talkLibraryIdentity(item:Media,kind:String)=JSONObject().put("id",item.id)
    .put("type",item.type.routeName).put("kind",kind).apply {
        item.imdbId?.takeIf{it.matches(Regex("tt\\d{1,12}"))}?.let{put("imdbId",it)}
    }

/** Read-only, current app context. No playback URLs, credentials or destructive actions. */
internal fun talkAppContext(context:Context):JSONObject {
    val app=context.applicationContext as AliflixApplication
    val settings=app.playerSettingsStore.settings.value
    return JSONObject().put("version",BuildConfig.VERSION_NAME).put("platform","Android mobile")
        .put("library",JSONObject().put("favorites",app.libraryStore.likes.value.size)
            .put("myList",app.libraryStore.myList.value.size).put("recent",app.libraryStore.recent.value.size))
        .put("settings",JSONObject().put("preferredQuality",settings.preferredVideoQuality.label)
            .put("playNextEpisode",settings.playNextEpisode).put("playbackSpeed",settings.playbackSpeed)
            .put("subtitleDelaySeconds",settings.subtitleDelaySeconds).put("subtitleSizeSp",settings.subtitleFontSizeSp)
            .put("subtitleBackgroundOpacity",settings.subtitleBackgroundOpacity).put("audioBoost",settings.boostAudio))
        .put("features",JSONArray(listOf(
            "Home: For You, Movies, TV Shows, Categories; title details, search, seasons and episode ratings.",
            "Discover > Ask Aliflix: Describe and Find similar remain separate search modes. Microphone opens Talk; Talk starts listening after microphone permission. Transcript and Privacy controls are inside Talk. End or Back returns to the app.",
            "My Space: Favorites are explicit likes; My List is saved intent, not proof of watching. Recent history and playback progress are separate. Settings and app updates are accessed here.",
            "Player > More > Streaming source chooses a provider; individual hosts are tried automatically. Filmo is preferred for movies, Serienstream for TV, AniWorld for Japanese anime; unavailable sources can fall back. Website availability does not guarantee every host works.",
            "Player > More > Audio & subtitles: available audio languages depend on the current source; subtitles, manual subtitle delay and Sync with audio are separate controls. Sync with audio analyses local dialogue timing; it does not translate or generate subtitles.",
            "Player episodes supports season selection. Next episode depends on the Play next episode setting. Quality, audio, subtitles, volume and brightness remain individual playback preferences.",
            "Watch Together is inside Player > More. Private invitations, independent provider streams, host/everyone permissions and room leave/end controls; no video URLs or credentials are shared.",
            "Downloads have their own quality choice, audio/subtitle selection when available, and episode selection; saved downloads are separate from My List. Source tracks can vary by title.",
            "Settings has inline Auto/Low preferred quality and a read-only Streaming sources list at the end; choosing a source is in the player.",
            "Talk uses microphone audio with Gemini Live, pinned voice and same-model reconnect. Keep history is opt-in; Delete history clears saved Talk history. Stopping or leaving releases the microphone. Talk cannot change settings, launch playback, delete data or verify live provider status on your behalf."
        )))
        .put("instruction","Explain how these real features connect. Use the returned current settings for personal questions. Do not invent hidden controls, device observations or actions. If a capability is absent here, acknowledge that specific limitation briefly.")
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
        val generic=Regex("(?i)\\b(that|this|it|same|more|something|anything|a movie|a film|movies|films|series|ending|final photograph|time loop|sci.?fi)\\b|هذا|ذلك|نفس|المزيد")
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
