package com.aliflix.app.account

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class AccountMergePolicyTest {
    @Test
    fun firstSignInMergesMyListWithoutErasingLocalTitles() {
        val localOnly = media(1, "Local only")
        val sharedLocal = media(2, "Shared", overview = "Local metadata")
        val sharedCloud = media(2, "Shared", overview = "Richer cloud metadata", genres = listOf("Crime"))
        val cloudOnly = media(3, "Cloud only")

        val merged = AccountMergePolicy.mergeLibrary(
            local = LibrarySnapshot(myList = listOf(localOnly, sharedLocal)),
            cloud = LibrarySnapshot(myList = listOf(sharedCloud, cloudOnly)),
        )

        assertEquals(listOf("movie:1", "movie:2", "movie:3"), merged.myList.map(Media::key))
        assertEquals("Richer cloud metadata", merged.myList[1].overview)
        assertEquals(listOf("Crime"), merged.myList[1].genres)
    }

    @Test
    fun favoritesDeduplicateByFullMediaKeyWithoutCollidingMovieAndTvIds() {
        val movie = media(9, "Movie")
        val duplicateMovie = media(9, "Movie", overview = "More complete")
        val tv = media(9, "Series", type = MediaType.TV)

        val merged = AccountMergePolicy.mergeLibrary(
            local = LibrarySnapshot(favorites = listOf(movie, tv)),
            cloud = LibrarySnapshot(favorites = listOf(duplicateMovie)),
        )

        assertEquals(listOf("movie:9", "tv:9"), merged.favorites.map(Media::key))
        assertEquals("More complete", merged.favorites.first().overview)
    }

    @Test
    fun recentHistoryUsesNewestTimestampAndPreservesMaximumLimit() {
        val local = (1..25).map { id ->
            RecentMediaEntry(media(id, "Local $id"), 2_000L - id)
        }
        val cloud = (20..45).map { id ->
            RecentMediaEntry(media(id, "Cloud $id"), 4_000L - id)
        }

        val merged = AccountMergePolicy.mergeRecent(local, cloud)

        assertEquals(AccountMergePolicy.MAX_RECENT, merged.size)
        assertEquals((20..45).map { "movie:$it" }, merged.take(26).map { it.media.key })
        assertEquals(3_980L, merged.first().lastPlayedAtMillis)
        assertEquals(merged.sortedByDescending(RecentMediaEntry::lastPlayedAtMillis), merged)
    }

    @Test
    fun recentHistoryKeepsTheNewestTimeAndTheRichestAvailableMediaMetadata() {
        val olderRich = RecentMediaEntry(
            media(77, "Mystery", overview = "Complete overview", genres = listOf("Mystery")),
            1_000L,
        )
        val newerSparse = RecentMediaEntry(media(77, "Mystery"), 2_000L)

        val merged = AccountMergePolicy.mergeRecent(
            local = listOf(olderRich),
            cloud = listOf(newerSparse),
        ).single()

        assertEquals(2_000L, merged.lastPlayedAtMillis)
        assertEquals("Complete overview", merged.media.overview)
        assertEquals(listOf("Mystery"), merged.media.genres)
    }

    @Test
    fun newerCloudSettingsWinButLegacyExplicitLocalPreferencesAreProtected() {
        val cloud = settings(
            provider = "RAMOFLIX",
            updatedAt = 9_000L,
            subtitleLanguage = "AR",
            autoSubtitles = true,
        )
        val ordinaryLocal = settings(provider = "MOVIEPIRE", updatedAt = 8_000L)
        val resolved = AccountMergePolicy.resolveSettings(ordinaryLocal, cloud)
        assertSame(cloud, resolved)
        assertEquals("AR", resolved.preferredSubtitleLanguage)
        assertEquals(true, resolved.autoDisplaySubtitles)

        val legacyExplicit = settings(
            provider = "DORABY",
            updatedAt = 0L,
            explicit = true,
        )
        assertSame(legacyExplicit, AccountMergePolicy.resolveSettings(legacyExplicit, cloud))
    }

    @Test
    fun switchingAccountsNeverRestoresThePreviousUsersScope() {
        val fromAtoNewB = AccountMergePolicy.scopeTransition(
            activeScope = AccountMergePolicy.userScope("user-a"),
            targetUid = "user-b",
            existingUserScopes = setOf(AccountMergePolicy.userScope("user-a")),
        )
        assertEquals(AccountMergePolicy.userScope("user-a"), fromAtoNewB.scopeToSave)
        assertEquals(AccountMergePolicy.GUEST_SCOPE, fromAtoNewB.scopeToRestore)
        assertFalse(fromAtoNewB.scopeToRestore.contains("user-a"))

        val returningB = AccountMergePolicy.scopeTransition(
            activeScope = AccountMergePolicy.GUEST_SCOPE,
            targetUid = "user-b",
            existingUserScopes = setOf(AccountMergePolicy.userScope("user-b")),
        )
        assertEquals(AccountMergePolicy.userScope("user-b"), returningB.scopeToRestore)
    }

    @Test
    fun deletionTargetsOnlyTheDocumentedAccountCollections() {
        assertEquals(
            listOf("profile", "myList", "favorites", "recent", "progress", "settings"),
            AccountCloudDeletionPlan.collections,
        )
    }

    private fun media(
        id: Int,
        title: String,
        type: MediaType = MediaType.MOVIE,
        overview: String = "",
        genres: List<String> = emptyList(),
    ) = Media(
        id = id,
        type = type,
        title = title,
        overview = overview,
        genres = genres,
    )

    private fun settings(
        provider: String,
        updatedAt: Long,
        explicit: Boolean = false,
        subtitleLanguage: String = "EN",
        autoSubtitles: Boolean = false,
    ) = AccountSettingsSnapshot(
        generalProvider = provider,
        ramoflixUrl = "https://ramoflix.example/",
        moviepireUrl = "https://moviepire.example/",
        dorabyUrl = "https://doraby.example/",
        askAliflixEnabled = true,
        recommendationAiModel = "groq-qwen-3.8-27b",
        updatedAtMillis = updatedAt,
        preferredSubtitleLanguage = subtitleLanguage,
        autoDisplaySubtitles = autoSubtitles,
        hasExplicitLocalValues = explicit,
    )
}
