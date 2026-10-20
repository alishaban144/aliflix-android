package com.aliflix.app.talk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkChatHistoryTest {
    @Test fun archiveRoundTripKeepsDialogueAndAttribution() {
        val chat=TalkChat("chat","Sci-fi",1234,listOf(TalkLine("user","A recent film"),TalkLine("assistant","A researched choice",listOf(TalkReference("Source","https://example.org/film","Publisher")))))
        assertEquals(chat,parseTalkChat(chat.json()))
    }
    @Test fun remoteSummaryDoesNotInventDialogue() {
        val summary=parseTalkChat(JSONObject().put("id","a").put("title","Conversation").put("updatedAt",1234))
        assertTrue(summary.lines.isEmpty())
    }
}
