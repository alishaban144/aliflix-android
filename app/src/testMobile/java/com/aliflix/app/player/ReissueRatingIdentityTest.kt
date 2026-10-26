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
        assertEquals(1994,repository.parseGraphQlRating(payload,expected)!!.originalReleaseYear)
        try { repository.parseGraphQlRating(payload,expected.copy(imdbId="tt0181689"));fail("A different IMDb ID must be rejected") } catch(_:java.io.IOException) { }
    }
    @Test fun currentRtHeaderVerifiesAnUnratedShortWithoutBorrowingAnAudienceScore() {
        val client=RottenTomatoesClient { "" }
        val page="""<title>Incoherence | Rotten Tomatoes</title><link rel="canonical" href="https://www.rottentomatoes.com/m/incoherence">
            <media-hero><rt-text slot="metadata-prop">1994</rt-text><rt-text slot="metadata-prop">31m</rt-text></media-hero>
            <script type="application/json" data-json="reviewsData">{"criticsScore":{"ratingCount":0,"reviewCount":0},"audienceScore":{"score":90}}</script>"""
        assertTrue(client.isIdentityVerified(page,Media(67415,MediaType.MOVIE,"Incoherence",year="1994")))
        assertFalse(client.isIdentityVerified(page,Media(67415,MediaType.MOVIE,"Incoherence",year="2025")))
        assertTrue(client.hasConfirmedNoCriticRating(page))
        assertNull(client.parseRating(page))
    }
}
