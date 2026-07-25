package com.aliflix.app.model

import com.aliflix.app.data.RamoflixConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaTest {
    @Test
    fun moviePlaybackUsesRamoflixTitleSearch() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        assertEquals(
            "https://ramoflix.net/?s=Inception",
            PlaybackSelection(item).watchUrl,
        )
    }

    @Test
    fun tvPlaybackUsesRamoflixTitleSearch() {
        val item = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        assertEquals(
            "https://ramoflix.net/?s=Stranger+Things",
            PlaybackSelection(item).watchUrl,
        )
    }

    @Test
    fun tvPlaybackSelectionTargetsExactSeasonAndEpisode() {
        val item = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val selection = PlaybackSelection(
            media = item,
            seasonNumber = 2,
            episodeNumber = 3,
        )

        assertEquals(
            "https://ramoflix.net/?s=Stranger+Things",
            selection.watchUrl,
        )
        assertEquals("tv:66732:s2:e3", selection.key)
    }

    @Test
    fun playbackSelectionKeyChangesWhenRamoflixUrlChanges() {
        val item = Media(id = 27205, type = MediaType.MOVIE, title = "Inception")
        val defaultSelection = PlaybackSelection(media = item)
        val editedSelection = PlaybackSelection(
            media = item,
            ramoflixConfig = RamoflixConfig("https://ramo-mirror.example/"),
        )

        assertNotEquals(defaultSelection.key, editedSelection.key)
    }

    @Test
    fun playbackSelectionUsesEditedRamoflixUrlForAnEpisode() {
        val item = Media(id = 66732, type = MediaType.TV, title = "Stranger Things")
        val selection = PlaybackSelection(
            media = item,
            seasonNumber = 2,
            episodeNumber = 3,
            ramoflixConfig = RamoflixConfig("https://ramo-mirror.example/"),
        )

        assertEquals(
            "https://ramo-mirror.example/?s=Stranger+Things",
            selection.watchUrl,
        )
        assertEquals(
            "tv:66732:s2:e3:ramoflix@https://ramo-mirror.example",
            selection.key,
        )
    }
}
