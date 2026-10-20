package com.aliflix.app.player

import com.aliflix.app.data.*
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class ReissueRatingIdentityTest {
    private val repository=DefaultImdbRatingRepository(null,{error("No network")},ImdbGraphQlTransport { _,_,_->error("No network") })
    @Test fun exactIdentityAllowsAnOriginalReleaseYearForAReissue() {
        val payload="""{"data":{"title":{"id":"tt1997399","titleText":{"text":"Incoherence"},"releaseYear":{"year":1994},"titleType":{"id":"short"},"ratingsSummary":{"aggregateRating":6.8,"voteCount":1000}}}}"""
        val expected=ImdbTitleIdentity("tt1997399","Incoherence",2025,MediaType.MOVIE)
        assertEquals(6.8,repository.parseGraphQlRating(payload,expected)!!.rating!!,.001)
        try { repository.parseGraphQlRating(payload,expected.copy(imdbId="tt0181689"));fail("A different IMDb ID must be rejected") } catch(_:java.io.IOException) { }
    }
}
