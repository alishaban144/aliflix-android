package com.aliflix.app.talk

import org.json.JSONArray
import org.json.JSONObject

internal val talkPlayerCommands = setOf("player_state", "pause", "resume", "seek", "seek_relative", "audio_language", "subtitle_language",
    "sync_subtitles", "reset_subtitle_sync", "skip_intro", "next_episode", "select_episode", "playback_speed", "quality", "select_server", "select_provider", "retry_playback",
    "volume", "brightness", "rotate", "stop_playback", "stop_cast", "open_cast", "watch_together", "verify_provider")

/** Validate every step before the first mutation. Order is retained, including seeks after sync. */
internal fun talkTaskPlan(args: JSONObject): List<JSONObject> {
    val rows = args.getJSONArray("commands")
    require(rows.length() in 1..16) { "A task needs one to sixteen commands" }
    val allowed = talkPlayerCommands + setOf("play", "save", "unsave", "favorite", "unfavorite", "set_setting")
    val steps = (0 until rows.length()).map { JSONObject(rows.getJSONObject(it).toString()).also { command -> command.remove("_talkCallId") } }
    require(steps.count { it.optString("action") == "play" } <= 1) { "Use one playback target per task" }
    require(steps.indexOfFirst { it.optString("action") == "play" } <= 0) { "Start the requested title before configuring it" }
    require(steps.dropLast(1).none { it.optString("action") in setOf("stop_playback","open_cast","watch_together","verify_provider") }) { "A native handoff must be the last task step" }
    steps.forEach { command ->
        val action = command.getString("action")
        require(action in allowed) { "Unsupported task step: $action" }
        if(command.has("ifAvailable"))require(command.opt("ifAvailable") is Boolean && action=="skip_intro") { "ifAvailable applies to a conditional intro skip" }
        when (action) {
            "seek", "seek_relative", "playback_speed", "volume", "brightness" -> {
                require(command.opt("numberValue") is Number)
                val number = command.getDouble("numberValue")
                require(number.isFinite() && (action != "seek" || number >= 0) && kotlin.math.abs(number) <= 604800)
                if (action == "playback_speed") require(number in listOf(.5, .75, 1.0, 1.25, 1.5, 2.0))
                if (action in setOf("volume", "brightness")) require(number in 0.0..1.0)
            }
            "audio_language", "subtitle_language" -> {
                val language=command.getString("stringValue")
                require(language.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*|off")))
                require(action!="audio_language"||language!="off")
            }
            "select_episode" -> { require(command.talkOptionalInteger("season") != null); require(command.talkOptionalInteger("episode", 1) != null) }
            "play" -> TalkEpisodeArguments.from(command)
            "select_server" -> require(command.getString("stringValue").isNotBlank())
            "select_provider" -> require(com.aliflix.app.model.PlaybackProvider.fromStoredValue(command.getString("stringValue")) != null)
            "quality" -> require(command.getString("stringValue").uppercase(java.util.Locale.ROOT).matches(Regex("AUTO|LOW|[1-9][0-9]{1,3}P?")))
            "rotate" -> require(command.getString("stringValue") in setOf("portrait", "landscape", "auto"))
            "set_setting" -> talkValidateSetting(command)
        }
    }
    return steps
}

internal fun talkValidateSetting(command:JSONObject) {
    when(command.getString("setting")) {
        "play_next_episode","audio_boost","zoom","auto_subtitles","recommendations_enabled" -> require(command.opt("enabled") is Boolean)
        "playback_speed" -> require(command.talkSettingNumber(.5f..2f) in listOf(.5f,.75f,1f,1.25f,1.5f,2f))
        "subtitle_size" -> command.talkSettingNumber(11f..30f)
        "subtitle_opacity" -> command.talkSettingNumber(0f..1f)
        "subtitle_delay_seconds" -> command.talkSettingNumber(-60f..60f)
        "subtitle_vertical_offset" -> command.talkSettingNumber(-200f..200f)
        "preferred_quality" -> require(command.getString("stringValue").uppercase(java.util.Locale.ROOT) in setOf("AUTO","LOW"))
        "subtitle_language" -> require(com.aliflix.app.model.SubtitleLanguage.entries.any{it.code.equals(command.getString("stringValue"),true)})
        "provider" -> require(com.aliflix.app.model.PlaybackProvider.fromStoredValue(command.getString("stringValue"))?.supportsGeneralPlayback == true)
        else -> error("Unknown setting")
    }
}

internal fun talkTaskSuccess(status: String) = status in setOf("completed", "current", "observed", "playing", "prepared", "already_applied", "not_applicable")
internal fun talkTaskJson(steps: List<JSONObject>) = JSONArray(steps)
