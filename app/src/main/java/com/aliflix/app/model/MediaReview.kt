package com.aliflix.app.model

data class MediaReview(
    val id: String,
    val author: String,
    val authorName: String? = null,
    val authorUsername: String? = null,
    val avatarPath: String? = null,
    val rating: Double? = null,
    val content: String,
    val createdAt: String? = null,
    val url: String? = null,
) {
    val displayName: String
        get() = authorName?.takeIf(String::isNotBlank)
            ?: author.takeIf(String::isNotBlank)
            ?: authorUsername?.takeIf(String::isNotBlank)
            ?: "Anonymous Reviewer"

    val avatarUrl: String?
        get() = avatarPath?.let { path ->
            when {
                path.startsWith("https://") || path.startsWith("http://") -> path
                path.startsWith("/https://") || path.startsWith("/http://") -> path.removePrefix("/")
                path.startsWith("/") -> "https://image.tmdb.org/t/p/w185$path"
                path.isNotBlank() -> "https://image.tmdb.org/t/p/w185/$path"
                else -> null
            }
        }

    val displayDate: String?
        get() = createdAt?.take(10)
}
