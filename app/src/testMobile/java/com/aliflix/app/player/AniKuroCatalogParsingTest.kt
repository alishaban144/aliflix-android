package com.aliflix.app.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AniKuroCatalogParsingTest {
    private val base = "https://anikuro.to/"

    private fun payload(text: String) = JSONObject(text)

    @Test fun animepowerStreamsAlwaysCarryTheSiteOriginAsReferer() {
        // AniKuro answers animepower with an empty headers object, so the site origin is the only origin.
        val streams = anikuroStreams("animepower", anikuroVariants(payload("""
            {"ok":true,"data":{"provider":"animepower","normalized":[
              {"variant":"sub","sources":[{"url":"https://cdn.example/upload/a/master.m3u8","quality":"AniKuro","type":"hls","isM3u8":true}],"subtitles":[],"headers":{}},
              {"variant":"dub","sources":[{"url":"https://cdn.example/upload/b/master.m3u8","quality":"AniKuro","type":"hls","isM3u8":true}],"headers":{}}]}}
        """.trimIndent()), base))
        assertEquals(listOf("AniKuro / animepower / sub / Stream 1", "AniKuro / animepower / dub / Stream 2"), streams.map { it.label })
        assertTrue(streams.all { it.referer == base })
        assertTrue(streams.all { it.hls })
        assertEquals("https://cdn.example/upload/a/master.m3u8", streams.first().url)
    }

    @Test fun noParsedStreamIsEverLeftWithoutAReferer() {
        val streams = anikuroStreams("animepower", anikuroVariants(payload("""
            {"data":{"normalized":[{"variant":"sub","sources":[{"url":"https://cdn.example/x/master.m3u8","type":"hls"}]}]}}
        """.trimIndent()), base))
        assertTrue(streams.isNotEmpty())
        streams.forEach { assertTrue("blank referer for ${it.label}", isNativeStreamUrl(it.referer)) }
    }

    @Test fun providerSuppliedOriginsWinOverTheSiteOrigin() {
        val variants = anikuroVariants(payload("""
            {"data":{"normalized":[{"variant":"sub","headers":{"Referer":"https://player.example/"},"sources":[
              {"url":"https://cdn.example/c/master.m3u8","type":"hls","headers":{"Referer":"https://upstream.example/"}},
              {"url":"https://cdn.example/d/master.m3u8","type":"hls","upstreamReferer":"https://fallback.example/"},
              {"url":"https://cdn.example/e/master.m3u8","type":"hls"}]}]}}
        """.trimIndent()), base)
        val sources = variants.single().sources
        assertEquals("https://upstream.example/", sources[0].referer)
        assertEquals("https://fallback.example/", sources[1].referer)
        assertEquals("https://player.example/", sources[2].referer)
    }

    @Test fun unusableOriginValuesFallBackToTheSiteOrigin() {
        val variants = anikuroVariants(payload("""
            {"data":{"normalized":[{"variant":"sub","headers":{"Referer":"javascript:alert(1)"},"sources":[
              {"url":"https://cdn.example/f/master.m3u8","type":"hls","upstreamReferer":"nonsense"}]}]}}
        """.trimIndent()), base)
        assertEquals(base, variants.single().sources.single().referer)
    }

    @Test fun legacyRawPayloadsAreReadWhenNothingWasNormalised() {
        val variants = anikuroVariants(payload("""
            {"data":{"raw":{"error":"not_found","sub":null,"dub":null},"normalized":[]}}
        """.trimIndent()), base)
        assertTrue(variants.isEmpty())
        val raw = anikuroVariants(payload("""
            {"data":{"raw":{"sub":{"default":"https://cdn.example/g/index.m3u8","sources":[]},"dub":null},"normalized":[]}}
        """.trimIndent()), base)
        assertEquals("sub", raw.single().variant)
        assertEquals("https://cdn.example/g/index.m3u8", raw.single().sources.single().url)
        assertEquals(base, raw.single().sources.single().referer)
    }

    @Test fun nonPlayableAndRelativeSourcesAreRejected() {
        val variants = anikuroVariants(payload("""
            {"data":{"normalized":[{"variant":"sub","sources":[
              {"url":"blob:https://anikuro.to/1234","type":"hls"},
              {"url":"/relative/master.m3u8","type":"hls"},
              {"url":"https://cdn.example/h/master.m3u8","type":"mp4"}]}]}}
        """.trimIndent()), base)
        val single = variants.single().sources.single()
        assertEquals("https://cdn.example/h/master.m3u8", single.url)
        assertFalse(single.hls)
    }

    @Test fun duplicateMirrorsOfOneVariantAreListedOnce() {
        val streams = anikuroStreams("animix", anikuroVariants(payload("""
            {"data":{"normalized":[{"variant":"sub","sources":[
              {"url":"https://proxy.example/m.m3u8","type":"hls","quality":"default"},
              {"url":"https://proxy.example/m.m3u8","type":"hls","quality":"default"}]}]}}
        """.trimIndent()), base))
        assertEquals(1, streams.size)
        assertEquals("AniKuro / animix / sub / Stream 1", streams.single().label)
    }

    @Test fun resolutionLabelsAreUsedWhenTheProviderReportsThem() {
        val streams = anikuroStreams("animepahe", anikuroVariants(payload("""
            {"data":{"normalized":[{"variant":"sub","sources":[
              {"url":"https://cdn.example/i/master.m3u8","type":"hls","quality":"1080p"},
              {"url":"https://cdn.example/i/720.m3u8","type":"hls","quality":"720p"}]}]}}
        """.trimIndent()), base))
        assertEquals(listOf("AniKuro / animepahe / sub / 1080p", "AniKuro / animepahe / sub / 720p"), streams.map { it.label })
    }
}
