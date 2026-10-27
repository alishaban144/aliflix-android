package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class TalkCatalogueRefinementTest {
    @Test fun strictRefinementsKeepTheirPreviouslyVerifiedMatchesEligible() {
        val cards=TalkCardContext().show(listOf(TalkTitleCard(1,MediaType.MOVIE,"A verified choice")))
        val question="Only films from 2020 onward. Keep the other constraints."
        assertTrue(talkDiscoveryQuestion(question,true))
        assertTrue(talkDiscoveryExclusions(question,cards).isEmpty())
        assertTrue(talkDiscoveryQuestion("Only ended series. Keep the other constraints from this search.",true))
        assertTrue(talkDiscoveryQuestion("More different titles with the same constraints. Exclude the ones already shown.",true))
    }
    @Test fun factualFollowupsDoNotAccidentallyStartAnotherSearch() {
        for(question in listOf("More about the second one","Only explain the ending","What happened in 2020?","Which one should I choose?"))assertFalse(question,talkDiscoveryQuestion(question,true))
    }
}
