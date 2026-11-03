package com.aliflix.app.talk

import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.*
import com.aliflix.app.ui.discover.OrbExpression

internal enum class TalkOrbActivity { REST, LISTEN, READ, RECALL, OBSERVE, THINK, WRITE, SPEAK, MUTED, CONNECT, UNAVAILABLE }

@Composable internal fun rememberTalkPresentation(session: TalkVoiceSession): State<TalkVoiceState> {
    val observed=session.state.collectAsState()
    return remember(observed) { derivedStateOf { observed.value.copy(level=0f) } }
}

internal fun talkOrbPhaseActivity(phase: String): TalkOrbActivity = when(phase) {
    "Listening" -> TalkOrbActivity.LISTEN
    "Speaking" -> TalkOrbActivity.SPEAK
    "Thinking" -> TalkOrbActivity.THINK
    "Muted" -> TalkOrbActivity.MUTED
    "Connecting", "Reconnecting", "Opening chat" -> TalkOrbActivity.CONNECT
    "Reconnect", "Try again later", "Audio unavailable", "Reply unavailable", "Opened elsewhere" -> TalkOrbActivity.UNAVAILABLE
    else -> TalkOrbActivity.REST
}

internal fun talkOrbActivity(state: TalkVoiceState, reading: Boolean = false): TalkOrbActivity = when {
    state.phase in setOf("Reconnect", "Try again later", "Audio unavailable", "Reply unavailable", "Opened elsewhere") -> TalkOrbActivity.UNAVAILABLE
    state.phase in setOf("Connecting", "Reconnecting", "Opening chat") -> TalkOrbActivity.CONNECT
    state.phase == "Speaking" -> TalkOrbActivity.SPEAK
    state.evidenceStage == TalkEvidenceStage.RECALL -> TalkOrbActivity.RECALL
    state.evidenceStage == TalkEvidenceStage.OBSERVE -> TalkOrbActivity.OBSERVE
    state.textMode && state.sending && state.lines.lastOrNull()?.let { it.role=="assistant" && it.text.isNotBlank() } == true -> TalkOrbActivity.WRITE
    state.sending || state.phase == "Thinking" -> TalkOrbActivity.THINK
    reading || state.textMode -> TalkOrbActivity.READ
    state.muted || state.phase == "Muted" -> TalkOrbActivity.MUTED
    state.phase == "Listening" -> TalkOrbActivity.LISTEN
    else -> TalkOrbActivity.REST
}

internal data class TalkOrbAppearance(val face: OrbExpression, val glow: Float, val flow: Float, val breath: Float, val tilt: Float, val label: String)

internal fun talkOrbAppearance(activity: TalkOrbActivity): TalkOrbAppearance = when (activity) {
    TalkOrbActivity.LISTEN -> TalkOrbAppearance(OrbExpression(Offset(0f,.08f),1.08f),.68f,.72f,.005f,0f,"Listening")
    TalkOrbActivity.READ -> TalkOrbAppearance(OrbExpression(Offset(0f,-.28f),.91f),.34f,.26f,.002f,0f,"Ready for your message")
    TalkOrbActivity.RECALL -> TalkOrbAppearance(OrbExpression(Offset(-.32f,.12f),.83f, asymmetry=.045f),.60f,.48f,.004f,-2f,"Connecting the story")
    TalkOrbActivity.OBSERVE -> TalkOrbAppearance(OrbExpression(Offset(-.48f,-.03f),.89f),.68f,.58f,.003f,-1.2f,"Following the scene")
    TalkOrbActivity.THINK -> TalkOrbAppearance(OrbExpression(Offset(-.16f,.22f),.76f, asymmetry=.055f),.54f,.40f,.004f,1.5f,"Thinking")
    TalkOrbActivity.WRITE -> TalkOrbAppearance(OrbExpression(Offset(0f,-.13f),.96f),.60f,.60f,.003f,0f,"Replying")
    TalkOrbActivity.SPEAK -> TalkOrbAppearance(OrbExpression(Offset(0f,.04f),1f),.72f,.82f,.004f,0f,"Speaking")
    TalkOrbActivity.MUTED -> TalkOrbAppearance(OrbExpression(Offset(0f,.04f),.79f),.24f,.12f,0f,0f,"Microphone muted")
    TalkOrbActivity.CONNECT -> TalkOrbAppearance(OrbExpression(Offset(0f,.13f),.91f),.38f,.34f,.002f,0f,"Connecting")
    TalkOrbActivity.UNAVAILABLE -> TalkOrbAppearance(OrbExpression(Offset(0f,-.04f),.86f),.24f,0f,0f,0f,"Connection unavailable")
    TalkOrbActivity.REST -> TalkOrbAppearance(OrbExpression(Offset(0f,.06f),1f),.30f,.20f,.002f,0f,"Ready")
}
