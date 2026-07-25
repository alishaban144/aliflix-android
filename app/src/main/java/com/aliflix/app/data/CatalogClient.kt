package com.aliflix.app.data

import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.ContentRailKind
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.Season
import com.aliflix.app.recommendation.RelatedContentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the native catalogue from public, server-rendered movie metadata pages.
 *
 * No TMDB account, API key, or copied third-party credential is needed. The
 * player remains separate and loads only after the user presses Play.
 */
class CatalogClient(
    private val jsonPoster: suspend (String, String) -> String = ::postJson,
    private val animeCatalogClient: AniListCatalogClient = AniListCatalogClient(),
    private val pageLoader: suspend (String) -> String = ::downloadPage,
) {
    @Volatile
    private var catalogue: List<Media> = fallbackItems
    private val imdbRatingsCache = ConcurrentHashMap<String, Double>()
    private val rottenTomatoesRatingsCache = ConcurrentHashMap<String, Int>()
    private val animeMappingCache = ConcurrentHashMap<Int, Media>().apply {
        fallbackItems
            .filter { item -> item.aniListId != null }
            .forEach { item -> put(item.aniListId!!, item) }
    }
    private val animeLookupSemaphore = Semaphore(ANIME_LOOKUP_CONCURRENCY)

    suspend fun home(): HomeContent = supervisorScope {
        val trendingAnimeRequest = async {
            runCatching {
                animeCatalogClient.trending().take(ANIME_HOME_LIMIT)
            }.getOrDefault(emptyList())
        }
        val freshRails = tmdbHomeRailSpecs.map { spec ->
            async {
                runCatching {
                    val separator = if ("?" in spec.path) "&" else "?"
                    val items = parseSearchResults(
                        html = pageLoader(
                            "$TMDB_SITE_URL${spec.path}${separator}language=en-US",
                        ),
                    )
                    ContentRail(
                        title = spec.title,
                        items = items.take(HOME_RAIL_LIMIT),
                    )
                }.getOrNull()?.takeIf { it.items.isNotEmpty() }
            }
        }.awaitAll().filterNotNull()

        val seenTitles = mutableSetOf<String>()
        val liveGeneralRails = freshRails.ifEmpty {
            fallbackRails.filter { rail -> rail.kind == ContentRailKind.GENERAL }
        }
        val animeRail = runCatching {
            loadTrendingAnimeRail(
                animeItems = trendingAnimeRequest.await(),
                homeCandidates = liveGeneralRails.flatMap(ContentRail::items),
            )
        }.getOrDefault(fallbackAnimeRail)
        // Anime identity comes from AniList and is mapped to a TMDB record for
        // native details and the general playback providers. A verified
        // fallback rail keeps the tab useful if either catalogue is offline.
        val sourceRails = liveGeneralRails + animeRail
        val rails = sourceRails.mapNotNull { rail ->
            val uniqueItems = if (rail.kind == ContentRailKind.ANIME) {
                rail.items
                    .filter { item ->
                        item.isJapaneseAnime && item.aniListId != null
                    }
                    .distinctBy { item ->
                        "${item.type.routeName}:${normalizeText(item.title)}"
                    }
            } else {
                rail.items.filter { item ->
                    seenTitles.add("${item.type.routeName}:${normalizeText(item.title)}")
                }
            }
            if (uniqueItems.isEmpty()) null else rail.copy(items = uniqueItems)
        }.ifEmpty { listOf(ContentRail("Featured", fallbackItems)) }
        catalogue = buildMap {
            rails.flatMap(ContentRail::items).forEach { item ->
                put(item.key, item)
            }
        }.values.toList().ifEmpty { fallbackItems }

        val hero = rails.firstNotNullOfOrNull { rail ->
            rail.items.firstOrNull { it.backdropPath != null }
        }
            ?: rails.firstNotNullOfOrNull { rail -> rail.items.firstOrNull() }
            ?: fallbackItems.first()
        HomeContent(hero = hero, rails = rails)
    }

    suspend fun search(query: String): List<Media> = supervisorScope {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@supervisorScope emptyList()
        val online = searchTmdb(cleanQuery)

        val source = online
            .ifEmpty { localSearch(cleanQuery) }
            .filterNot { item ->
                item.isJapaneseAnime && item.aniListId != null
            }
        val sorted = SearchRanker.rank(cleanQuery, source).take(80)
        if (online.isNotEmpty()) {
            catalogue = (online + catalogue).distinctBy(Media::key)
        }
        sorted
    }

    /**
     * Dedicated Japanese-anime search. AniList supplies the authoritative
     * anime identity while TMDB supplies the existing native details/seasons
     * ID needed by Ramoflix and 67 Movies.
     */
    suspend fun searchAnime(query: String): List<Media> = supervisorScope {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@supervisorScope emptyList()

        val animeRequest = async {
            animeCatalogClient.search(cleanQuery).take(ANIME_SEARCH_LIMIT)
        }
        val broadTmdbRequest = async { searchTmdb(cleanQuery) }
        val animeItems = animeRequest.await()
        if (animeItems.isEmpty()) return@supervisorScope emptyList()

        val broadPool = (
            broadTmdbRequest.await() +
                animeMappingCache.values +
                fallbackAnimeRail.items
            ).distinctBy(Media::key)
        val directMatches = mutableMapOf<Int, Media>()
        animeItems.forEach { anime ->
            val match = animeMappingCache[anime.aniListId]
                ?: bestTmdbAnimeMatch(anime, broadPool)
            match?.let {
                directMatches[anime.aniListId] = match
            }
        }

        val unresolved = animeItems
            .filterNot { anime -> anime.aniListId in directMatches }
            .take(ANIME_EXPANDED_LOOKUPS)
        val expandedMatches = unresolved.map { anime ->
            async {
                animeLookupSemaphore.withPermit {
                    val candidates = searchTmdb(
                        query = anime.preferredTitle,
                        types = anime.expectedMediaTypes()
                            .map(MediaType::routeName),
                    )
                    anime.aniListId to bestTmdbAnimeMatch(
                        anime = anime,
                        candidates = candidates +
                            animeMappingCache.values +
                            fallbackAnimeRail.items,
                    )
                }
            }
        }.awaitAll().toMap()

        val mapped = animeItems.mapNotNull { anime ->
            val tmdb = directMatches[anime.aniListId]
                ?: expandedMatches[anime.aniListId]
                ?: return@mapNotNull null
            tmdb.withVerifiedAnime(anime)
        }.distinctBy(Media::key)

        if (mapped.isNotEmpty()) {
            mapped.forEach { item ->
                item.aniListId?.let { id -> animeMappingCache[id] = item }
            }
            catalogue = buildMap {
                catalogue.forEach { item -> put(item.key, item) }
                mapped.forEach { item -> put(item.key, item) }
            }.values.toList()
        }
        mapped
    }

    private suspend fun loadTrendingAnimeRail(
        animeItems: List<AniListCatalogItem>,
        homeCandidates: List<Media>,
    ): ContentRail = supervisorScope {
        if (animeItems.isEmpty()) return@supervisorScope fallbackAnimeRail

        val candidatePool = (
            homeCandidates +
                animeMappingCache.values +
                fallbackAnimeRail.items
            ).distinctBy(Media::key)
        val directMatches = mutableMapOf<Int, Media>()
        animeItems.forEach { anime ->
            val match = animeMappingCache[anime.aniListId]
                ?: bestTmdbAnimeMatch(anime, candidatePool)
            match?.let { directMatches[anime.aniListId] = it }
        }

        val expandedMatches = animeItems
            .filterNot { anime -> anime.aniListId in directMatches }
            .take(ANIME_HOME_EXPANDED_LOOKUPS)
            .map { anime ->
                async {
                    animeLookupSemaphore.withPermit {
                        val candidates = searchTmdb(
                            query = anime.preferredTitle,
                            types = anime.expectedMediaTypes()
                                .map(MediaType::routeName),
                        )
                        anime.aniListId to bestTmdbAnimeMatch(
                            anime = anime,
                            candidates = candidates + candidatePool,
                        )
                    }
                }
            }
            .awaitAll()
            .toMap()

        val mapped = animeItems.mapNotNull { anime ->
            val tmdb = directMatches[anime.aniListId]
                ?: expandedMatches[anime.aniListId]
                ?: return@mapNotNull null
            tmdb.withVerifiedAnime(anime)
        }.distinctBy(Media::key)
        mapped.forEach { item ->
            item.aniListId?.let { id -> animeMappingCache[id] = item }
        }

        val items = (mapped + fallbackAnimeRail.items)
            .distinctBy(Media::key)
            .take(HOME_RAIL_LIMIT)
        ContentRail(
            title = JAPANESE_ANIME_RAIL_TITLE,
            items = items,
            kind = ContentRailKind.ANIME,
        )
    }

    private suspend fun searchTmdb(
        query: String,
        types: List<String> = listOf("movie", "tv"),
    ): List<Media> = supervisorScope {
        val encoded = URLEncoder.encode(
            query.trim(),
            StandardCharsets.UTF_8.toString(),
        )
        val resultGroups = types.map { type ->
            async {
                runCatching {
                    parseSearchResults(
                        pageLoader(
                            "$TMDB_SITE_URL/search/$type" +
                                "?query=$encoded&language=en-US",
                        ),
                    )
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        val largestGroup = resultGroups.maxOfOrNull(List<Media>::size) ?: 0
        (0 until largestGroup)
            .flatMap { index ->
                resultGroups.mapNotNull { results -> results.getOrNull(index) }
            }
            .distinctBy(Media::key)
    }

    private fun bestTmdbAnimeMatch(
        anime: AniListCatalogItem,
        candidates: List<Media>,
    ): Media? {
        val expectedTypes = anime.expectedMediaTypes()
        val ranked = candidates
            .asSequence()
            .filter { media -> media.type in expectedTypes }
            .map { media -> media to animeMatchScore(anime, media) }
            .sortedByDescending { (_, score) -> score }
            .toList()
        val best = ranked.firstOrNull()
            ?.takeIf { (_, score) -> score >= MIN_ANIME_MATCH_SCORE }
            ?: return null
        val runnerUp = ranked.getOrNull(1)
        if (
            runnerUp != null &&
            best.second - runnerUp.second < MIN_ANIME_MATCH_MARGIN &&
            best.first.id != runnerUp.first.id
        ) {
            return null
        }
        return best.first
    }

    private fun animeMatchScore(
        anime: AniListCatalogItem,
        media: Media,
    ): Int {
        val mediaTitle = normalizeAniListAlias(media.title)
        val aliases = anime.normalizedAliases
        val titleScore = aliases.maxOfOrNull { alias ->
            when {
                alias == mediaTitle -> 260
                alias.length >= 5 &&
                    (alias.startsWith(mediaTitle) || mediaTitle.startsWith(alias)) -> 195
                alias.length >= 5 &&
                    (alias.contains(mediaTitle) || mediaTitle.contains(alias)) -> 165
                else -> {
                    val aliasTokens = alias.split(' ').filter(String::isNotBlank).toSet()
                    val mediaTokens = mediaTitle.split(' ').filter(String::isNotBlank).toSet()
                    if (aliasTokens.isEmpty() || mediaTokens.isEmpty()) {
                        0
                    } else {
                        val shared = aliasTokens.intersect(mediaTokens).size
                        (shared * 145) / aliasTokens.union(mediaTokens).size
                    }
                }
            }
        } ?: 0
        val animeYear = anime.year
        val mediaYear = fourDigitYear.find(media.year)?.value?.toIntOrNull()
        if (animeYear != null && mediaYear == null) {
            return REJECTED_ANIME_MATCH_SCORE
        }
        val yearDelta = if (animeYear != null && mediaYear != null) {
            kotlin.math.abs(animeYear - mediaYear)
        } else {
            null
        }
        val seasonalContinuation = anime.format in TV_ANIME_FORMATS &&
            aliases.any { alias ->
                alias != mediaTitle &&
                    alias.contains(mediaTitle) &&
                    SEASONAL_TITLE_MARKER.containsMatchIn(alias)
            }
        if (
            yearDelta != null &&
            yearDelta > 2 &&
            !(seasonalContinuation && yearDelta <= MAX_SEASON_YEAR_DELTA)
        ) {
            return REJECTED_ANIME_MATCH_SCORE
        }
        val yearScore = when {
            yearDelta == null -> 0
            yearDelta == 0 -> 65
            yearDelta <= 1 -> 25
            yearDelta <= 2 -> 10
            seasonalContinuation -> -20
            else -> REJECTED_ANIME_MATCH_SCORE
        }
        return titleScore + yearScore
    }

    private fun AniListCatalogItem.expectedMediaTypes(): Set<MediaType> =
        when (format) {
            "MOVIE" -> setOf(MediaType.MOVIE)
            in TV_ANIME_FORMATS -> setOf(MediaType.TV)
            else -> setOf(MediaType.TV, MediaType.MOVIE)
        }

    private fun Media.withVerifiedAnime(anime: AniListCatalogItem): Media = copy(
        overview = overview.ifBlank { anime.description },
        posterPath = posterPath ?: anime.coverImageUrl,
        backdropPath = backdropPath ?: anime.bannerImageUrl ?: anime.coverImageUrl,
        year = year.ifBlank { anime.year?.toString().orEmpty() },
        rating = if (rating > 0.0) {
            rating
        } else {
            anime.averageScore?.div(10.0) ?: 0.0
        },
        genres = (genres + anime.genres + "Anime")
            .filter(String::isNotBlank)
            .distinct(),
        isJapaneseAnime = true,
        aniListId = anime.aniListId,
    )

    suspend fun details(item: Media): Pair<Media, List<Media>> = supervisorScope {
        val catalogued = catalogue.firstOrNull { it.key == item.key }
        val current = when {
            item.aniListId != null -> (catalogued ?: item).copy(
                isJapaneseAnime = true,
                aniListId = item.aniListId,
            )
            catalogued != null -> catalogued.copy(
                isJapaneseAnime = false,
                aniListId = null,
            )
            else -> item.copy(isJapaneseAnime = false, aniListId = null)
        }
        val pageRequest = async {
            runCatching {
                pageLoader(
                    "$TMDB_SITE_URL/${item.type.routeName}/${item.id}?language=en-US",
                )
            }.getOrNull()
        }
        val ratingsRequest = async {
            ratingsFor(current)
        }
        val pageHtml = pageRequest.await()
        val metadata = pageHtml?.let {
            runCatching { parseTitleDetails(it, current) }.getOrDefault(current)
        } ?: current
        val pageRecommendations = pageHtml?.let {
            runCatching { parseRelatedResults(it) }.getOrDefault(emptyList())
        }.orEmpty()
        val ratings = ratingsRequest.await()
        val enriched = metadata.copy(
            imdbRating = ratings.imdb ?: metadata.imdbRating,
            rottenTomatoesRating = ratings.rottenTomatoes
                ?: metadata.rottenTomatoesRating,
        )
        catalogue = (listOf(enriched) + catalogue.filterNot { it.key == enriched.key })

        val sameType = catalogue.filter { it.type == item.type && it.key != item.key }
        val locallyRelated = RelatedContentEngine.rank(enriched, sameType)
        val recommendations = (pageRecommendations + locallyRelated)
            .filter { it.type == item.type && it.key != item.key }
            .distinctBy(Media::key)
            .take(18)
        enriched to recommendations
    }

    suspend fun seasons(item: Media): List<Season> {
        if (item.type != MediaType.TV) return emptyList()
        return runCatching {
            parseSeasons(
                html = pageLoader("$TMDB_SITE_URL/tv/${item.id}/seasons?language=en-US"),
                mediaId = item.id,
            )
        }.getOrDefault(emptyList())
    }

    suspend fun episodes(item: Media, seasonNumber: Int): List<Episode> {
        if (item.type != MediaType.TV) return emptyList()
        return runCatching {
            parseEpisodes(
                html = pageLoader(
                    "$TMDB_SITE_URL/tv/${item.id}/season/$seasonNumber?language=en-US",
                ),
                mediaId = item.id,
                seasonNumber = seasonNumber,
            )
        }.getOrDefault(emptyList())
    }

    internal fun parsePage(html: String, titlePrefix: String = ""): ParsedPage {
        val document = Jsoup.parse(html, SITE_URL)
        val rails = document
            .select("section[aria-label=Carousel of shows]")
            .mapNotNull { section ->
                val title = section.selectFirst("h2")?.text()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val items = section.select("picture").mapNotNull { picture ->
                    val link = picture.selectFirst("a[href^=/movie/], a[href^=/tv/]")
                        ?: return@mapNotNull null
                    val route = link.attr("href")
                    val match = titleRoute.matchEntire(route) ?: return@mapNotNull null
                    val image = picture.selectFirst("img")
                    val mediaTitle = image?.attr("alt")?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: link.attr("aria-label").trim()
                    if (mediaTitle.isBlank()) return@mapNotNull null
                    Media(
                        id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
                        type = MediaType.from(match.groupValues[1]),
                        title = mediaTitle,
                        posterPath = image?.attr("src")?.takeIf(String::isNotBlank),
                    )
                }.distinctBy(Media::key)
                ContentRail("$titlePrefix$title", items)
            }

        val allItems = rails.flatMap(ContentRail::items)
        return ParsedPage(
            hero = parseHero(document, allItems),
            rails = rails,
        )
    }

    private fun parseHero(document: Document, items: List<Media>): Media? {
        val section = document.selectFirst("section[aria-label=Hero]") ?: return null
        val route = section.selectFirst("a[href^=/watch/movie/], a[href^=/watch/tv/]")
            ?.attr("href")
            ?: return null
        val match = watchRoute.matchEntire(route) ?: return null
        val id = match.groupValues[2].toIntOrNull() ?: return null
        val type = MediaType.from(match.groupValues[1])
        val existing = items.firstOrNull { it.id == id && it.type == type }
        val title = section.selectFirst("h1")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: existing?.title
            ?: return null
        val backdrop = section.selectFirst("img[src]")?.attr("src")?.takeIf(String::isNotBlank)
        val paragraphs = section.select("p").map { it.text().trim() }
        return (existing ?: Media(id = id, type = type, title = title)).copy(
            title = title,
            overview = paragraphs.firstOrNull { it.length > 45 }.orEmpty(),
            backdropPath = backdrop ?: existing?.posterPath,
            year = paragraphs.firstOrNull { yearText.matches(it) }?.take(4).orEmpty(),
            rating = paragraphs.firstNotNullOfOrNull {
                matchPercent.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            }?.div(10.0) ?: existing?.rating ?: 0.0,
        )
    }

    internal fun parseSearchResults(html: String): List<Media> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        return document.select("main div[data-object-id]").mapNotNull { card ->
            val titleLink = card.selectFirst("a[data-media-type][href] h2")?.parent()
                ?: return@mapNotNull null
            val match = tmdbTitleRoute.find(titleLink.attr("href")) ?: return@mapNotNull null
            val title = titleLink.selectFirst("h2")?.text()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val poster = card.selectFirst("img.poster, img[alt=\"$title\"]")
                ?.attr("src")
                ?.takeIf(String::isNotBlank)
            Media(
                id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
                type = MediaType.from(match.groupValues[1]),
                title = title,
                overview = card.selectFirst("p")?.text()?.trim().orEmpty(),
                posterPath = normalizeTmdbImage(poster),
                backdropPath = normalizeTmdbImage(poster),
                year = fourDigitYear.find(
                    card.selectFirst(".release_date")?.text().orEmpty(),
                )?.value.orEmpty(),
            )
        }.distinctBy(Media::key)
    }

    internal fun parseRelatedResults(html: String): List<Media> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        return document.select(
            "section.recommendations div.item, " +
                "section.recommendations div.card, " +
                "#recommendation_waypoint div.item, " +
                "div.recommendations div.item",
        ).mapNotNull { card ->
            val link = card.select("a[href^=/movie/], a[href^=/tv/]")
                .firstOrNull { tmdbTitleRoute.find(it.attr("href")) != null }
                ?: return@mapNotNull null
            val match = tmdbTitleRoute.find(link.attr("href")) ?: return@mapNotNull null
            val image = card.selectFirst("img")
            val title = sequenceOf(
                link.attr("title"),
                image?.attr("alt")?.removePrefix("Poster for "),
                card.selectFirst(".title, .name, h2, h3, p.title")?.text(),
                link.text(),
            ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank)
                ?: return@mapNotNull null
            val artwork = sequenceOf(
                image?.attr("src"),
                image?.attr("data-src"),
                image?.attr("data-original"),
            ).filterNotNull().firstOrNull(String::isNotBlank)
            Media(
                id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
                type = MediaType.from(match.groupValues[1]),
                title = title,
                posterPath = normalizeTmdbImage(artwork),
                backdropPath = normalizeTmdbImage(artwork),
                year = fourDigitYear.find(card.text())?.value.orEmpty(),
            )
        }.distinctBy(Media::key)
    }

    internal fun parseTitleDetails(
        html: String,
        fallback: Media,
    ): Media {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val exactTitleRoute = Regex(
            "^/${fallback.type.routeName}/${fallback.id}(?:-[^/?]+)?(?:\\?.*)?$",
        )
        val title = document
            .select("main h2 a[href], section.header h2 a[href]")
            .firstOrNull { exactTitleRoute.matches(it.attr("href")) }
            ?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: fallback.title
        val overview = document
            .selectFirst("main .overview p, main div.overview, section.header_info .overview")
            ?.text()
            ?.trim()
            ?.takeIf { it.length > 20 }
            ?: fallback.overview
        val poster = document
            .selectFirst("main img.poster, section.header img.poster")
            ?.attr("src")
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizeTmdbImage)
            ?: fallback.posterPath
        val socialImage = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizeTmdbImage)
        val year = document
            .selectFirst("main h2 .release_date, section.header h2 .release_date")
            ?.text()
            ?.let { fourDigitYear.find(it)?.value }
            ?: fallback.year
        val score = document
            .selectFirst("[data-percent].user_score_chart, .user_score_chart[data-percent]")
            ?.attr("data-percent")
            ?.toDoubleOrNull()
            ?.div(10.0)
            ?: fallback.rating
        val genres = document
            .select(
                "main section.header a[href^=/genre/], " +
                    "main section.header_info a[href^=/genre/], " +
                    "main div.header_info a[href^=/genre/]",
            )
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .distinct()
            .take(6)
            .ifEmpty { fallback.genres }
        val cast = document
            .select("main ol.people li.card p a[href^=/person/]")
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .distinct()
            .take(10)
            .ifEmpty { fallback.cast }
        return fallback.copy(
            title = title,
            overview = overview,
            posterPath = poster,
            backdropPath = socialImage ?: fallback.backdropPath ?: poster,
            year = year,
            rating = score,
            genres = genres,
            cast = cast,
            isJapaneseAnime = fallback.aniListId != null,
        )
    }

    internal fun parseSeasons(html: String, mediaId: Int): List<Season> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val routePattern = Regex(
            "^/tv/$mediaId(?:-[^/]*)?/season/(\\d+)(?:\\?.*)?$",
        )
        return document.select("main a[href] img").mapNotNull { image ->
            val link = image.parents().firstOrNull { it.tagName() == "a" }
                ?: return@mapNotNull null
            val match = routePattern.matchEntire(link.attr("href")) ?: return@mapNotNull null
            val number = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val container = link.parents().firstOrNull { parent ->
                parent.selectFirst("h4") != null && episodeCount.find(parent.text()) != null
            }
            Season(
                number = number,
                title = image.attr("alt").trim().ifBlank { "Season $number" },
                episodeCount = container?.text()?.let {
                    episodeCount.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
                } ?: 0,
                posterPath = normalizeTmdbImage(image.attr("src")),
            )
        }.distinctBy(Season::number).sortedBy(Season::number)
    }

    internal fun parseEpisodes(
        html: String,
        mediaId: Int,
        seasonNumber: Int,
    ): List<Episode> {
        val document = Jsoup.parse(html, TMDB_SITE_URL)
        val routePattern =
            Regex(
                "^/tv/$mediaId(?:-[^/]*)?/season/$seasonNumber/episode/(\\d+)(?:\\?.*)?$",
            )
        return document.select("main div.episode").mapNotNull { container ->
            val link = container.selectFirst(
                "a[data-episode-number][href], a[href*=\"/episode/\"]",
            ) ?: return@mapNotNull null
            val match = routePattern.matchEntire(link.attr("href")) ?: return@mapNotNull null
            val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val image = container.selectFirst("img.backdrop, img[src]")
            val title = container
                .selectFirst("h3 a, .title a, a[data-episode-number]")
                ?.text()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: image?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
                ?: "Episode $number"
            val overview = container
                .selectFirst(".overview p, div.overview, .info p")
                ?.text()
                ?.trim()
                ?.takeUnless { it.equals("Expand", ignoreCase = true) }
                ?: container.select("p")
                    .map { it.text().trim() }
                    .firstOrNull { it.length > 25 }
                    .orEmpty()
            Episode(
                seasonNumber = seasonNumber,
                number = number,
                title = title,
                overview = overview.replace(
                    Regex("\\s*(?:Read More|Expand)\\s*$", RegexOption.IGNORE_CASE),
                    "",
                ),
                stillPath = normalizeTmdbImage(image?.attr("src")),
                runtime = container.selectFirst(".runtime")?.text()?.trim().orEmpty(),
            )
        }.distinctBy(Episode::number).sortedBy(Episode::number)
    }

    private suspend fun ratingsFor(item: Media): ExternalRatings {
        val cachedImdb = imdbRatingsCache[item.key]
        val cachedRottenTomatoes = rottenTomatoesRatingsCache[item.key]
        if (cachedImdb != null && cachedRottenTomatoes != null) {
            return ExternalRatings(cachedImdb, cachedRottenTomatoes)
        }
        val ratings = supervisorScope {
            val imdb = async {
                cachedImdb ?: runCatching { loadImdbRating(item) }.getOrNull()
            }
            val rottenTomatoes = async {
                cachedRottenTomatoes
                    ?: runCatching { loadRottenTomatoesRating(item) }.getOrNull()
            }
            ExternalRatings(
                imdb = imdb.await(),
                rottenTomatoes = rottenTomatoes.await(),
            )
        }
        ratings.imdb?.let { imdbRatingsCache[item.key] = it }
        ratings.rottenTomatoes?.let { rottenTomatoesRatingsCache[item.key] = it }
        return ratings
    }

    private suspend fun loadImdbRating(item: Media): Double? {
        val query = URLEncoder.encode(item.title, StandardCharsets.UTF_8.toString())
        val suggestion = JSONObject(
            pageLoader("https://v3.sg.media-imdb.com/suggestion/x/$query.json"),
        )
        val candidates = suggestion.optJSONArray("d") ?: return null
        val wantedTitle = normalizeText(item.title)
        val wantedYear = item.year.take(4).toIntOrNull()
        val imdbId = (0 until candidates.length())
            .mapNotNull { index -> candidates.optJSONObject(index) }
            .filter { it.optString("id").startsWith("tt") }
            .maxByOrNull { candidate ->
                var confidence = 0
                val candidateTitle = normalizeText(candidate.optString("l"))
                if (candidateTitle == wantedTitle) confidence += 100
                if (
                    candidateTitle.contains(wantedTitle) ||
                    wantedTitle.contains(candidateTitle)
                ) {
                    confidence += 25
                }
                if (wantedYear != null && candidate.optInt("y") == wantedYear) confidence += 20
                val qualifier = candidate.optString("q").lowercase()
                if (item.type == MediaType.TV && "tv" in qualifier) confidence += 10
                if (item.type == MediaType.MOVIE && "feature" in qualifier) confidence += 10
                confidence
            }
            ?.optString("id")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val queryBody = JSONObject()
            .put(
                "query",
                "query { title(id: \"$imdbId\") { ratingsSummary { " +
                    "aggregateRating voteCount } } }",
            )
            .toString()
        val graph = JSONObject(
            jsonPoster("https://api.graphql.imdb.com/", queryBody),
        )
        val rating = graph
            .optJSONObject("data")
            ?.optJSONObject("title")
            ?.optJSONObject("ratingsSummary")
            ?.optDouble("aggregateRating")
        if (rating != null && rating in 0.1..10.0) return rating
        return runCatching {
            parseImdbRating(pageLoader("https://www.imdb.com/title/$imdbId/"))
        }.getOrNull()
    }

    internal fun parseImdbRating(html: String): Double? {
        val document = Jsoup.parse(html, "https://www.imdb.com")
        document.select("script[type=application/ld+json]").forEach { script ->
            val rating = runCatching {
                JSONObject(script.data())
                    .optJSONObject("aggregateRating")
                    ?.optDouble("ratingValue")
            }.getOrNull()
            if (rating != null && rating in 0.1..10.0) return rating
        }
        return imdbRatingPattern.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.1..10.0 }
    }

    private suspend fun loadRottenTomatoesRating(item: Media): Int? {
        val slug = rottenTomatoesSlug(item.title)
        val expectedPrefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val year = item.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
        val directPaths = buildList {
            add("$expectedPrefix$slug")
            year?.let { add("$expectedPrefix${slug}_$it") }
        }.distinct()
        directPaths.forEach { path ->
            val direct = runCatching {
                pageLoader("$ROTTEN_TOMATOES_URL$path")
            }.getOrNull()
            parseRottenTomatoesRating(direct.orEmpty())?.let { return it }
        }

        val query = URLEncoder.encode(item.title, StandardCharsets.UTF_8.toString())
        val searchHtml = pageLoader("$ROTTEN_TOMATOES_URL/search?search=$query")
        val candidatePaths = parseRottenTomatoesCandidatePaths(searchHtml, item)
            .filterNot(directPaths::contains)
            .take(5)
        candidatePaths.forEach { path ->
            val page = runCatching {
                pageLoader("$ROTTEN_TOMATOES_URL$path")
            }.getOrNull()
            parseRottenTomatoesRating(page.orEmpty())?.let { return it }
        }
        return null
    }

    internal fun parseRottenTomatoesRating(html: String): Int? {
        if (html.isBlank()) return null
        val document = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        val scoreBoard = document.selectFirst("score-board")
        val attributeScore = sequenceOf(
            scoreBoard?.attr("tomatometerscore"),
            scoreBoard?.attr("tomatometerScore"),
            document.selectFirst(
                "media-scorecard rt-text[slot=criticsScore], " +
                    "rt-text[slot=criticsScore]",
            )?.text(),
            document.selectFirst(
                "[data-qa=tomatometer], [data-qa=score-panel-critics-score]",
            )?.text(),
        ).filterNotNull().mapNotNull { scoreText.find(it)?.value?.toIntOrNull() }.firstOrNull()
        if (attributeScore != null && attributeScore in 0..100) return attributeScore
        val visibleScore = visibleTomatometerPattern.find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (visibleScore != null && visibleScore in 0..100) return visibleScore
        return rottenTomatoesPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it in 0..100 }
        }
    }

    internal fun parseRottenTomatoesCandidatePaths(
        html: String,
        item: Media,
    ): List<String> {
        if (html.isBlank()) return emptyList()
        val expectedPrefix = if (item.type == MediaType.MOVIE) "/m/" else "/tv/"
        val wantedTitle = normalizeText(item.title)
        val wantedSlug = rottenTomatoesSlug(item.title)
        val wantedTokens = wantedTitle.split(' ').filter(String::isNotBlank).toSet()
        val wantedYear = item.year.take(4).takeIf { it.matches(Regex("\\d{4}")) }
        val scores = linkedMapOf<String, Int>()

        fun addCandidate(rawPath: String, context: String) {
            val path = rawPath.substringBefore('?').substringBefore('#').trimEnd('/')
            if (!path.startsWith(expectedPrefix)) return
            val slug = path.substringAfter(expectedPrefix).substringBefore('/')
            if (slug.isBlank()) return
            val normalizedSlug = normalizeText(slug.replace('_', ' '))
            val slugTokens = normalizedSlug.split(' ').filter(String::isNotBlank).toSet()
            val normalizedContext = normalizeText(context)
            var score = 0
            if (slug == wantedSlug) score += 180
            if (normalizedSlug == wantedTitle) score += 160
            if (
                normalizedSlug.contains(wantedTitle) ||
                wantedTitle.contains(normalizedSlug)
            ) {
                score += 70
            }
            score += (slugTokens intersect wantedTokens).size * 18
            if (normalizedContext.contains(wantedTitle)) score += 110
            if (wantedYear != null && (wantedYear in path || wantedYear in context)) score += 24
            scores[path] = maxOf(scores[path] ?: Int.MIN_VALUE, score)
        }

        val document = Jsoup.parse(html, ROTTEN_TOMATOES_URL)
        document.select("a[href^=\"$expectedPrefix\"]").forEach { link ->
            addCandidate(link.attr("href"), link.parent()?.text().orEmpty() + " " + link.text())
        }
        val unescaped = html
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
        rottenTomatoesPathPattern.findAll(unescaped).forEach { match ->
            addCandidate(match.value, "")
        }
        return scores.entries
            .sortedByDescending(Map.Entry<String, Int>::value)
            .map(Map.Entry<String, Int>::key)
    }

    private fun rottenTomatoesSlug(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("['’]"), "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return normalized
    }

    private fun normalizeText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun localSearch(query: String): List<Media> {
        val terms = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return catalogue.filter { item ->
            val searchable =
                "${item.title} ${item.year} ${item.genres.joinToString(" ")}".lowercase()
            terms.all(searchable::contains)
        }
    }

    data class ParsedPage(
        val hero: Media?,
        val rails: List<ContentRail>,
    )

    private data class ExternalRatings(
        val imdb: Double?,
        val rottenTomatoes: Int?,
    )

    private data class TmdbHomeRailSpec(
        val path: String,
        val title: String,
    )

    private companion object {
        const val SITE_URL = "https://ramoflix.net"
        const val TMDB_SITE_URL = "https://www.themoviedb.org"
        const val ROTTEN_TOMATOES_URL = "https://www.rottentomatoes.com"
        const val HOME_RAIL_LIMIT = 20
        const val ANIME_HOME_LIMIT = 12
        const val ANIME_HOME_EXPANDED_LOOKUPS = 4
        const val ANIME_SEARCH_LIMIT = 18
        const val ANIME_EXPANDED_LOOKUPS = 6
        const val ANIME_LOOKUP_CONCURRENCY = 4
        const val MAX_SEASON_YEAR_DELTA = 10
        const val MIN_ANIME_MATCH_SCORE = 160
        const val MIN_ANIME_MATCH_MARGIN = 12
        const val REJECTED_ANIME_MATCH_SCORE = -1_000_000
        val TV_ANIME_FORMATS = setOf("TV", "TV_SHORT")
        val SEASONAL_TITLE_MARKER = Regex(
            """\b(?:season|part)\s*(?:\d+|[ivx]+)\b""",
            RegexOption.IGNORE_CASE,
        )
        val titleRoute = Regex("^/(movie|tv)/.*-(\\d+)$")
        val watchRoute = Regex("^/watch/(movie|tv)/(\\d+)$")
        val tmdbTitleRoute = Regex("^/(movie|tv)/(\\d+)(?:-|$)")
        val yearText = Regex("^\\d{4}(?:-\\d{2}-\\d{2})?$")
        val fourDigitYear = Regex("\\b(?:19|20)\\d{2}\\b")
        val matchPercent = Regex("(\\d{1,3})%\\s*Match", RegexOption.IGNORE_CASE)
        val episodeCount = Regex("(\\d+)\\s+Episodes?", RegexOption.IGNORE_CASE)
        val scoreText = Regex("\\d{1,3}")
        val visibleTomatometerPattern = Regex(
            """(\d{1,3})%\s*(?:Avg\.\s*)?Tomatometer""",
            RegexOption.IGNORE_CASE,
        )
        val rottenTomatoesPathPattern = Regex(
            """/(?:m|tv)/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*""",
        )
        val imdbRatingPattern = Regex(
            """"ratingValue"\s*:\s*"?([0-9]+(?:\.[0-9]+)?)""",
            RegexOption.IGNORE_CASE,
        )
        val rottenTomatoesPatterns = listOf(
            Regex(
                """tomatometerscore\s*=\s*["']?(\d{1,3})""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """"criticsScore"\s*:\s*"?(\d{1,3})""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """"criticsScore"\s*:\s*\{[^{}]*"score"\s*:\s*"(\d{1,3})"""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ),
            Regex(
                """"scorePercent"\s*:\s*"(\d{1,3})%"""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """"tomatometerScore"\s*:\s*"?(\d{1,3})""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """slot\s*=\s*["']criticsScore["'][^>]*>\s*(\d{1,3})""",
                RegexOption.IGNORE_CASE,
            ),
        )

        val tmdbHomeRailSpecs = listOf(
            TmdbHomeRailSpec("/movie", "Trending Movies"),
            TmdbHomeRailSpec("/tv", "Trending Series"),
            TmdbHomeRailSpec("/movie/now-playing", "Now in Cinemas"),
            TmdbHomeRailSpec("/tv/on-the-air", "Series Airing Now"),
            TmdbHomeRailSpec("/movie/top-rated", "All-Time Movie Greats"),
            TmdbHomeRailSpec("/tv/top-rated", "Binge-Worthy Series"),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=28&sort_by=popularity.desc",
                "Action Hits",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=35&sort_by=popularity.desc",
                "Comedy Picks",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=878&sort_by=popularity.desc",
                "Science Fiction",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=53&sort_by=popularity.desc",
                "Edge-of-Your-Seat Thrillers",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=27&sort_by=popularity.desc",
                "Horror After Dark",
            ),
            TmdbHomeRailSpec(
                "/discover/movie?with_genres=10749&sort_by=popularity.desc",
                "Romance",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=80&sort_by=popularity.desc",
                "Crime Series",
            ),
            TmdbHomeRailSpec(
                "/discover/tv?with_genres=10765&sort_by=popularity.desc",
                "Fantasy & Sci-Fi Worlds",
            ),
        )

        fun normalizeTmdbImage(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val filename = value.substringAfterLast('/').substringBefore('?')
            return if (
                filename.isNotBlank() &&
                (value.contains("themoviedb.org/t/p/") || value.contains("tmdb.org/t/p/"))
            ) {
                "https://image.tmdb.org/t/p/w500/$filename"
            } else {
                value
            }
        }

        val fallbackItems = listOf(
            Media(
                id = 27205,
                type = MediaType.MOVIE,
                title = "Inception",
                overview = "A skilled extractor enters dreams to steal secrets and is offered a chance to erase his past.",
                posterPath = "/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg",
                backdropPath = "/s3TBrRGB1iav7gFOCNx3H31MoES.jpg",
                year = "2010",
                rating = 8.4,
                genres = listOf("Science Fiction", "Action", "Thriller"),
            ),
            Media(
                id = 496243,
                type = MediaType.MOVIE,
                title = "Parasite",
                posterPath = "/7IiTTgloJzvGI1TAYymCfbfl3vT.jpg",
                backdropPath = "/TU9NIjwzjoKPwQHoHshkFcQUCG.jpg",
                year = "2019",
                rating = 8.5,
                genres = listOf("Thriller", "Drama"),
            ),
            Media(
                id = 155,
                type = MediaType.MOVIE,
                title = "The Dark Knight",
                posterPath = "/qJ2tW6WMUDux911r6m7haRef0WH.jpg",
                backdropPath = "/hqkIcbrOHL86UncnHIsHVcVmzue.jpg",
                year = "2008",
                rating = 8.5,
                genres = listOf("Action", "Crime", "Drama"),
            ),
            Media(
                id = 454639,
                type = MediaType.MOVIE,
                title = "Masters of the Universe",
                posterPath = "https://image.tmdb.org/t/p/w500/oRuyGUHdoaQxWP3SDfafGkStxTC.jpg",
            ),
            Media(
                id = 1275779,
                type = MediaType.MOVIE,
                title = "Disclosure Day",
                posterPath = "https://image.tmdb.org/t/p/w500/AnJ8IQJI23hNpYXVNaythu061Ru.jpg",
            ),
            Media(
                id = 1368337,
                type = MediaType.MOVIE,
                title = "The Odyssey",
                posterPath = "https://image.tmdb.org/t/p/w500/5rhTDKUhPYvpdQIijFIs5VoWsON.jpg",
            ),
            Media(
                id = 1396,
                type = MediaType.TV,
                title = "Breaking Bad",
                posterPath = "/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg",
                backdropPath = "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg",
                year = "2008",
                rating = 8.9,
                genres = listOf("Drama", "Crime"),
            ),
            Media(
                id = 66732,
                type = MediaType.TV,
                title = "Stranger Things",
                posterPath = "/uOOtwVbSr4QDjAGIifLDwpb2Pdl.jpg",
                backdropPath = "/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
                year = "2016",
                rating = 8.6,
                genres = listOf("Drama", "Mystery", "Sci-Fi"),
            ),
            Media(
                id = 94605,
                type = MediaType.TV,
                title = "Arcane",
                posterPath = "/fqldf2t8ztc9aiwn3k6mlX3tvRT.jpg",
                backdropPath = "/rkB4LyZHo1NHXFEDHl9vSD9r1lI.jpg",
                year = "2021",
                rating = 8.8,
                genres = listOf("Animation", "Drama", "Action"),
            ),
            Media(
                id = 1429,
                type = MediaType.TV,
                title = "Attack on Titan",
                posterPath = "https://image.tmdb.org/t/p/w500/hTP1DtLGFamjfu8WqjnuQdP1n4i.jpg",
                genres = listOf("Anime", "Action", "Drama"),
                isJapaneseAnime = true,
                aniListId = 16498,
            ),
            Media(
                id = 85937,
                type = MediaType.TV,
                title = "Demon Slayer: Kimetsu no Yaiba",
                posterPath = "https://image.tmdb.org/t/p/w500/xUfRZu2mi8jH6SzQEJGP6tjBuYj.jpg",
                genres = listOf("Anime", "Action"),
                isJapaneseAnime = true,
                aniListId = 101922,
            ),
            Media(
                id = 37854,
                type = MediaType.TV,
                title = "One Piece",
                posterPath = "https://image.tmdb.org/t/p/w500/dB4EDhre2dsC2kxYDavyKWqLQwi.jpg",
                genres = listOf("Anime", "Adventure"),
                isJapaneseAnime = true,
                aniListId = 21,
            ),
            Media(
                id = 209867,
                type = MediaType.TV,
                title = "Frieren: Beyond Journey's End",
                posterPath = "https://image.tmdb.org/t/p/w500/dqZENchTd7lp5zht7BdlqM7RBhD.jpg",
                genres = listOf("Anime", "Fantasy", "Adventure"),
                isJapaneseAnime = true,
                aniListId = 154587,
            ),
        )

        val fallbackRails = listOf(
            ContentRail("Trending Now", fallbackItems.filterNot(Media::isJapaneseAnime)),
            ContentRail(
                "Popular Movies",
                fallbackItems.filter {
                    it.type == MediaType.MOVIE && !it.isJapaneseAnime
                },
            ),
            ContentRail(
                "Popular TV Shows",
                fallbackItems.filter {
                    it.type == MediaType.TV && !it.isJapaneseAnime
                },
            ),
            ContentRail(
                JAPANESE_ANIME_RAIL_TITLE,
                fallbackItems.filter(Media::isJapaneseAnime),
                kind = ContentRailKind.ANIME,
            ),
        )

        const val JAPANESE_ANIME_RAIL_TITLE = "Japanese Anime · Miruro"
        val fallbackAnimeRail = fallbackRails.first { rail ->
            rail.kind == ContentRailKind.ANIME
        }

        suspend fun downloadPage(url: String): String = withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 18_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126 Mobile Safari/537.36",
                )
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw IOException("Catalogue request failed ($status)")
                if (body.isBlank()) throw IOException("Catalogue response was empty")
                body
            } finally {
                connection.disconnect()
            }
        }

        suspend fun postJson(url: String, body: String): String = withContext(Dispatchers.IO) {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 18_000
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Aliflix/1.5 (personal Android app)")
            }
            try {
                connection.outputStream.use { it.write(payload) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw IOException("Metadata request failed ($status)")
                if (response.isBlank()) throw IOException("Metadata response was empty")
                response
            } finally {
                connection.disconnect()
            }
        }
    }
}
