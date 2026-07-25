package com.aliflix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RamoflixConfigTest {
    @Test
    fun defaultUrlBuildsRamoflixSearch() {
        assertEquals(
            "https://ramoflix.net/?s=Inception",
            RamoflixConfig().buildWatchUrl("Inception"),
        )
    }

    @Test
    fun editedUrlIsUsedAndTheTitleIsEncoded() {
        val config = RamoflixConfig("https://ramo.example/")

        assertEquals(
            "https://ramo.example/?s=Dune%3A+Part+Two",
            config.buildWatchUrl("Dune: Part Two"),
        )
        assertEquals("ramo.example", config.cleanDomain)
    }

    @Test
    fun normalizesEditableBaseUrl() {
        assertEquals(
            "https://mirror.ramoflix.example/",
            RamoflixConfig.normalizeBaseUrl(" mirror.ramoflix.example "),
        )
        assertEquals(
            "https://mirror.ramoflix.example/",
            RamoflixConfig.normalizeBaseUrl("https://mirror.ramoflix.example///"),
        )
        assertNull(RamoflixConfig.normalizeBaseUrl("   "))
        assertNull(RamoflixConfig.normalizeBaseUrl("http://mirror.ramoflix.example"))
        assertNull(RamoflixConfig.normalizeBaseUrl("https://"))
        assertNull(RamoflixConfig.normalizeBaseUrl("https://ramoflix.example/?source=other"))
        assertNull(RamoflixConfig.normalizeBaseUrl("https://ramoflix.example/#other"))
    }
}
