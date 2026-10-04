package com.aliflix.app.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/** Retry a failed CineJoy CDN fragment through a fresh cache key, retaining its byte range. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CineJoyFragmentDataSource(
    private val upstream: DataSource,
    private val masterUrl: String,
) : DataSource {
    private var originalUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        originalUri = dataSpec.uri
        val eligible = dataSpec.uri.host == Uri.parse(masterUrl).host &&
            dataSpec.uri.path.orEmpty().endsWith(".html")
        var attempt = 0
        while (true) {
            val spec = if (attempt == 0) dataSpec else dataSpec.withUri(
                dataSpec.uri.buildUpon().appendQueryParameter("_aliflix_retry",
                    "${System.currentTimeMillis()}-${sequence.incrementAndGet()}").build(),
            ).withRequestHeaders(dataSpec.httpRequestHeaders + ("Cache-Control" to "no-cache"))
            try {
                return upstream.open(spec)
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (!eligible || error.responseCode != 502 || attempt >= 6) throw error
                upstream.close()
                attempt++
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = originalUri ?: upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun close() { try { upstream.close() } finally { originalUri = null } }

    private companion object { val sequence = AtomicLong() }
}
