package com.aliflix.app.player

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CineJoyFragmentDataSourceTest {
    private class Upstream(private var failures: Int, private val code: Int = 502) : DataSource {
        val specs = mutableListOf<DataSpec>()
        var closes = 0
        private val bytes = ByteArrayDataSource(byteArrayOf(1, 2, 3, 4, 5))
        override fun open(dataSpec: DataSpec): Long {
            specs += dataSpec
            if (failures-- > 0) throw HttpDataSource.InvalidResponseCodeException(
                code, "failure", null, emptyMap(), dataSpec, byteArrayOf(),
            )
            return bytes.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = bytes.read(buffer, offset, length)
        override fun getUri(): Uri? = bytes.uri
        override fun addTransferListener(listener: TransferListener) {}
        override fun close() { closes++; bytes.close() }
    }

    @Test fun freshRetriesPreserveRangeHeadersAndOriginalUri() {
        val upstream = Upstream(2)
        val source = CineJoyFragmentDataSource(upstream, "https://cdn.test/master.m3u8")
        val spec = DataSpec.Builder().setUri("https://cdn.test/audio_2_3.html?token=original")
            .setPosition(2).setLength(2).setKey("original-cache-key")
            .setHttpRequestHeaders(mapOf("Referer" to CineJoyNativeCatalog.REFERER)).build()
        assertEquals(2L, source.open(spec))
        assertEquals(3, upstream.specs.size)
        assertEquals(2, upstream.closes)
        assertEquals(spec.uri, source.uri)
        val retryUris = upstream.specs.drop(1).map { retry ->
            assertEquals(spec.position, retry.position)
            assertEquals(spec.length, retry.length)
            assertEquals(spec.key, retry.key)
            assertEquals("original", retry.uri.getQueryParameter("token"))
            assertEquals(CineJoyNativeCatalog.REFERER, retry.httpRequestHeaders["Referer"])
            assertEquals("no-cache", retry.httpRequestHeaders["Cache-Control"])
            assertNotNull(retry.uri.getQueryParameter("_aliflix_retry"))
            retry.uri
        }
        assertEquals(2, retryUris.distinct().size)
        val data = ByteArray(2)
        assertEquals(2, source.read(data, 0, 2))
        assertArrayEquals(byteArrayOf(3, 4), data)
        source.close()
    }

    @Test fun retriesAreBoundedAndLeavePermanentErrorsVisible() {
        val upstream = Upstream(100)
        val source = CineJoyFragmentDataSource(upstream, "https://cdn.test/master.m3u8")
        try { source.open(DataSpec(Uri.parse("https://cdn.test/audio.html"))); fail("Expected 502") }
        catch (error: HttpDataSource.InvalidResponseCodeException) { assertEquals(502, error.responseCode) }
        assertEquals(7, upstream.specs.size)
        source.close()
    }

    @Test fun manifestsOtherHostsAndAuthorizationErrorsAreNotRewritten() {
        for ((url, code) in listOf("https://cdn.test/audio.m3u8" to 502,
            "https://other.test/audio.html" to 502, "https://cdn.test/audio.html" to 403)) {
            val upstream = Upstream(1, code)
            val source = CineJoyFragmentDataSource(upstream, "https://cdn.test/master.m3u8")
            try { source.open(DataSpec(Uri.parse(url))); fail("Expected HTTP error") }
            catch (error: HttpDataSource.InvalidResponseCodeException) { assertEquals(code, error.responseCode) }
            assertEquals(1, upstream.specs.size)
            assertEquals(url, upstream.specs.single().uri.toString())
            source.close()
        }
    }
}
