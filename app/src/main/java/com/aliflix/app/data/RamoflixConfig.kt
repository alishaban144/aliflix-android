package com.aliflix.app.data

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class RamoflixConfig(
    val baseUrl: String = DEFAULT_URL,
) {
    val cleanDomain: String
        get() = runCatching {
            URI(baseUrl).host ?: baseUrl
        }.getOrDefault(baseUrl)

    fun buildWatchUrl(title: String): String {
        val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString())
        return "${baseUrl.trimEnd('/')}/?s=$encodedTitle"
    }

    companion object {
        const val DEFAULT_URL = "https://ramoflix.net/"

        fun normalizeBaseUrl(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return null
            val withScheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                "://" in trimmed -> return null
                else -> "https://$trimmed"
            }
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            if (
                !uri.scheme.equals("https", ignoreCase = true) ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null ||
                uri.rawQuery != null ||
                uri.rawFragment != null
            ) {
                return null
            }
            return "https://${withScheme.substringAfter("://").trimEnd('/')}/"
        }
    }
}
