package com.aliflix.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream

/** Applied outside the byte cache: cached masters also respect this playback's video choice. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CineJoyManifestDataSource(
    private val upstream: DataSource,
    private val request: NativePlaybackRequest,
) : DataSource {
    private var manifest: ByteArrayInputStream? = null
    private var manifestUri: Uri? = null
    private var buffered = false

    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.toString() != request.url || dataSpec.position != 0L) return upstream.open(dataSpec)
        buffered = true
        manifestUri = dataSpec.uri
        val body = DataSourceInputStream(upstream, dataSpec).use { it.readBytes().toString(Charsets.UTF_8) }
        val bytes = CineJoyHlsProbe.pinVideo(body, request.preferredVideoWidth, request.preferredVideoHeight)
            .toByteArray(Charsets.UTF_8)
        manifest = ByteArrayInputStream(bytes)
        return bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        if (length == 0) 0 else manifest?.read(buffer, offset, length)
            ?: if (buffered) C.RESULT_END_OF_INPUT else upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = manifestUri ?: upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = if (buffered) emptyMap() else upstream.responseHeaders
    override fun close() {
        if (!buffered) upstream.close()
        manifest?.close()
        manifest = null
        manifestUri = null
        buffered = false
    }
}
