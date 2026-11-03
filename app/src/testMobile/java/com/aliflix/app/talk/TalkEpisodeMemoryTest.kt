package com.aliflix.app.talk

import com.aliflix.app.player.SubtitleCue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkEpisodeMemoryTest {
    private fun cue(start: Double, end: Double, text: String) = SubtitleCue(start, end, text)

    private val cues = listOf(
        cue(0.0, 2.0, "At the beginning the mother arrives."),
        cue(4.0, 6.0, "The boy asks about his father."),
        cue(8.0, 10.0, "She hides the letter."),
        cue(40.0, 42.0, "A stranger watches the house."),
        cue(44.0, 46.0, "The mother locks the door."),
        cue(48.0, 50.0, "The stranger walks away."),
        cue(80.0, 82.0, "The stranger breaks in."),
        cue(84.0, 86.0, "The mother hides the boy."),
        cue(88.0, 90.0, "The boy runs upstairs."),
        cue(120.0, 122.0, "The ending reveals the stranger."),
    )

    private fun packet(question: String, anchor: Double = 100.0,
        watched:List<ClosedFloatingPointRange<Double>> = listOf(0.0..100.0)):JSONObject {
        val request = parseTalkRecallRequest(question, anchor)
        return buildTalkRecallPacket(question, request, anchor, cues, "timed_captions", "edition-1",
            watched, listOf(10.0, 50.0, 90.0))
    }

    @Test fun previousSceneComesFromTheWholeEpisodeEvenAfterASeek() {
        val request = parseTalkRecallRequest("What happened in the previous scene?", 100.0)
        assertEquals(TalkRecallMode.PREVIOUS, request.mode)
        val result = packet("What happened in the previous scene?")
        val scenes = result.getJSONArray("scenes")
        assertEquals(3, scenes.length())
        assertEquals("earlier", scenes.getJSONObject(0).getString("role"))
        assertEquals(0.0, scenes.getJSONObject(0).getDouble("startSeconds"), .001)
        assertEquals("previous_candidate", scenes.getJSONObject(1).getString("role"))
        assertEquals(40.0, scenes.getJSONObject(1).getDouble("startSeconds"), .001)
        assertFalse(result.has("currentScene"))
        assertEquals("latest_exchange", scenes.getJSONObject(2).getString("role"))
        assertEquals(80.0, scenes.getJSONObject(2).getDouble("startSeconds"), .001)
        assertFalse(result.getBoolean("dialogueWindowsAreSceneBoundaries"))
        assertFalse(result.toString().contains("ending reveals"))
    }

    @Test fun timestampRecallSelectsTheActualEarlierScene() {
        val request = parseTalkRecallRequest("What happened at 0:20?", 100.0)
        assertEquals(TalkRecallMode.TIMESTAMP, request.mode)
        assertEquals(20.0, request.targetSeconds!!, .001)
        val result = packet("What happened at 0:20?")
        val scenes = result.getJSONArray("scenes")
        assertEquals(1, scenes.length())
        assertEquals("target", scenes.getJSONObject(0).getString("role"))
        assertEquals(0.0, scenes.getJSONObject(0).getDouble("startSeconds"), .001)
        assertTrue(scenes.getJSONObject(0).getJSONArray("dialogue").toString().contains("hides the letter"))
        assertFalse(result.toString().contains("ending reveals"))
    }

    @Test fun aTimestampBeyondThePlayheadIsRefusedInsteadOfSpoiled() {
        val request = parseTalkRecallRequest("What happened at 6:20?", 100.0)
        assertEquals(TalkRecallMode.TIMESTAMP, request.mode)
        assertTrue(request.targetBeyondAnchor)
        val result = packet("What happened at 6:20?")
        assertEquals(0, result.getJSONArray("scenes").length())
        assertTrue(result.getJSONArray("evidenceGaps").toString().contains("beyond the current playhead"))
        assertFalse(result.toString().contains("ending reveals"))
    }

    @Test fun recapUsesTheTimelineAndExplicitlyMarksUnwatchedEvidence() {
        val result = packet("Recap the episode from the beginning", watched = listOf(0.0..10.0, 40.0..50.0))
        assertEquals("recap", result.getString("mode"))
        val scenes = result.getJSONArray("scenes")
        assertEquals(3, scenes.length())
        scenes.getJSONObject(0).let {
            assertTrue(it.has("excerpt"))
            assertEquals(1.0, it.getDouble("watchedFraction"), .001)
        }
        scenes.getJSONObject(2).let {
            assertEquals(0.0, it.getDouble("watchedFraction"), .001)
            assertTrue(it.getJSONArray("visualFrameSeconds").length() > 0)
        }
        assertTrue(result.getJSONArray("evidenceGaps").toString().contains("transcript"))
        assertFalse(result.toString().contains("ending reveals"))
    }

    @Test fun scenesNeverCrossThePlayhead() {
        val result = packet("What happened in the previous scene?", 45.0)
        assertTrue(result.getDouble("anchorSeconds") == 45.0)
        assertFalse(result.toString().contains("stranger breaks in"))
        assertFalse(result.toString().contains("ending reveals"))
    }

    @Test fun recallDetectionCoversPreviousTimestampAndRecap() {
        assertEquals(TalkRecallMode.PREVIOUS, parseTalkRecallRequest("ماذا حدث في المشهد السابق؟", 100.0).mode)
        assertEquals(TalkRecallMode.RECAP, parseTalkRecallRequest("لخص الحلقة من البداية", 100.0).mode)
        assertTrue(talkEpisodeRecallQuestion("What happened in the previous scene?"))
        assertTrue(talkEpisodeRecallQuestion("What happened at 6:20?"))
        assertTrue(talkEpisodeRecallQuestion("Recap the episode from the beginning"))
        assertFalse(talkEpisodeRecallQuestion("Lower the volume"))
    }

    @Test fun paraphrasedCausalQuestionStillReceivesTheStoryFromTheBeginning() {
        val result = packet("Why did she fall down?")
        val story = result.getJSONObject("storyThroughAnchor")
        assertTrue(story.getBoolean("complete"))
        val dialogue = story.getJSONArray("dialogue").toString()
        assertTrue(dialogue.contains("mother arrives"))
        assertTrue(dialogue.contains("hides the letter"))
        assertTrue(dialogue.contains("stranger breaks in"))
        assertFalse(dialogue.contains("ending reveals"))
        assertTrue(talkEpisodeRecallQuestion("Why did she fall down?"))
    }

    @Test fun anotherLanguageDoesNotRemoveEarlierDialogueFromEvidence() {
        val result = packet("Warum ist sie gefallen?")
        assertTrue(result.getJSONObject("storyThroughAnchor").getJSONArray("dialogue").toString().contains("hides the letter"))
    }

    @Test fun aRewindRemovesLaterEventsEvenWhenTheyWerePreviouslyWatched() {
        val result = packet("What caused this?", anchor = 45.0, watched = listOf(0.0..150.0))
        val story = result.getJSONObject("storyThroughAnchor").getJSONArray("dialogue")
        for (index in 0 until story.length()) assertTrue(story.getJSONArray(index).getDouble(1) <= 45.0)
        assertFalse(story.toString().contains("stranger breaks in"))
        assertFalse(story.toString().contains("ending reveals"))
    }

    @Test fun anOversizeTranscriptExplicitlyReportsCoverageRatherThanPretendingToKnowEverything() {
        val scenes = buildTalkCueScenes(cues, 100.0)
        val story = talkStoryTranscript(scenes, characterBudget = 65)
        assertFalse(story.getBoolean("complete"))
        assertTrue(story.getInt("includedCues") < story.getInt("eligibleCues"))
        assertTrue(story.getDouble("throughSeconds") < 100.0)
    }
    @Test fun largeQuestionContextKeepsEarlierMatchesAndRecentStoryWithFullRecallAvailable() {
        val longCues=(0..2000 step 2).map { second -> cue(second.toDouble(),second+1.0,
            if(second==200) "The note explains her collapse" else "Unrelated conversation at $second with enough words to fill a long transcript") }
        val query="Why did she collapse?"
        val request=parseTalkRecallRequest(query,2000.0)
        fun recall(full:Boolean)=buildTalkRecallPacket(query,request,2000.0,longCues,"timed_captions","edition",emptyList(),emptyList(),includeWholeStory=full).getJSONObject("storyThroughAnchor")
        val focused=recall(false)
        assertEquals("question_context",focused.getString("coverage"))
        assertTrue(focused.getBoolean("fullPrefixAvailable"))
        assertTrue(focused.getJSONArray("dialogue").toString().contains("note explains"))
        val compact=buildTalkRecallPacket(query,request,2000.0,longCues,"timed_captions","edition",emptyList(),emptyList())
        assertFalse(compact.getJSONArray("scenes").getJSONObject(0).has("dialogue"))
        assertTrue(focused.getDouble("throughSeconds")<=2000.0)
        assertTrue(focused.getInt("includedCues") < recall(true).getInt("includedCues"))
    }
    @Test fun immediatePreviousSceneUsesFocusedContextWhileDeepRecallCanReadTheWholeStory() {
        val longCues=cues+listOf(cue(1300.0,1302.0,"Later setup"),cue(1900.0,1902.0,"Immediate exchange"))
        val query="What happened immediately before this scene?"
        val request=parseTalkRecallRequest(query,2000.0)
        fun recall(full:Boolean)=buildTalkRecallPacket(query,request,2000.0,longCues,"timed_captions","edition",emptyList(),emptyList(),includeWholeStory=full)
        val focused=recall(false).getJSONObject("storyThroughAnchor")
        assertEquals("recent_context",focused.getString("coverage"))
        assertTrue(focused.getBoolean("fullPrefixAvailable"))
        assertFalse(focused.getJSONArray("dialogue").toString().contains("mother arrives"))
        assertTrue(recall(true).getJSONObject("storyThroughAnchor").getJSONArray("dialogue").toString().contains("mother arrives"))
    }
}
