package com.aliflix.app.player

import java.net.URI

/** The master alone is insufficient: CineJoy can return 200 for a playlist and 502 for its media. */
internal object CineJoyHlsProbe {
    data class Rendition(val url: String, val label: String, val default: Boolean)
    data class Video(val url: String, val width: Int, val height: Int, val codecs: String)
    data class Master(val audio: List<Rendition>, val video: List<Video>)
    data class Segment(val init: String?, val media: String)

    private fun attribute(line: String, name: String): String? =
        Regex("(?:^|,)$name=(?:\"([^\"]*)\"|([^,]*))").find(line.substringAfter(':', ""))
            ?.let { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }

    fun master(url: String, body: String): Master {
        require(body.lineSequence().firstOrNull()?.trim() == "#EXTM3U")
        val lines = body.lineSequence().map(String::trim).toList()
        val video = lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
            val path = lines.drop(index + 1).firstOrNull { it.isNotEmpty() }?.takeUnless { it.startsWith('#') }
                ?: return@mapIndexedNotNull null
            val size = attribute(line, "RESOLUTION")?.split('x').orEmpty()
            Video(URI(url).resolve(path).toString(), size.getOrNull(0)?.toIntOrNull() ?: 0,
                size.getOrNull(1)?.toIntOrNull() ?: 0, attribute(line, "CODECS").orEmpty())
        }
        val groups = lines.filter { it.startsWith("#EXT-X-STREAM-INF:") }
            .mapNotNull { attribute(it, "AUDIO") }.toSet()
        val audio = lines.filter { it.startsWith("#EXT-X-MEDIA:") && attribute(it, "TYPE") == "AUDIO" }
            .mapNotNull { line ->
                val path = attribute(line, "URI") ?: return@mapNotNull null
                val label = attribute(line, "NAME") ?: return@mapNotNull null
                if (attribute(line, "GROUP-ID") !in groups) return@mapNotNull null
                Rendition(URI(url).resolve(path).toString(), label, attribute(line, "DEFAULT") == "YES")
            }
        return Master(audio, video)
    }

    /** Pick the chunk covering the resume point and its initialization map. */
    fun segment(url: String, body: String, positionMs: Long): Segment? {
        if (body.lineSequence().firstOrNull()?.trim() != "#EXTM3U") return null
        var init: String? = null
        var timeMs = 0.0
        var durationMs: Double? = null
        for (line in body.lineSequence().map(String::trim)) {
            when {
                line.startsWith("#EXT-X-MAP:") -> init = attribute(line, "URI")?.let { URI(url).resolve(it).toString() }
                line.startsWith("#EXTINF:") -> durationMs = line.substringAfter(':').substringBefore(',').toDoubleOrNull()?.times(1000)
                line.isNotEmpty() && !line.startsWith('#') && durationMs != null -> {
                    val segment = Segment(init, URI(url).resolve(line).toString())
                    if (positionMs < timeMs + durationMs) return segment
                    timeMs += durationMs
                    durationMs = null
                }
            }
        }
        return null
    }

    /** Avoid unsupported 4K/HEVC and the site's intermittent 720p failures when 1080p AVC works. */
    fun preferredVideos(video: List<Video>, lowQuality: Boolean = false): List<Video> = video.sortedWith(
        compareBy<Video> { if (it.codecs.contains("avc", ignoreCase = true)) 0 else 1 }
            .thenBy { if (it.height in 1..1080) 0 else 1 }
            .thenBy { if (lowQuality) it.height else -it.height },
    )
}
