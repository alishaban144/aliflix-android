package com.aliflix.app.data

import com.aliflix.app.model.MediaType
import com.aliflix.app.model.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogClientTest {
    private val client = CatalogClient { error("Network must not be used by parser tests") }

    @Test
    fun parsesNativeRailsAndHeroFromServerRenderedHtml() {
        val html = """
            <html><body>
              <section aria-label="Hero">
                <h1>Inception</h1>
                <img src="https://image.tmdb.org/t/p/original/hero.jpg" />
                <a href="/watch/movie/27205">Play</a>
              </section>
              <section aria-label="Carousel of shows">
                <h2>Trending Now</h2>
                <picture>
                  <a aria-label="Inception" href="/movie/inception-27205"></a>
                  <img alt="Inception" src="https://image.tmdb.org/t/p/w500/poster.jpg" />
                </picture>
                <picture>
                  <a aria-label="Stranger Things" href="/tv/stranger-things-66732"></a>
                  <img alt="Stranger Things" src="https://image.tmdb.org/t/p/w500/tv.jpg" />
                </picture>
              </section>
            </body></html>
        """.trimIndent()

        val page = client.parsePage(html)

        assertEquals("Trending Now", page.rails.single().title)
        assertEquals(2, page.rails.single().items.size)
        assertEquals(MediaType.TV, page.rails.single().items[1].type)
        assertEquals(27205, page.hero?.id)
        assertEquals(
            "https://image.tmdb.org/t/p/original/hero.jpg",
            page.hero?.backdropUrl,
        )
        assertNotNull(page.hero)
    }

    @Test
    fun parsesCompleteSearchResultsFromPublicCatalogueHtml() {
        val html = """
            <main>
              <div data-object-id="movie-238">
                <a data-media-type="movie" href="/movie/238-the-godfather">
                  <img class="poster" alt="The Godfather"
                    src="https://media.themoviedb.org/t/p/w94_and_h141_face/godfather.jpg" />
                </a>
                <a data-media-type="movie" href="/movie/238-the-godfather">
                  <h2>The Godfather</h2>
                </a>
                <span class="release_date">August 24, 1972</span>
                <p>A crime family chronicle.</p>
              </div>
              <div data-object-id="tv-66732">
                <a data-media-type="tv" href="/tv/66732-stranger-things">
                  <img class="poster" alt="Stranger Things"
                    src="https://media.themoviedb.org/t/p/w94_and_h141_face/stranger.jpg" />
                </a>
                <a data-media-type="tv" href="/tv/66732-stranger-things">
                  <h2>Stranger Things</h2>
                </a>
                <span class="release_date">July 15, 2016</span>
              </div>
            </main>
        """.trimIndent()

        val results = client.parseSearchResults(html)

        assertEquals(2, results.size)
        assertEquals(238, results[0].id)
        assertEquals("1972", results[0].year)
        assertEquals("https://image.tmdb.org/t/p/w500/godfather.jpg", results[0].posterUrl)
        assertEquals(MediaType.TV, results[1].type)
    }

    @Test
    fun parsesOfficialTitlePageRecommendations() {
        val html = """
            <main>
              <section class="recommendations">
                <div class="item mini backdrop mini_card">
                  <a href="/movie/49026-the-dark-knight-rises"
                    title="The Dark Knight Rises">
                    <img alt="The Dark Knight Rises"
                      src="https://media.themoviedb.org/t/p/w250/backdrop.jpg" />
                  </a>
                  <p class="title">The Dark Knight Rises</p>
                  <span>2012</span>
                </div>
                <div class="item mini backdrop mini_card">
                  <a href="/movie/27205-inception" title="Inception"></a>
                  <img alt="Inception"
                    data-src="https://media.themoviedb.org/t/p/w250/inception.jpg" />
                </div>
              </section>
            </main>
        """.trimIndent()

        val results = client.parseRelatedResults(html)

        assertEquals(listOf(49026, 27205), results.map(Media::id))
        assertEquals("2012", results.first().year)
        assertEquals(
            "https://image.tmdb.org/t/p/w500/backdrop.jpg",
            results.first().posterUrl,
        )
    }

    @Test
    fun parsesSeasonsAndEpisodesForNativeTvPicker() {
        val seasonsHtml = """
            <main>
              <div>
                <a href="/tv/66732-stranger-things/season/1?language=en-US">
                  <img alt="Season 1" src="https://media.themoviedb.org/t/p/w130/season1.jpg" />
                </a>
                <h4>2016 · 8 Episodes</h4>
              </div>
              <div>
                <a href="/tv/66732-stranger-things/season/2">
                  <img alt="Stranger Things 2" src="https://media.themoviedb.org/t/p/w130/season2.jpg" />
                </a>
                <h4>2017 · 9 Episodes</h4>
              </div>
            </main>
        """.trimIndent()
        val episodesHtml = """
            <main>
              <div class="episode">
                <a data-episode-number="1"
                  href="/tv/66732-stranger-things/season/1/episode/1?language=en-US">
                  <img class="backdrop" alt="Chapter One"
                    src="https://media.themoviedb.org/t/p/w227_and_h127/ep1.jpg" />
                </a>
                <h3><a href="/tv/66732-stranger-things/season/1/episode/1">Chapter One</a></h3>
                <span class="runtime">48m</span>
                <div class="overview"><p>Will sees something terrifying on his way home.</p></div>
              </div>
              <div class="episode">
                <a data-episode-number="2"
                  href="/tv/66732-stranger-things/season/1/episode/2">
                  <img class="backdrop" alt="Chapter Two"
                    src="https://media.themoviedb.org/t/p/w227_and_h127/ep2.jpg" />
                </a>
                <h3><a href="/tv/66732-stranger-things/season/1/episode/2">Chapter Two</a></h3>
                <span class="runtime">55m</span>
                <p>The boys meet a mysterious girl in the woods.</p>
              </div>
            </main>
        """.trimIndent()

        val seasons = client.parseSeasons(seasonsHtml, 66732)
        val episodes = client.parseEpisodes(episodesHtml, 66732, 1)

        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(8, seasons.first().episodeCount)
        assertEquals(listOf("Chapter One", "Chapter Two"), episodes.map { it.title })
        assertEquals(2, episodes.last().number)
        assertEquals("48m", episodes.first().runtime)
        assertEquals("The boys meet a mysterious girl in the woods.", episodes.last().overview)
    }

    @Test
    fun parsesEnglishTitleArtworkPlotAndCredits() {
        val html = """
            <html>
              <head>
                <meta property="og:image"
                  content="https://media.themoviedb.org/t/p/w1920/backdrop.jpg" />
              </head>
              <body><main>
                <section class="header">
                  <img class="poster" src="https://media.themoviedb.org/t/p/w300/poster.jpg" />
                  <h2><a href="/movie/27205-inception">Inception</a>
                    <span class="release_date">(2010)</span>
                  </h2>
                  <a href="/genre/878-science-fiction/movie">Science Fiction</a>
                  <div class="user_score_chart" data-percent="84"></div>
                  <div class="overview"><p>An expert thief enters the dreams of his targets.</p></div>
                </section>
                <ol class="people">
                  <li class="card"><p><a href="/person/6193">Leonardo DiCaprio</a></p></li>
                </ol>
              </main></body>
            </html>
        """.trimIndent()

        val result = client.parseTitleDetails(
            html,
            Media(id = 27205, type = MediaType.MOVIE, title = "Inception"),
        )

        assertEquals("An expert thief enters the dreams of his targets.", result.overview)
        assertEquals("2010", result.year)
        assertEquals(8.4, result.rating, 0.001)
        assertEquals(listOf("Science Fiction"), result.genres)
        assertEquals(listOf("Leonardo DiCaprio"), result.cast)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", result.posterUrl)
    }

    @Test
    fun parsesRealExternalRatingMarkup() {
        val imdb = """
            <script type="application/ld+json">
              {"aggregateRating":{"ratingValue":8.8,"ratingCount":1000}}
            </script>
        """.trimIndent()
        val rottenTomatoes = """
            <script>
              {"criticsScore":{"averageRating":"8.1","score":"87","scorePercent":"87%"}}
            </script>
        """.trimIndent()

        assertEquals(8.8, client.parseImdbRating(imdb) ?: 0.0, 0.001)
        assertEquals(87, client.parseRottenTomatoesRating(rottenTomatoes))
    }

    @Test
    fun ranksRottenTomatoesSearchPathsByExactTitleAndType() {
        val html = """
            <main>
              <a href="/m/dark_knight_of_the_soul">Dark Knight of the Soul</a>
              <a href="/tv/the_dark_knight">A television result</a>
              <search-page-media-row>
                <a href="/m/the_dark_knight">The Dark Knight (2008)</a>
              </search-page-media-row>
            </main>
            <script>
              {"url":"\/m\/the_dark_knight_2008","name":"The Dark Knight"}
            </script>
        """.trimIndent()

        val paths = client.parseRottenTomatoesCandidatePaths(
            html,
            Media(
                id = 155,
                type = MediaType.MOVIE,
                title = "The Dark Knight",
                year = "2008",
            ),
        )

        assertEquals("/m/the_dark_knight", paths.first())
        assertTrue(paths.none { it.startsWith("/tv/") })
    }

    @Test
    fun titleParserDoesNotMistakeASeasonHeadingForTheShowName() {
        val html = """
            <main>
              <h2><a href="/tv/66732-stranger-things/season/5">Stranger Things 5</a></h2>
              <h2><a href="/tv/66732-stranger-things?language=en-US">Stranger Things</a></h2>
              <div class="overview"><p>A supernatural mystery in a small town.</p></div>
            </main>
        """.trimIndent()

        val result = client.parseTitleDetails(
            html,
            Media(id = 66732, type = MediaType.TV, title = "Stranger Things"),
        )

        assertEquals("Stranger Things", result.title)
    }
}
