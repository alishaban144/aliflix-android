package com.aliflix.app.data

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

object SafeHttpTransport {
    private val resolvedIpCache = ConcurrentHashMap<String, String>()

    fun resolveHost(host: String): String {
        runCatching {
            val addresses = InetAddress.getAllByName(host)
            val ipv4 = addresses.firstOrNull { it is java.net.Inet4Address }
            ipv4?.hostAddress?.let { return it }
            addresses.firstOrNull()?.hostAddress?.let { return it }
        }

        resolvedIpCache[host]?.let { return it }

        runCatching {
            val dohUrl = URL("https://1.1.1.1/dns-query?name=$host&type=A")
            val conn = (dohUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Accept", "application/dns-json")
                setRequestProperty("User-Agent", "AliflixAndroid")
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val obj = org.json.JSONObject(json)
            val answers = obj.optJSONArray("Answer")
            if (answers != null && answers.length() > 0) {
                for (i in 0 until answers.length()) {
                    val item = answers.getJSONObject(i)
                    if (item.optInt("type") == 1) {
                        val ip = item.getString("data")
                        resolvedIpCache[host] = ip
                        return ip
                    }
                }
            }
        }
        return host
    }

    fun openConnection(
        urlString: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val originalUrl = URL(urlString)
        val connection = (originalUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true

            setRequestProperty("Accept-Encoding", "gzip, deflate")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
                )
            }
            if (headers.none { it.key.equals("Accept", ignoreCase = true) }) {
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,image/webp,*/*;q=0.8",
                )
            }
            setRequestProperty(
                "Sec-Ch-Ua",
                "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Android WebView\";v=\"126\"",
            )
            setRequestProperty("Sec-Ch-Ua-Mobile", "?1")
            setRequestProperty("Sec-Ch-Ua-Platform", "\"Android\"")
            setRequestProperty("Sec-Fetch-Dest", "document")
            setRequestProperty("Sec-Fetch-Mode", "navigate")
            setRequestProperty("Sec-Fetch-Site", "none")

            headers.forEach(::setRequestProperty)
        }
        return connection
    }

    fun readResponseText(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val rawStream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        val isGzip = "gzip".equals(connection.contentEncoding, ignoreCase = true)
        val stream: InputStream = if (isGzip) GZIPInputStream(rawStream) else rawStream
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
