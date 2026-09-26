package com.aliflix.app.player

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class AnimeMappingAssetTest {
    private val payload = """{"tmdb_show:37854:s8":{"anilist:21":{"229-263":"229-263"}}}""".toByteArray()

    @Test fun expandedPayloadIsUsedAsIs() {
        assertArrayEquals(payload, inflateAssetPayload(payload))
    }

    @Test fun compressedPayloadIsInflated() {
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(payload) }
        }.toByteArray()
        assertEquals(0x1F.toByte(), compressed[0])
        assertArrayEquals(payload, inflateAssetPayload(compressed))
    }

    @Test fun shortOrEmptyPayloadsAreNeverInflated() {
        assertArrayEquals(byteArrayOf(), inflateAssetPayload(byteArrayOf()))
        assertArrayEquals(byteArrayOf(0x1F), inflateAssetPayload(byteArrayOf(0x1F)))
    }
}
