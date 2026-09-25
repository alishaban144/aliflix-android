package com.aliflix.app.player

import android.net.Uri
import org.json.JSONObject

/** Apply Miruro's origin rules to manifests, keys and segments, including absolute URLs. */
internal fun NativePlaybackRequest.resolveStreamUrl(raw: String): String {
    if (streamUrlRules.isBlank() || !isNativeStreamUrl(raw)) return raw
    val rules = JSONObject(streamUrlRules)
    val uri = Uri.parse(raw)
    val builder = uri.buildUpon()
    rules.optJSONObject("origin")?.takeIf { it.optBoolean("enabled") }?.let {
        val origin = Uri.parse(it.getString("url"))
        require(origin.scheme == "https" && !origin.host.isNullOrBlank())
        builder.scheme(origin.scheme).encodedAuthority(origin.encodedAuthority)
    }
    // Query overrides belong to playlists; segment signatures must remain unchanged.
    rules.optJSONObject("query")?.takeIf { it.optBoolean("enabled") &&
        (raw == url || uri.path.orEmpty().endsWith(".m3u8", true)) }?.optJSONObject("params")?.let { params ->
        builder.clearQuery()
        uri.queryParameterNames.filterNot { params.has(it) }.forEach { name ->
            uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        params.keys().forEach { builder.appendQueryParameter(it, params.getString(it)) }
    }
    return builder.build().toString()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun NativePlaybackRequest.resolveStreamSpec(spec: androidx.media3.datasource.DataSpec): androidx.media3.datasource.DataSpec {
    val resolved = spec.withUri(Uri.parse(resolveStreamUrl(spec.uri.toString())))
    return if (cookie.isNotBlank() && resolved.uri.host == Uri.parse(url).host)
        resolved.withRequestHeaders(resolved.httpRequestHeaders + ("Cookie" to cookie)) else resolved
}
