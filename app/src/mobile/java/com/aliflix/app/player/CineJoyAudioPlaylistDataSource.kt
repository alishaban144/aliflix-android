package com.aliflix.app.player

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/** Repair only CineJoy's indexed VOD audio layout when a declared playlist returns 502. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CineJoyAudioPlaylistDataSource(
    private val upstream: DataSource,
    private val masterUrl: String,
    private val fetchFactory: DataSource.Factory,
) : DataSource {
    private var repaired: ByteArrayDataSource? = null
    override fun open(dataSpec: DataSpec): Long {
        try { return upstream.open(dataSpec) }
        catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode != 502 || dataSpec.position != 0L ||
                dataSpec.uri.host != Uri.parse(masterUrl).host ||
                !dataSpec.uri.path.orEmpty().matches(Regex("/hls/[^/]+/audio_\\d+\\.m3u8"))) throw error
            upstream.close()
            val body = runCatching {
                CineJoyAudioPlaylistRepair.repair(masterUrl, dataSpec.uri.toString()) { url ->
                    val fetchSpec = dataSpec.buildUpon().setUri(url).setPosition(0)
                        .setLength(androidx.media3.common.C.LENGTH_UNSET.toLong()).setKey(null).build()
                    DataSourceInputStream(fetchFactory.createDataSource(), fetchSpec).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 1_048_576)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            }.onFailure {
                android.util.Log.w("AliflixCineJoy", "Audio playlist recovery rejected: ${it.javaClass.simpleName}")
            }.getOrNull() ?: throw error
            android.util.Log.i("AliflixCineJoy", "Recovered ${dataSpec.uri.lastPathSegment} with verified audio timestamps")
            val source = ByteArrayDataSource(body.toByteArray(Charsets.UTF_8))
            repaired = source
            return source.open(dataSpec)
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = (repaired ?: upstream).read(buffer, offset, length)
    override fun getUri(): Uri? = (repaired ?: upstream).uri
    override fun getResponseHeaders(): Map<String, List<String>> = if (repaired != null) emptyMap() else upstream.responseHeaders
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun close() { try { (repaired ?: upstream).close() } finally { repaired = null } }
}

internal object CineJoyAudioPlaylistRepair {
    private val playlist = Regex("(.*/hls/[^/]+/)audio_(\\d+)\\.m3u8")
    private val media = Regex("(.*/hls/[^/]+/)audio_(\\d+)_(init|\\d+)\\.html")
    private data class Template(val number: String, val init: String, val urls: List<String>, val durations: List<Double>) {
        fun normalize(url: String) = url.replace("/audio_${number}_", "/audio_@_")
        fun agrees(other: Template): Boolean = normalize(init) == other.normalize(other.init) &&
            urls.map(::normalize) == other.urls.map(other::normalize) &&
            durations.dropLast(1) == other.durations.dropLast(1) && abs(durations.last() - other.durations.last()) < 1
    }

    /** Two independent sibling timelines must agree; actual target fMP4 timestamps verify the mapping. */
    fun repair(masterUrl: String, targetUrl: String, fetch: (String) -> ByteArray): String? {
        val target = playlist.matchEntire(targetUrl) ?: return null
        val master = CineJoyHlsProbe.master(masterUrl, fetch(masterUrl).toString(Charsets.UTF_8))
        if (master.audio.none { it.url == targetUrl }) return null
        val templates = mutableListOf<Template>()
        var matched: Template? = null
        for (rendition in master.audio.filter { it.url != targetUrl }) {
            val sibling = playlist.matchEntire(rendition.url) ?: continue
            if (sibling.groupValues[1] != target.groupValues[1]) continue
            val template = runCatching { parse(rendition.url, fetch(rendition.url).toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            if (templates.any { it.agrees(template) }) { matched = template; break }
            templates += template
        }
        val template = matched ?: return null
        fun targetMedia(url: String) = url.replace("/audio_${template.number}_", "/audio_${target.groupValues[2]}_")
        val init = targetMedia(template.init)
        val urls = template.urls.map(::targetMedia)
        val scale = AudioFragmentTiming.timescale(fetch(init))
        val first = AudioFragmentTiming.fragment(fetch(urls.first()), scale)
        val last = AudioFragmentTiming.fragment(fetch(urls.last()), scale)
        if (first.first !in 0.0..60.0 || abs(first.second - template.durations.first()) > 0.05 ||
            abs(last.first - first.first - template.durations.dropLast(1).sum()) > 0.05 ||
            last.second <= 0 || last.second > template.durations.first() + 1) return null
        val durations = template.durations.dropLast(1) + last.second
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:${ceil(durations.max()).toInt()}\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            append("#EXT-X-MAP:URI=\"$init\"\n")
            urls.forEachIndexed { index, url -> append(String.format(Locale.US, "#EXTINF:%.5f,\n%s\n", durations[index], url)) }
            append("#EXT-X-ENDLIST\n")
        }
    }

    private fun parse(url: String, body: String): Template? {
        val number = playlist.matchEntire(url)?.groupValues?.get(2) ?: return null
        val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U" || lines.lastOrNull() != "#EXT-X-ENDLIST" ||
            "#EXT-X-PLAYLIST-TYPE:VOD" !in lines || "#EXT-X-MEDIA-SEQUENCE:0" !in lines) return null
        val allowed = listOf("#EXTM3U", "#EXT-X-VERSION:", "#EXT-X-TARGETDURATION:", "#EXT-X-MEDIA-SEQUENCE:",
            "#EXT-X-PLAYLIST-TYPE:", "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-MAP:", "#EXTINF:", "#EXT-X-BITRATE:", "#EXT-X-ENDLIST")
        if (lines.any { it.startsWith('#') && allowed.none(it::startsWith) }) return null
        val maps = lines.filter { it.startsWith("#EXT-X-MAP:") }
        if (maps.size != 1 || maps.single().contains("BYTERANGE")) return null
        val init = Regex("URI=\"([^\"]+)\"").find(maps.single())?.groupValues?.get(1) ?: return null
        val initMatch = media.matchEntire(init) ?: return null
        if (initMatch.groupValues[2] != number || initMatch.groupValues[3] != "init") return null
        val urls = mutableListOf<String>()
        val durations = mutableListOf<Double>()
        var pending: Double? = null
        for (line in lines) {
            if (line.startsWith("#EXTINF:")) {
                if (pending != null) return null
                pending = line.substringAfter(':').substringBefore(',').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it < 30 } ?: return null
            } else if (!line.startsWith('#')) {
                val match = media.matchEntire(line) ?: return null
                if (match.groupValues[2] != number || match.groupValues[3].toIntOrNull() != urls.size + 1 ||
                    java.net.URI(line).host != java.net.URI(url).host) return null
                urls += line
                durations += pending ?: return null
                pending = null
            }
        }
        if (pending != null || urls.size < 2 || java.net.URI(init).host != java.net.URI(url).host) return null
        return Template(number, init, urls, durations)
    }
}

/** Minimal bounded ISO-BMFF timing reader; no media samples are stored. */
internal object AudioFragmentTiming {
    private data class Box(val type: String, val data: Int, val end: Int)
    private fun uint(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= bytes.size)
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
    }
    private fun boxes(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): List<Box> {
        val result = mutableListOf<Box>()
        var offset = start
        while (offset < end) {
            require(offset + 8 <= end)
            val size = uint(bytes, offset)
            require(size in 8..(end - offset).toLong())
            result += Box(String(bytes, offset + 4, 4, Charsets.US_ASCII), offset + 8, offset + size.toInt())
            offset += size.toInt()
        }
        return result
    }
    private fun child(bytes: ByteArray, parent: Box, type: String) = boxes(bytes, parent.data, parent.end).single { it.type == type }
    fun timescale(bytes: ByteArray): Long {
        val moov = boxes(bytes).single { it.type == "moov" }
        val trak = child(bytes, moov, "trak")
        val mdia = child(bytes, trak, "mdia")
        val handler = child(bytes, mdia, "hdlr")
        require(handler.data + 12 <= handler.end && String(bytes, handler.data + 8, 4, Charsets.US_ASCII) == "soun")
        val mdhd = child(bytes, mdia, "mdhd")
        val version = bytes[mdhd.data].toInt()
        require(version in 0..1)
        val offset = mdhd.data + if (version == 1) 20 else 12
        require(offset + 4 <= mdhd.end)
        return uint(bytes, offset).also { require(it in 1..192_000) }
    }
    fun fragment(bytes: ByteArray, scale: Long): Pair<Double, Double> {
        require(scale > 0)
        val moof = boxes(bytes).single { it.type == "moof" }
        val traf = child(bytes, moof, "traf")
        val tfhd = child(bytes, traf, "tfhd")
        val flags = uint(bytes, tfhd.data).toInt() and 0xffffff
        var offset = tfhd.data + 8
        if (flags and 1 != 0) offset += 8
        if (flags and 2 != 0) offset += 4
        val defaultDuration = if (flags and 8 != 0) {
            require(offset + 4 <= tfhd.end); uint(bytes, offset)
        } else 0
        val tfdt = child(bytes, traf, "tfdt")
        val version = bytes[tfdt.data].toInt()
        require(version in 0..1 && tfdt.data + (if (version == 1) 12 else 8) <= tfdt.end)
        val timestamp = if (version == 1) {
            val high = uint(bytes, tfdt.data + 4)
            require(high <= 0x7fffffff)
            (high shl 32) or uint(bytes, tfdt.data + 8)
        } else uint(bytes, tfdt.data + 4)
        var duration = 0L
        for (trun in boxes(bytes, traf.data, traf.end).filter { it.type == "trun" }) {
            val runFlags = uint(bytes, trun.data).toInt() and 0xffffff
            val count = uint(bytes, trun.data + 4)
            require(count in 1..100_000 && trun.data + 8 <= trun.end)
            var cursor = trun.data + 8
            if (runFlags and 1 != 0) cursor += 4
            if (runFlags and 4 != 0) cursor += 4
            repeat(count.toInt()) {
                val sampleDuration = if (runFlags and 256 != 0) {
                    require(cursor + 4 <= trun.end); uint(bytes, cursor).also { cursor += 4 }
                } else defaultDuration
                require(sampleDuration > 0)
                duration += sampleDuration
                if (runFlags and 512 != 0) cursor += 4
                if (runFlags and 1024 != 0) cursor += 4
                if (runFlags and 2048 != 0) cursor += 4
                require(cursor <= trun.end)
            }
        }
        require(duration > 0)
        return timestamp.toDouble() / scale to duration.toDouble() / scale
    }
}
