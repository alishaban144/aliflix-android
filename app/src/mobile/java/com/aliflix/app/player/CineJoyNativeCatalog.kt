package com.aliflix.app.player

import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * CineJoy serves every title through its own encrypted catalogue API instead of a
 * server-rendered watch page. The site at cinejoy.pk boots its bundle, lists its
 * servers from api.wing.st/servers, then for the chosen server encrypts a catalogue
 * URL via enc-cinejoy, posts the decoded bytes to api.wing.st/g, and decrypts the
 * reply via dec-cinejoy. The reply carries a multivariant HLS master that retains
 * every alternate audio rendition (for Dark S1E1: Track 1-4, including German).
 *
 * The previous WebView-only path loaded a single watch route and kept whichever
 * variant the embedded player fetched first. A per-title server can be missing
 * (Dark S1E1 404s on Nebula/Athens and errors on Scout/Riga) while another carries
 * it (Lisbon), and a video-only variant (video_1080p.m3u8) has no audio at all, so
 * validation failed with "No playable audio" and German never appeared. Racing the
 * catalogue servers and handing off the master fixes both, for CineJoy and as the
 * reference for every other source.
 */
internal object CineJoyNativeCatalog {
    const val ENTRY_BASE = "https://cinejoy.pk/"
    const val API_BASE = "https://api.wing.st"
    const val ENC_ENDPOINT = "https://enc-dec.app/api/enc-cinejoy"
    const val DEC_ENDPOINT = "https://enc-dec.app/api/dec-cinejoy"
    const val REFERER = "https://cinejoy.pk/"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    /** Live order from /servers; used when the catalogue is unreachable. */
    val FALLBACK_SERVERS = listOf("Lisbon", "Nebula", "Scout", "Riga", "Solara", "Athens")

    fun serverLabel(server: String): String = "CineJoy / $server"

    fun serverFromLabel(label: String?): String? =
        label?.takeIf { it.startsWith("CineJoy / ") }?.removePrefix("CineJoy / ")?.takeIf { it.isNotBlank() }

    /** Keep the catalogue lookup stable: TMDB year, never a full release date. */
    fun catalogueYear(raw: String?): String? {
        val year = raw.orEmpty().trim().take(4)
        return year.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Mirrors the site's own lookup:
     * https://api.wing.st/?title={title}&type={movie|series}&year={year}&imdb={imdb}&tmdb={id}
     * &server={server}[&season={s}&episode={e}]
     * imdb and year are omitted when unknown; the backend resolves via TMDB.
     */
    fun innerUrl(selection: PlaybackSelection, server: String): String {
        val media = selection.media
        val type = if (media.type == MediaType.TV) "series" else "movie"
        val params = StringBuilder()
        params.append("title=").append(encode(media.title))
        params.append("&type=").append(type)
        catalogueYear(media.year)?.let { params.append("&year=").append(it) }
        media.imdbId?.takeIf { it.matches(Regex("tt\\d+")) }?.let { params.append("&imdb=").append(it) }
        params.append("&tmdb=").append(media.id)
        params.append("&server=").append(encode(server))
        if (media.type == MediaType.TV) {
            params.append("&season=").append(selection.seasonNumber ?: 1)
            params.append("&episode=").append(selection.episodeNumber ?: 1)
        }
        return "$API_BASE/?$params"
    }

    fun parseServers(payload: String): List<String> = runCatching {
        val root = JSONObject(payload)
        val array = root.optJSONArray("servers") ?: return emptyList()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
    }.getOrDefault(emptyList())

    /** Only multivariant masters are handed to Media3; video-only variants have no audio. */
    fun isHlsMasterUrl(raw: String): Boolean {
        if (!isNativeStreamUrl(raw)) return false
        val lower = raw.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8")
    }

    /**
     * The decrypted reply is {status, result:{data:{stream:[{type, playlist|url, captions}]}}}.
     * Non-playlist embeds (e.g. /content?v=...) stay on the site player; only HLS masters
     * are native-playable with every alternate audio rendition intact.
     */
    fun parsePlaylists(decrypted: JSONObject): List<String> {
        val data = decrypted.optJSONObject("result")?.optJSONObject("data") ?: return emptyList()
        val streams = data.optJSONArray("stream") ?: return emptyList()
        return (0 until streams.length()).mapNotNull { index ->
            val item = streams.optJSONObject(index) ?: return@mapNotNull null
            val type = item.optString("type").lowercase()
            if (type.isNotBlank() && type != "hls" && type != "m3u8") return@mapNotNull null
            val candidate = item.optString("playlist").takeIf { it.isNotBlank() }
                ?: item.optString("url").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            candidate.takeIf(::isHlsMasterUrl)
        }.distinct()
    }

    /** Reject child playlists: they omit the site's alternate audio menu. */
    fun audioTracksInMaster(manifest: String): List<String> {
        if (!manifest.lineSequence().firstOrNull()?.trim().orEmpty().startsWith("#EXTM3U") ||
            !manifest.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF:") }) return emptyList()
        val audioGroups = manifest.lineSequence().filter { it.startsWith("#EXT-X-STREAM-INF:") }
            .mapNotNull { Regex("""AUDIO="([^"]+)"""").find(it)?.groupValues?.get(1) }.toSet()
        return manifest.lineSequence().filter { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") }
            .mapNotNull { line ->
                val group = Regex("""GROUP-ID="([^"]+)"""").find(line)?.groupValues?.get(1)
                val name = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.get(1)
                name?.takeIf { group in audioGroups }
            }.distinct().toList()
    }

    private fun mediaChunkAvailable(url: String, userAgent: String): Boolean = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 4_000
            setRequestProperty("Range", "bytes=0-31")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Origin", REFERER.trimEnd('/'))
            setRequestProperty("Referer", REFERER)
            setRequestProperty("User-Agent", userAgent)
            instanceFollowRedirects = true
        }
        try {
            connection.responseCode in 200..299 && connection.inputStream.use { input ->
                val header = ByteArray(32)
                val count = input.read(header)
                // These CineJoy .html URLs actually contain fMP4, not HTML pages.
                count >= 8 && String(header, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "moof", "styp")
            }
        } finally { connection.disconnect() }
    }.getOrDefault(false)

    private fun renditionAvailable(url: String, positionMs: Long, userAgent: String): Boolean {
        val segment = CineJoyHlsProbe.segment(url, httpGet(url, userAgent), positionMs) ?: return false
        return (segment.init == null || mediaChunkAvailable(segment.init, userAgent)) &&
            mediaChunkAvailable(segment.media, userAgent)
    }

    private fun playableVideo(
        master: CineJoyHlsProbe.Master, positionMs: Long, preferredAudioLabel: String?,
        lowQuality: Boolean, userAgent: String,
    ): CineJoyHlsProbe.Video? {
        check(master.audio.isNotEmpty() && master.video.isNotEmpty()) {
            "CineJoy did not provide a multivariant stream with selectable audio"
        }
        val audio = master.audio.firstOrNull { it.label == preferredAudioLabel }
            ?: master.audio.firstOrNull { it.default } ?: master.audio.first()
        if (!renditionAvailable(audio.url, positionMs, userAgent)) return null
        return CineJoyHlsProbe.preferredVideos(master.video, lowQuality).firstOrNull {
            renditionAvailable(it.url, positionMs, userAgent)
        }
    }

    private fun httpGet(url: String, userAgent: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Origin", REFERER.trimEnd('/'))
            setRequestProperty("Referer", REFERER)
            setRequestProperty("User-Agent", userAgent)
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            check(code in 200..299) { "CineJoy catalogue HTTP $code" }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPostBytes(url: String, body: ByteArray, userAgent: String, json: Boolean): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Origin", REFERER.trimEnd('/'))
            setRequestProperty("Referer", REFERER)
            setRequestProperty("User-Agent", userAgent)
            if (json) setRequestProperty("Content-Type", "application/json")
            // The catalogue /g endpoint rejects an explicit octet-stream content type;
            // leave the body raw exactly like the site's own player does.
            instanceFollowRedirects = true
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            check(code in 200..299) { "CineJoy catalogue HTTP $code" }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(raw: String): ByteArray {
        var padded = raw.trim()
        val remainder = padded.length % 4
        if (remainder > 0) padded += "=".repeat(4 - remainder)
        return java.util.Base64.getUrlDecoder().decode(padded)
    }

    suspend fun fetchServers(userAgent: String = USER_AGENT): List<String> = withContext(Dispatchers.IO) {
        val live = runCatching { parseServers(httpGet("$API_BASE/servers", userAgent)) }.getOrDefault(emptyList())
        live.ifEmpty { FALLBACK_SERVERS }
    }

    private suspend fun playlistsForServer(
        selection: PlaybackSelection,
        server: String,
        userAgent: String,
    ): List<String> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val inner = innerUrl(selection, server)
        val encPayload = httpGet("$ENC_ENDPOINT?url=${encode(inner)}", userAgent)
        val enc = JSONObject(encPayload)
        check(enc.optInt("status") == 200) { enc.optString("error").takeIf { it.isNotBlank() } ?: "CineJoy encrypt failed" }
        val result = enc.getJSONObject("result")
        val data = result.getString("data")
        val state = result.getJSONObject("state")
        val raw = base64UrlDecode(data)
        val encrypted = httpPostBytes("$API_BASE/g", raw, userAgent, json = false)
        val decBody = JSONObject().put("text", base64UrlEncode(encrypted)).put("state", state).toString()
            .toByteArray(Charsets.UTF_8)
        val decPayload = httpPostBytes(DEC_ENDPOINT, decBody, userAgent, json = true)
            .toString(Charsets.UTF_8)
        val dec = JSONObject(decPayload)
        check(dec.optInt("status") == 200) { dec.optString("error").takeIf { it.isNotBlank() } ?: "CineJoy decrypt failed" }
        parsePlaylists(dec)
    }

    suspend fun resolve(
        selection: PlaybackSelection,
        positionMs: Long,
        excluded: Set<String>,
        preferredServer: String? = null,
        onServers: (List<String>) -> Unit = {},
        strictPreferredServer: Boolean = false,
        preferredAudioLabel: String? = null,
        lowQuality: Boolean = false,
        userAgent: String = USER_AGENT,
        onServer: (String) -> Unit,
    ): NativePlaybackRequest = withContext(Dispatchers.IO) {
        if (strictPreferredServer) require(!preferredServer.isNullOrBlank()) { "A server is required for pinned preparation." }
        val preferred = serverFromLabel(preferredServer)
            ?: preferredServer?.takeIf { it in FALLBACK_SERVERS }
            ?: FALLBACK_SERVERS.firstOrNull { it.equals(preferredServer, ignoreCase = true) }
        // Recovery already knows the exact working server. Skip discovery so a
        // seek reconnect cannot drift onto a server with different audio.
        val live = if (strictPreferredServer) listOfNotNull(preferred)
            else runCatching { fetchServers(userAgent) }.getOrDefault(FALLBACK_SERVERS)
        val orderedServers = (listOfNotNull(preferred) + live).distinct()
        val labels = orderedServers.map(::serverLabel)
        if (labels.isNotEmpty()) withContext(Dispatchers.Main.immediate) { onServers(labels) }
        val candidates = orderedServers.filter { serverLabel(it) !in excluded }
            .let { remaining ->
                if (strictPreferredServer) {
                    val target = requireNotNull(preferred) { "A server is required for pinned preparation." }
                    listOf(target).filter { serverLabel(it) !in excluded }
                        .takeIf { it.isNotEmpty() }
                        ?: throw IllegalStateException("Server '$preferredServer' is unavailable for this episode. Retry this episode.")
                } else if (preferred != null) {
                    (listOf(preferred) + remaining.filter { it != preferred }).distinct()
                } else remaining
            }
        if (candidates.isEmpty()) throw NoNativeServersException()
        val winner = try {
            firstSuccessful(candidates.map { server -> suspend {
                currentCoroutineContext().ensureActive()
                val playlists = playlistsForServer(selection, server, userAgent)
                val master = playlists.firstOrNull()
                    ?: throw NoNativeServersException()
                val manifest = CineJoyHlsProbe.master(master, httpGet(master, userAgent))
                var playablePosition = positionMs
                var video = playableVideo(manifest, playablePosition, preferredAudioLabel, lowQuality, userAgent)
                if (video == null && playablePosition > 0) {
                    // A saved position can point into a broken CDN segment. Stay on the
                    // same CineJoy server and same audio, then start from the first good chunk.
                    playablePosition = 0
                    video = playableVideo(manifest, 0, preferredAudioLabel, lowQuality, userAgent)
                }
                check(video != null) { "CineJoy's audio or video chunks are unavailable" }
                val request = NativePlaybackRequest(
                    url = master,
                    mimeType = "application/x-mpegURL",
                    referer = REFERER,
                    userAgent = userAgent,
                    cookie = "",
                    title = selection.media.title,
                    positionMs = playablePosition,
                    playing = true,
                    selectionJson = selection.nativeJson(),
                    preferredVideoWidth = video.width,
                    preferredVideoHeight = video.height,
                )
                // The master has been checked above. Preparing the same stream in
                // a second ExoPlayer added a full buffering round trip and could
                // reject a usable CDN stream before the actual player tried it.
                server to request
            } }, parallelism = 2)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw (error as? NoNativeServersException ?: NoNativeServersException())
        }
        withContext(Dispatchers.Main.immediate) { onServer(serverLabel(winner.first)) }
        winner.second
    }
}
