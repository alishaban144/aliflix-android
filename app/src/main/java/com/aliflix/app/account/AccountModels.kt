package com.aliflix.app.account

import com.aliflix.app.model.Media
import com.aliflix.app.data.PlaybackProgress

data class AccountUser(
    val uid: String,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
    val providerIds: Set<String>,
)

data class AccountState(
    val user: AccountUser? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val passwordResetSentTo: String? = null,
) {
    val uid: String? get() = user?.uid
    val displayName: String? get() = user?.displayName
    val email: String? get() = user?.email
    val photoUrl: String? get() = user?.photoUrl
    val isSignedIn: Boolean get() = user != null
}

data class AccountActionResult(
    val succeeded: Boolean,
    val message: String? = null,
)

sealed interface AccountSyncState {
    data object SignedOut : AccountSyncState
    data object Syncing : AccountSyncState
    data object Synced : AccountSyncState
    data class Error(val message: String) : AccountSyncState
}

object AccountCloudDeletionPlan {
    val collections: List<String> = listOf(
        "profile",
        "myList",
        "favorites",
        "recent",
        "progress",
        "settings",
    )
}

data class RecentMediaEntry(
    val media: Media,
    val lastPlayedAtMillis: Long,
)

data class LibrarySnapshot(
    val myList: List<Media> = emptyList(),
    val favorites: List<Media> = emptyList(),
    val recent: List<RecentMediaEntry> = emptyList(),
)

data class AccountSettingsSnapshot(
    val generalProvider: String,
    val ramoflixUrl: String,
    val moviepireUrl: String,
    val dorabyUrl: String,
    val askAliflixEnabled: Boolean,
    val recommendationAiModel: String,
    val updatedAtMillis: Long,
    val hasExplicitLocalValues: Boolean = false,
)

data class AccountLocalSnapshot(
    val library: LibrarySnapshot,
    val playbackProgress: List<PlaybackProgress> = emptyList(),
    val settings: AccountSettingsSnapshot,
)

data class AccountScopeTransition(
    val scopeToSave: String,
    val scopeToRestore: String,
)

object AccountMergePolicy {
    const val MAX_RECENT = 30
    const val GUEST_SCOPE = "guest"

    fun mergeLibrary(local: LibrarySnapshot, cloud: LibrarySnapshot): LibrarySnapshot =
        LibrarySnapshot(
            myList = mergeMedia(local.myList, cloud.myList),
            favorites = mergeMedia(local.favorites, cloud.favorites),
            recent = mergeRecent(local.recent, cloud.recent),
        )

    fun mergeMedia(local: List<Media>, cloud: List<Media>): List<Media> {
        val merged = linkedMapOf<String, Media>()
        (local + cloud).forEach { candidate ->
            val current = merged[candidate.key]
            merged[candidate.key] = if (current == null) {
                candidate
            } else {
                richerMedia(current, candidate)
            }
        }
        return merged.values.toList()
    }

    fun mergeRecent(
        local: List<RecentMediaEntry>,
        cloud: List<RecentMediaEntry>,
        maximum: Int = MAX_RECENT,
    ): List<RecentMediaEntry> {
        val merged = linkedMapOf<String, RecentMediaEntry>()
        (local + cloud).forEach { candidate ->
            val current = merged[candidate.media.key]
            merged[candidate.media.key] = when {
                current == null -> candidate
                candidate.lastPlayedAtMillis > current.lastPlayedAtMillis ->
                    candidate.copy(media = richerMedia(current.media, candidate.media))
                candidate.lastPlayedAtMillis < current.lastPlayedAtMillis ->
                    current.copy(media = richerMedia(current.media, candidate.media))
                else -> current.copy(media = richerMedia(current.media, candidate.media))
            }
        }
        return merged.values
            .sortedWith(
                compareByDescending<RecentMediaEntry> { it.lastPlayedAtMillis }
                    .thenBy { it.media.key },
            )
            .take(maximum.coerceAtLeast(0))
    }

    fun resolveSettings(
        local: AccountSettingsSnapshot,
        cloud: AccountSettingsSnapshot?,
    ): AccountSettingsSnapshot {
        if (cloud == null) return local
        if (local.updatedAtMillis > cloud.updatedAtMillis) return local
        if (cloud.updatedAtMillis > local.updatedAtMillis) {
            return if (local.updatedAtMillis == 0L && local.hasExplicitLocalValues) local else cloud
        }
        return if (local.hasExplicitLocalValues && local != cloud) local else cloud
    }

    fun scopeTransition(
        activeScope: String,
        targetUid: String?,
        existingUserScopes: Set<String>,
    ): AccountScopeTransition {
        val targetScope = targetUid?.let(::userScope) ?: GUEST_SCOPE
        val restoreScope = when {
            targetScope == GUEST_SCOPE -> GUEST_SCOPE
            targetScope in existingUserScopes -> targetScope
            else -> GUEST_SCOPE
        }
        return AccountScopeTransition(
            scopeToSave = activeScope,
            scopeToRestore = restoreScope,
        )
    }

    fun userScope(uid: String): String = "user:$uid"

    private fun richerMedia(first: Media, second: Media): Media =
        if (mediaRichness(second) > mediaRichness(first)) second else first

    private fun mediaRichness(media: Media): Int = listOf(
        media.overview,
        media.posterPath,
        media.backdropPath,
        media.year,
        media.imdbId,
        media.status,
        media.originalLanguage,
        media.runtime,
    ).count { !it.isNullOrBlank() } +
        media.genres.size + media.cast.size + media.creators.size + media.omdbGenres.size
}
