package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the titles that previously lost their Rotten Tomatoes score.
 *
 * Each fixture is a real page captured from Rotten Tomatoes, so the assertions describe the actual
 * markup the site serves today rather than a simplified imitation of it.
 */
class RottenTomatoesVanityResolutionTest {
    private fun page(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("rt/$name")) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }
            .removePrefix(RottenTomatoesClient.BYTE_ORDER_MARK)

    private fun movie(title: String, year: String) = Media(1, MediaType.MOVIE, title, year = year)
    private fun series(title: String, year: String) = Media(2, MediaType.TV, title, year = year)

    /** Serves the same captured page for every request and resolves candidates from a fixed list. */
    private fun client(html: String, vararg hits: RottenTomatoesSearchHit) = RottenTomatoesClient(
        RottenTomatoesTransport { url -> RtHttpResponse(url, url, 200, "text/html", html, 10) },
        {},
        offlineSearchIndex(*hits),
    )

    /** A search index that answers with fixed candidates and never reaches the network. */
    private fun offlineSearchIndex(vararg hits: RottenTomatoesSearchHit) = RottenTomatoesSearchIndex(
        object : RottenTomatoesSearchIndexTransport {
            override suspend fun query(
                credentials: RottenTomatoesSearchIndex.Credentials,
                body: String,
            ): String {
                val typeId = if (body.contains("typeId = 1")) 1 else 2
                val prefix = if (typeId == 1) "/m/" else "/tv/"
                val records = hits.joinToString(",") { hit ->
                    """{"vanity":"${hit.path.removePrefix(prefix)}","typeId":$typeId,""" +
                        """"title":"${hit.title}","releaseYear":${hit.year ?: 0}}"""
                }
                return """{"hits":[$records]}"""
            }

            override suspend fun discoverCredentials(
                fallback: RottenTomatoesSearchIndex.Credentials,
            ): RottenTomatoesSearchIndex.Credentials? = null
        },
    )

    private fun hit(path: String, title: String, year: Int, type: MediaType = MediaType.MOVIE) =
        RottenTomatoesSearchHit(path, title, year, type, null)


    @Test fun `avatar resolves despite a page creation date from its re-release`() = runBlocking {
        val result = client(page("avatar_2009.html")).loadFetchResult(movie("Avatar", "2009"))
        assertEquals(81, (result as RottenTomatoesFetchResult.Verified).rating)
    }

    @Test fun `the avengers resolves through the vanity the website uses`() = runBlocking {
        val result = client(
            page("avengers_2012.html"),
            hit("/m/marvels_the_avengers", "Marvel's the Avengers", 2012),
        ).loadFetchResult(movie("The Avengers", "2012"))
        assertEquals(91, (result as RottenTomatoesFetchResult.Verified).rating)
    }

    @Test fun `wild tales keeps its score when the page creation year is later`() = runBlocking {
        val result = client(page("wild_tales_2014.html")).loadFetchResult(movie("Wild Tales", "2014"))
        assertEquals(94, (result as RottenTomatoesFetchResult.Verified).rating)
    }

    @Test fun `caveat resolves from a slugged page whose release year is the festival year`() = runBlocking {
        val result = client(
            page("caveat_2021.html"),
            hit("/m/caveat_2021", "Caveat", 2020),
        ).loadFetchResult(movie("Caveat", "2021"))
        assertEquals(82, (result as RottenTomatoesFetchResult.Verified).rating)
    }

    @Test fun `a series whose vanity differs from its title resolves to its real page`() = runBlocking {
        // Rotten Tomatoes files the 1996 anime as Case Closed and publishes no critic score for it,
        // so the correct answer is a resolved page reported as not rated rather than a failure.
        val result = client(
            page("detective_conan_1996.html"),
            hit("/tv/case_closed_1996", "Case Closed", 1996, MediaType.TV),
        ).loadFetchResult(series("Detective Conan", "1996"))
        assertEquals(RottenTomatoesFetchResult.ConfirmedNotRated, result)
    }

    @Test fun `a wrong-year page is still rejected after the release year change`() {
        val client = RottenTomatoesClient { "" }
        val item = movie("Same Name", "2024")
        fun html(year: String) = """<title>Same Name $year - Rotten Tomatoes</title>
            <link rel="canonical" href="https://www.rottentomatoes.com/m/same_name">"""
        assertTrue(!client.isIdentityVerified(html(""), item))
        assertTrue(!client.isIdentityVerified(html("(2023)"), item))
        assertTrue(client.isIdentityVerified(html("(2024)"), item))
    }

    @Test fun `an unrelated page can never satisfy a different title`() {
        val client = RottenTomatoesClient { "" }
        assertTrue(!client.isIdentityVerified(page("avengers_2012.html"), movie("Parasite", "2019")))
    }

    @Test fun `the critic score never falls back to the audience score`() {
        val client = RottenTomatoesClient { "" }
        val html = """
            <script type="application/json" data-json="reviewsData">
              {"audienceScore":{"score":"97"},"criticsScore":{"score":"41"}}
            </script>
        """.trimIndent()
        assertEquals(41, client.parseRating(html))
    }

    @Test fun `an index hit for the wrong media type is discarded`() {
        val index = RottenTomatoesSearchIndex(
            object : RottenTomatoesSearchIndexTransport {
                override suspend fun query(
                    credentials: RottenTomatoesSearchIndex.Credentials,
                    body: String,
                ): String = error("the parser test index must never be called")

                override suspend fun discoverCredentials(
                    fallback: RottenTomatoesSearchIndex.Credentials,
                ): RottenTomatoesSearchIndex.Credentials? = null
            },
        )
        val hits = index.parseHits(
            """{"hits":[{"vanity":"wrong","typeId":1,"title":"Wrong","releaseYear":1999}]}""",
            MediaType.TV,
        )
        assertTrue(hits.isEmpty())
    }

    @Test fun `an index hit must never turn a page into a different title`() = runBlocking {
        // The index can only propose a page for the title that was asked about, and the year on the
        // page still has to agree, so a proposal cannot smuggle in an unrelated title.
        val client = RottenTomatoesClient(
            RottenTomatoesTransport { url -> RtHttpResponse(url, url, 200, "text/html", page("wild_tales_2014.html"), 5) },
            {},
            offlineSearchIndex(hit("/m/wild_tales", "Wild Tales", 2014)),
        )
        val result = client.loadFetchResult(movie("The Shining", "1980"))
        assertTrue("a different title must not be accepted, got $result", result is RottenTomatoesFetchResult.Unavailable)
    }
}
