package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbReviewParserTest {

    private val sampleHtml = """
        <div class="card">
          <div class="grouped">
            <div class="avatar">
              <a href="/u/SoSmooth1982/reviews"><img src="https://media.themoviedb.org/t/p/w45_and_h45_face/ast1oGYDI7Li9daLuOV4UxGiXj.jpg" /></a>
            </div>
            <div class="info">
              <h3><a href="/review/64a4949d158c8500acb39957">A review by Andre Gonzales</a></h3>
              <div class="flex items-center">
                <div class="rating_border rating">
                  <span class="glyphicons star invert svg"></span>70<span class="percent">%</span>
                </div>
                <h5>Written by <a href="/u/SoSmooth1982/reviews">Andre Gonzales</a> on July 4, 2023</h5>
              </div>
            </div>
          </div>
          <div class="teaser w-full mb-4">
            <p>Crazy movie. I gotta watch a few more times to get full understanding. The dreams inside of dreams was a bit much, but it makes you think.</p>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun parsesReviewsCorrectly() {
        val client = CatalogClient(pageLoader = { "" })
        val reviews = client.parseReviews(sampleHtml)

        assertEquals(1, reviews.size)
        val review = reviews.first()
        assertEquals("64a4949d158c8500acb39957", review.id)
        assertEquals("Andre Gonzales", review.displayName)
        assertEquals("July 4, 2023", review.createdAt)
        assertEquals(7.0, review.rating ?: 0.0, 0.01)
        assertTrue(review.content.contains("Crazy movie"))
        assertEquals("https://media.themoviedb.org/t/p/w45_and_h45_face/ast1oGYDI7Li9daLuOV4UxGiXj.jpg", review.avatarUrl)
    }

    @Test
    fun parsesTitleDetailsWithReviews() {
        val client = CatalogClient(pageLoader = { "" })
        val initial = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        val parsed = client.parseTitleDetails(sampleHtml, initial)

        assertEquals(1, parsed.reviews.size)
        assertEquals("Andre Gonzales", parsed.reviews[0].displayName)
    }

    @Test
    fun parsesSingleFullReviewPage() {
        val client = CatalogClient(pageLoader = { "" })
        val singleReviewHtml = """
            <div class="flex-1 min-w-0">
                <div class="content">
                    <p>Paragraph 1: In a television landscape where medical dramas are rare.</p>
                    <p>Paragraph 2: Noah Wyle draws on his many years of experience.</p>
                </div>
            </div>
        """.trimIndent()

        val fullText = client.parseSingleReview(singleReviewHtml)
        assertNotNull(fullText)
        assertTrue(fullText!!.contains("Paragraph 1"))
        assertTrue(fullText.contains("Paragraph 2"))
    }

    @Test
    fun stripsReadTheRestFromTeaser() {
        val client = CatalogClient(pageLoader = { "" })
        val teaserHtml = """
            <div class="card">
                <div class="info"><h3><a href="/review/12345">A review by Author</a></h3></div>
                <div class="teaser"><p>Great movie with amazing moments... read the rest.</p></div>
            </div>
        """.trimIndent()

        val reviews = client.parseReviews(teaserHtml)
        assertEquals(1, reviews.size)
        assertEquals("Great movie with amazing moments", reviews[0].content)
    }
}
