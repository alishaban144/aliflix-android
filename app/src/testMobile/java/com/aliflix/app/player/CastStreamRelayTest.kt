package com.aliflix.app.player

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class CastStreamRelayTest {
    @Test fun receiverCanFetchMasterVariantsKeysAndByteRangesWithRequiredHeaders() {
        val requests = CopyOnWriteArrayList<String>()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            requests.add(exchange.requestURI.toString())
            if (exchange.requestHeaders.getFirst("Cookie") != "session=test" || exchange.requestHeaders.getFirst("Referer") != "https://player.example/watch") {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return@createContext
            }
            val path = exchange.requestURI.path
            val text = when (path) {
                "/master.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nvideo/list.m3u8?token=a%2Fb\n"
                "/video/list.m3u8" -> "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key?k=1\"\n#EXTINF:5,\nsegment.ts\n#EXT-X-ENDLIST\n"
                "/key" -> "secretkey"
                else -> "0123456789"
            }
            exchange.responseHeaders.add("Content-Type", if (path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream")
            val ranged = exchange.requestHeaders.getFirst("Range") == "bytes=2-5"
            val bytes = (if (ranged) text.substring(2, 6) else text).toByteArray()
            if (ranged) exchange.responseHeaders.add("Content-Range", "bytes 2-5/10")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.add("Content-Length", bytes.size.toString()); exchange.sendResponseHeaders(200, -1)
            } else { exchange.sendResponseHeaders(if (ranged) 206 else 200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
            exchange.close()
        }
        upstream.start()
        try {
            val request = NativePlaybackRequest("http://127.0.0.1:${upstream.address.port}/master.m3u8", "application/x-mpegURL", "https://player.example/watch", "Aliflix", "session=test", "Test", 0, true)
            CastStreamRelay(request, "127.0.0.1").use { relay ->
                val master = URL(relay.streamUrl).readText()
                assertFalse(master.contains("token=a"))
                val variant = master.lineSequence().first { it.startsWith("http") }
                val playlist = URL(variant).readText()
                val key = Regex("URI=\"([^\"]+)\"").find(playlist)!!.groupValues[1]
                assertEquals("secretkey", URL(key).readText())
                val segment = playlist.lineSequence().first { it.startsWith("http") }
                val range = URL(segment).openConnection() as HttpURLConnection
                range.setRequestProperty("Range", "bytes=2-5")
                assertEquals(206, range.responseCode)
                assertEquals("2345", range.inputStream.bufferedReader().readText())
                assertEquals("bytes 2-5/10", range.getHeaderField("Content-Range"))
                assertEquals("*", range.getHeaderField("Access-Control-Allow-Origin"))
                range.disconnect()
                val invalid = URL(relay.streamUrl.replace(Regex("/[a-f0-9]{32}/"), "/wrong-token/")).openConnection() as HttpURLConnection
                assertEquals(404, invalid.responseCode); invalid.disconnect()
                assertTrue(requests.contains("/video/list.m3u8?token=a%2Fb"))
            }
        } finally { upstream.stop(0) }
    }

    @Test fun receiverCanFetchSubtitlesVttWithCors() {
        val sampleVtt = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHello Aliflix\n"
        val request = NativePlaybackRequest(
            url = "http://127.0.0.1:8080/master.m3u8",
            mimeType = "application/x-mpegURL",
            referer = "https://player.example/watch",
            userAgent = "Aliflix",
            cookie = "session=test",
            title = "Test",
            positionMs = 0,
            playing = true,
            subtitlesVtt = sampleVtt
        )
        CastStreamRelay(request, "127.0.0.1").use { relay ->
            val conn = URL(relay.subtitleUrl).openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertEquals("text/vtt; charset=utf-8", conn.getHeaderField("Content-Type"))
            assertEquals("*", conn.getHeaderField("Access-Control-Allow-Origin"))
            assertEquals(sampleVtt, conn.inputStream.bufferedReader().readText())
            conn.disconnect()
        }
    }
}


