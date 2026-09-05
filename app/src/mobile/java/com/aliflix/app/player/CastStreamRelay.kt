package com.aliflix.app.player

import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * A session-scoped LAN relay. The receiver never needs browser cookies or special headers.
 * Only registered resource IDs are routable; this is not an arbitrary URL proxy. HLS playlists,
 * variants, audio, subtitles, keys and byte ranges all use the same authenticated upstream path.
 */
internal class CastStreamRelay(
    private val request: NativePlaybackRequest,
    private val address: String,
    bindAddress: InetAddress? = null,
) : Closeable {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val server = ServerSocket(0, 16, bindAddress)
    private val targets = ConcurrentHashMap<String, String>()
    private val ids = ConcurrentHashMap<String, String>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val permits = Semaphore(8)
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "AliflixCastRelay").apply { isDaemon = true } }
    val streamUrl: String = register(request.url)
    val subtitleUrl: String get() = "http://$address:${server.localPort}/$token/subtitles.vtt"

    init {
        thread(name = "AliflixCastAccept", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (!permits.tryAcquire()) { socket.close(); continue }
                clients.add(socket)
                runCatching {
                    workers.execute {
                        try { socket.use(::serve) } catch (_: Exception) {
                            // A receiver can abandon a segment during seek/quality changes.
                        } finally { clients.remove(socket); permits.release() }
                    }
                }.onFailure { clients.remove(socket); socket.close(); permits.release() }
            }
        }
    }

    private fun register(url: String): String {
        require(isNativeStreamUrl(url))
        val id = ids.computeIfAbsent(url) {
            UUID.randomUUID().toString().also { id -> targets[id] = url }
        }
        return "http://$address:${server.localPort}/$token/$id"
    }

    internal fun rewritePlaylist(text: String, baseUrl: String): String = text.lineSequence().joinToString("\n") { line ->
        if (line.isBlank()) line
        else if (!line.startsWith("#")) register(URI(baseUrl).resolve(line.trim()).toString())
        else Regex("URI=\"([^\"]+)\"").replace(line) { match ->
            "URI=\"${register(URI(baseUrl).resolve(match.groupValues[1]).toString())}\""
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val first = input.readLine()?.takeIf { it.length < 4096 }?.split(' ') ?: return
        if (first.size != 3) return
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = input.readLine() ?: return
            headerBytes += line.length
            if (headerBytes > 16_384) return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        val path = first[1].substringBefore('?')
        val prefix = "/$token/"
        if (!path.startsWith(prefix)) { send(output, 404, "text/plain", byteArrayOf()); return }
        if (first[0] == "OPTIONS") { send(output, 204, "text/plain", byteArrayOf()); return }
        if (first[0] !in setOf("GET", "HEAD")) { send(output, 405, "text/plain", byteArrayOf()); return }
        val head = first[0] == "HEAD"
        if (path == "${prefix}subtitles.vtt") {
            send(output, 200, "text/vtt; charset=utf-8", request.subtitlesVtt.toByteArray(), head)
            return
        }
        val upstream = targets[path.removePrefix(prefix)]
        if (upstream == null) { send(output, 404, "text/plain", byteArrayOf()); return }
        var connection: HttpURLConnection? = null
        try {
            var url = upstream
            for (redirect in 0..5) {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000; readTimeout = 30_000
                    requestMethod = if (head) "HEAD" else "GET"
                    setRequestProperty("User-Agent", request.userAgent)
                    setRequestProperty("Referer", request.referer)
                    val origin = URI(request.referer)
                    setRequestProperty("Origin", "${origin.scheme}://${origin.rawAuthority}")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (URI(url).host == URI(request.url).host && request.cookie.isNotBlank()) setRequestProperty("Cookie", request.cookie)
                    headers["range"]?.takeIf { it.matches(Regex("bytes=\\d*-\\d*(,\\d*-\\d*)*")) }?.let { setRequestProperty("Range", it) }
                }
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) break
                val location = connection.getHeaderField("Location") ?: error("Redirect without location")
                val next = URI(url).resolve(location).toString()
                require(isNativeStreamUrl(next))
                connection.disconnect(); connection = null; url = next
            }
            val response = checkNotNull(connection)
            val code = response.responseCode
            val type = response.contentType ?: if (upstream == request.url) request.mimeType else "application/octet-stream"
            val playlist = type.contains("mpegurl", true) || URI(url).path.endsWith(".m3u8", true)
            if (!head && code == 200 && playlist) {
                val bytes = response.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    val body = java.io.ByteArrayOutputStream()
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(body.size() + count <= 2_097_152) { "Playlist too large" }
                        body.write(buffer, 0, count)
                    }
                    body.toByteArray()
                }
                require(bytes.size <= 2_097_152) { "Playlist too large" }
                send(output, 200, "application/vnd.apple.mpegurl", rewritePlaylist(bytes.toString(Charsets.UTF_8), url).toByteArray())
            } else {
                val responseHeaders = buildString {
                    append("HTTP/1.1 $code Response\r\nContent-Type: $type\r\n")
                    listOf("Content-Length", "Content-Range", "Accept-Ranges").forEach { name ->
                        response.getHeaderField(name)?.let { append("$name: $it\r\n") }
                    }
                    append(corsHeaders)
                }
                output.write(responseHeaders.toByteArray(Charsets.US_ASCII))
                if (!head) (if (code < 400) response.inputStream else response.errorStream)?.use { it.copyTo(output) }
                output.flush()
            }
        } finally { connection?.disconnect() }
    }

    private fun send(output: BufferedOutputStream, status: Int, type: String, bytes: ByteArray, head: Boolean = false) {
        output.write("HTTP/1.1 $status Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n$corsHeaders".toByteArray(Charsets.US_ASCII))
        if (!head) output.write(bytes)
        output.flush()
    }

    override fun close() {
        server.close()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        targets.clear(); ids.clear()
    }

    private companion object {
        const val corsHeaders = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Range\r\nAccess-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
    }
}
