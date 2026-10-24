package com.aliflix.app.talk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TalkRecommendationRoutingTest {
    @Test fun discoveredTitlesCannotEscapeIntoGeneralWebNarration() {
        for(question in listOf("movies about gentlemen","Recommend three thoughtful science fiction movies","Find two recent science fiction films about identity.","Search the web for two recent science fiction films about identity.","Something less violent, but still stylish. Give me two titles.","Suggest a series for tonight")) {
            val original=JSONObject("""{"id":"lookup-1","name":"web_research","args":{"query":"web query","recent":true}}""")
            val routed=talkCanonicalLookup(original,question,true)
            assertEquals("recommendations",routed.getString("name"))
            assertEquals(question,routed.getJSONObject("args").getString("query"))
            assertFalse(routed.getJSONObject("args").has("recent"))
            assertEquals("web_research",original.getString("name"))
            val wire=talkFunctionResponse(original.getString("id"),original.getString("name"),JSONObject(),"WHEN_IDLE")
            assertEquals("lookup-1",wire.getString("id"));assertEquals("web_research",wire.getString("name"))
        }
        assertEquals("tv",talkRecommendationArgs("Find two recent series").getString("mediaType"))
    }
    @Test fun greetingsFactsAndExistingCardControlsNeverStartDiscovery() {
        for(question in listOf("Hi","Hello","Why is the sky blue?","Tell me more about Annihilation","Who directed the movie Arrival?","Show its card again.","Show all three recommendations again.","Explain the ending of this film","Find the release date of Dune","I recommend the movie Arrival","Don't recommend horror movies","Do you recommend the movie Arrival?","Search the web for news about recent science fiction films")) {
            assertFalse(question,talkDiscoveryQuestion(question,true))
        }
    }
}
