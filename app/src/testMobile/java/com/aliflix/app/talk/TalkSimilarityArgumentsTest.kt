package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class TalkSimilarityArgumentsTest {
    private val old=TalkTitleCard(101,MediaType.MOVIE,"Owned remake","1982")
    private val recent=old.copy(id=202,year="2011")
    private val cards=TalkCardContext().show(listOf(old,recent),replaceGroup=true).show(listOf(recent))
    @Test fun aSelectedEditionCarriesItsCanonicalIdIntoSimilarityResearch() {
        val args=JSONObject().put("similarTo",JSONArray().put("Owned remake"))
        val result=talkCanonicalRecommendationArgs(args,"Find films similar to Owned remake",cards)
        assertEquals(202,result.getJSONArray("canonicalAnchors").getJSONObject(0).getInt("tmdbId"))
    }
    @Test fun explicitEditionAndOutputYearAreDifferentConstraints() {
        val args=JSONObject().put("similarTo",JSONArray().put("Owned remake"))
        assertEquals(101,talkCanonicalRecommendationArgs(args,"Something like Owned remake (1982)",cards).getJSONArray("canonicalAnchors").getJSONObject(0).getInt("tmdbId"))
        assertEquals(202,talkCanonicalRecommendationArgs(args,"Find films from 2020 onward similar to Owned remake",cards).getJSONArray("canonicalAnchors").getJSONObject(0).getInt("tmdbId"))
    }
    @Test fun modelSuppliedIdsCannotOverrideActualEstablishedCards() {
        val args=JSONObject().put("similarTo",JSONArray().put("Owned remake (2015)")).put("canonicalAnchors",JSONArray().put(JSONObject().put("tmdbId",999)))
        assertFalse(talkCanonicalRecommendationArgs(args,"Owned remake (2015)",cards).has("canonicalAnchors"))
    }
    @Test fun aFallbackSearchCanUseEstablishedPositiveAnchorsButNotExcludedOnes() {
        assertEquals(202,talkCanonicalRecommendationArgs(JSONObject(),"Find something like Owned remake",cards).getJSONArray("canonicalAnchors").getJSONObject(0).getInt("tmdbId"))
        assertFalse(talkCanonicalRecommendationArgs(JSONObject(),"Find films, but not like Owned remake",cards).has("canonicalAnchors"))
    }
}
