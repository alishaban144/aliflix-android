package com.aliflix.app.data

import android.util.Log
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** One catalogue record returned by the Rotten Tomatoes search index. */
internal data class RottenTomatoesSearchHit(
    val path: String,
    val title: String,
    val year: Int?,
    val type: MediaType,
    val criticsScore: Int?,
)

/**
 * Resolves a title to the canonical Rotten Tomatoes page.
 *
 * Rotten Tomatoes replaced its server-rendered search results with a client-rendered one, so the
 * `/search` HTML no longer contains any `/m/` or `/tv/` links for a query. The site itself
 * resolves titles through the Algolia index whose credentials it publishes in every page, and that
 * index carries the canonical `vanity` slug, the release year and the critic score. This is the
 * same lookup the website performs, which is why it resolves vanity slugs that cannot be derived
 * from the title at all, such as `the_avengers` to `marvels_the_avengers`.
 *
 * Discovery stays strictly advisory. The returned path is still fetched and verified by
 * [RottenTomatoesClient], so an unavailable or rotated index only costs a fallback, never identity.
 */
internal class RottenTomatoesSearchIndex(
    private val transport: RottenTomatoesSearchIndexTransport = AndroidRottenTomatoesSearchIndexTransport(),
) {
    private val credentialLock = Mutex()

    @Volatile
    private var cachedCredentials: Credentials? = null

    suspend fun resolve(item: Media): List<RottenTomatoesSearchHit> {
        if (item.title.isBlank()) return emptyList()
        val credentials = credentials()
        val body = queryBody(item.title, item.type)
        val response = try {
            transport.query(credentials, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("search index lookup failed for ${item.key}: ${error.message}")
            return emptyList()
        }
        if (response.isBlank()) {
            log("search index returned an empty body for ${item.key}")
            return emptyList()
        }
        return parseHits(response, item.type)
    }

    /**
     * Reads the published search credentials from Rotten Tomatoes itself so a rotated key keeps
     * working, and only falls back to the bundled values when the page cannot be read.
     */
    private suspend fun credentials(): Credentials {
        cachedCredentials?.let { return it }
        return credentialLock.withLock {
            cachedCredentials?.let { return@withLock it }
            val discovered = try {
                transport.discoverCredentials(RottenTomatoesSearchCredentials.BUNDLED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            val resolved = discovered ?: RottenTomatoesSearchCredentials.BUNDLED
            cachedCredentials = resolved
            resolved
        }
    }

    internal fun queryBody(title: String, type: MediaType): String = JSONObject()
        .put("query", title)
        .put("hitsPerPage", SEARCH_HIT_LIMIT)
        .put("filters", "typeId = ${if (type == MediaType.MOVIE) 1 else 2}")
        .toString()

    internal fun parseHits(body: String, type: MediaType): List<RottenTomatoesSearchHit> {
        val hits = runCatching { JSONObject(body).optJSONArray("hits") }.getOrNull() ?: return emptyList()
        val typeId = if (type == MediaType.MOVIE) 1 else 2
        val prefix = if (type == MediaType.MOVIE) "/m/" else "/tv/"
        val collected = LinkedHashMap<String, RottenTomatoesSearchHit>()
        for (index in 0 until hits.length()) {
            val hit = hits.optJSONObject(index) ?: continue
            if (hit.optInt("typeId", typeId) != typeId) continue
            val vanity = hit.optString("vanity").trim()
            if (vanity.isBlank() || !vanity.matches(SLUG_PATTERN)) continue
            val path = prefix + vanity
            if (collected.containsKey(path)) continue
            collected[path] = RottenTomatoesSearchHit(
                path = path,
                title = hit.optString("title").trim(),
                year = hit.optYear("releaseYear"),
                type = type,
                criticsScore = hit.optJSONObject("rottenTomatoes")
                    ?.opt("criticsScore")
                    ?.toIntOrNull()
                    ?.takeIf { it in 0..100 },
            )
        }
        return collected.values.toList()
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d("AliflixRT", message)
    }

    private fun JSONObject.optYear(key: String): Int? =
        opt(key)?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }?.takeIf { it > 0 }

    private fun Any?.toIntOrNull(): Int? =
        (this as? Number)?.toInt() ?: this?.toString()?.trim()?.toIntOrNull()

    internal data class Credentials(
        val applicationId: String,
        val searchKey: String,
        val host: String,
    )

    internal companion object {
        const val SEARCH_HIT_LIMIT = 12
        val SLUG_PATTERN = Regex("^[A-Za-z0-9_.-]+$")

        /**
         * An index that never reaches the network. Used by the HTML parser test constructor so a
         * unit test can only ever exercise parsing, never a live lookup.
         */
        fun disabled(): RottenTomatoesSearchIndex = RottenTomatoesSearchIndex(
            object : RottenTomatoesSearchIndexTransport {
                override suspend fun query(
                    credentials: Credentials,
                    body: String,
                ): String = ""

                override suspend fun discoverCredentials(
                    fallback: Credentials,
                ): Credentials? = null
            },
        )
    }
}


internal object RottenTomatoesSearchCredentials {
    /**
     * The search-only key Rotten Tomatoes publishes inside its own pages for the index the website
     * queries. It is read-only, scoped to search, and refreshed from the site at runtime whenever
     * the page can be reached.
     */
    val BUNDLED = RottenTomatoesSearchIndex.Credentials(
        applicationId = "79FRDP12PN",
        searchKey = "175588f6e5f8319b27702e4cc4013561",
        host = "79frdp12pn-dsn.algolia.net",
    )
}

internal interface RottenTomatoesSearchIndexTransport {
    /** Returns the JSON body of the search request, or an empty string when it cannot be served. */
    suspend fun query(credentials: RottenTomatoesSearchIndex.Credentials, body: String): String

    /** Reads the credentials the site currently publishes, or null when the page is unreachable. */
    suspend fun discoverCredentials(
        fallback: RottenTomatoesSearchIndex.Credentials,
    ): RottenTomatoesSearchIndex.Credentials?
}

internal class AndroidRottenTomatoesSearchIndexTransport : RottenTomatoesSearchIndexTransport {
    override suspend fun query(
        credentials: RottenTomatoesSearchIndex.Credentials,
        body: String,
    ): String = runInterruptible(Dispatchers.IO) {
        request(
            url = "https://${credentials.host}/1/indexes/${RottenTomatoesClient.INDEX_NAME}/query",
            method = "POST",
            body = body,
            headers = mapOf(
                "x-algolia-application-id" to credentials.applicationId,
                "x-algolia-api-key" to credentials.searchKey,
                "Content-Type" to "application/json",
            ),
        )
    }

    override suspend fun discoverCredentials(
        fallback: RottenTomatoesSearchIndex.Credentials,
    ): RottenTomatoesSearchIndex.Credentials? = runInterruptible(Dispatchers.IO) {
        val page = request(
            url = "${RottenTomatoesClient.ROTTEN_TOMATOES_URL}/search",
            method = "GET",
            body = null,
            headers = mapOf("Accept" to HTML_ACCEPT),
        )
        if (page.isBlank()) return@runInterruptible null
        val match = CREDENTIALS_PATTERN.find(page) ?: return@runInterruptible null
        val applicationId = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val searchKey = match.groupValues.getOrNull(2)?.trim().orEmpty()
        if (applicationId.isBlank() || searchKey.isBlank()) null
        else RottenTomatoesSearchIndex.Credentials(
            applicationId = applicationId,
            searchKey = searchKey,
            host = "${applicationId.lowercase()}-dsn.algolia.net",
        )
    }

    private fun request(url: String, method: String, body: String?, headers: Map<String, String>): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) return ""
            val stream = connection.inputStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        } catch (_: Throwable) {
            ""
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val REQUEST_TIMEOUT_MS = 4_000
        val CREDENTIALS_PATTERN =
            Regex(""""algoliaSearch"\s*:\s*\{\s*"aId"\s*:\s*"([^"]+)"\s*,\s*"sId"\s*:\s*"([^"]+)"""")
    }
}
