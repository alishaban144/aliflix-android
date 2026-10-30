package com.aliflix.app.talk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkTaskPlanTest {
    private fun plan(vararg steps: JSONObject) = JSONObject().put("commands", JSONArray(steps.toList()))
    private fun action(name: String) = JSONObject().put("action", name)
    @Test fun allRequestedOperationsRetainTheirOrderAndSpecialsIdentity() {
        val steps = talkTaskPlan(plan(action("play").put("season", 0).put("episode", 1).put("_talkCallId","private-native-receipt"),
            action("audio_language").put("stringValue", "de"), action("subtitle_language").put("stringValue", "ar"),
            action("sync_subtitles"), action("seek").put("numberValue", 318.5), action("resume")))
        assertEquals(listOf("play", "audio_language", "subtitle_language", "sync_subtitles", "seek", "resume"), steps.map { it.getString("action") })
        assertEquals(0, steps.first().getInt("season"))
        assertEquals(318.5, steps[4].getDouble("numberValue"), 0.0)
        assertFalse(talkTaskJson(steps).toString().contains("private-native-receipt"))
    }
    @Test fun aBadLateStepRejectsTheWholePlanBeforeAnEarlierPlayOrLibraryWrite() {
        for (bad in listOf(action("seek").put("numberValue", "20"), action("seek").put("numberValue", -3),
            action("audio_language").put("stringValue", "https://example.test"), action("select_episode").put("season", 1).put("episode", 0),
            action("playback_speed").put("numberValue", 17), action("delete_account"))) {
            assertThrows(Exception::class.java) { talkTaskPlan(plan(action("play"), action("save"), bad)) }
        }
    }
    @Test fun oneTaskCannotRetargetAnEarlierSeekOrPlayTwoDifferentTitles() {
        assertThrows(IllegalArgumentException::class.java) { talkTaskPlan(plan(action("pause"), action("play"))) }
        assertThrows(IllegalArgumentException::class.java) { talkTaskPlan(plan(action("play"), action("play"))) }
        assertThrows(IllegalArgumentException::class.java) { talkTaskPlan(plan()) }
        assertThrows(IllegalArgumentException::class.java) { talkTaskPlan(plan(*(1..17).map { action("pause") }.toTypedArray())) }
    }
    @Test fun bridgesCanStartBeforeTheFullSentenceButNeverWithAnUnapprovedPrefix() {
        val script = "Dialogue timing follows the selected German audio."
        assertTrue(talkResearchNoteCanStream("Dialogue timing follows", script))
        assertFalse(talkResearchNoteCanStream("Dialogue", script))
        assertFalse(talkResearchNoteCanStream("I have already switched your audio", script))
        assertTrue(talkResearchNoteCanStream(script, script))
        assertFalse(talkResearchNoteCanStream(script + " And another claim", script))
    }
}
