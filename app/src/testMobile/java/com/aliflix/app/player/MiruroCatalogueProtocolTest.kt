package com.aliflix.app.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPOutputStream

class MiruroCatalogueProtocolTest {
    @Test fun currentBinaryCatalogueDecodesAndRejectsCorruptCompressedData() {
        val json = """{"data":[{"id":"ZkbbIfGqvoOrvvss5iQuAlVkW4AaYPxz","external_ids":{"anilist":["21"]}}]}"""
        val output = java.io.ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json.toByteArray()) }
        val key = "miruro/catalog".toByteArray()
        val compressed = output.toByteArray()
        val encoded = ByteArray(compressed.size) { (compressed[it].toInt() xor key[it % key.size].toInt()).toByte() }
        assertEquals("21", miruroCataloguePayload(encoded, "application/octet-stream").getJSONArray("data").getJSONObject(0)
            .getJSONObject("external_ids").getJSONArray("anilist").getString(0))
        assertTrue(runCatching { miruroCataloguePayload(byteArrayOf(0, 1, 2), "application/octet-stream") }.isFailure)
    }
    @Test fun currentPlayPayloadKeepsJapaneseVariantsOriginsAndDistinctServers() {
        val payload = JSONObject("""{"tracks":[
          {"track":"dub","providers":[{"provider":"a","servers":[{"server":"Dub","streams":[{"url":"https://cdn.test/dub.m3u8","format":"hls"}]}]}]},
          {"track":"ssub","providers":[{"provider":"a","servers":[{"server":"Fast","headers":{"Referer":"https://origin.test/"},"streams":[{"url":"https://cdn.test/soft.m3u8","format":"hls","quality":"auto"}]}]}]},
          {"track":"sub","providers":[{"provider":"b","servers":[{"server":"Fallback","streams":[{"url":"https://cdn.test/hard.mp4","format":"mp4","quality":"720p"},{"url":"https://embed.test/","format":"iframe"}]}]}]}
        ]}""")
        val streams = miruroNativeStreams(payload, JSONObject("""{"providerOrder":["a","b"],"streaming":{"a":{"hls":{"query":{"enabled":false}}}}}"""))
        assertEquals(2, streams.size)
        assertEquals("https://origin.test/", streams.first().referer)
        assertTrue(streams.first().hls)
        assertFalse(streams.last().hls)
        assertTrue(streams.first().label.contains("ssub"))
        assertTrue(streams.none { it.url.contains("dub") })
    }
}
