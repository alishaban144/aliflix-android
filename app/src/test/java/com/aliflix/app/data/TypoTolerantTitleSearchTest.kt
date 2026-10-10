package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class TypoTolerantTitleSearchTest {
    private fun movie(id: Int, title: String) = Media(id, MediaType.MOVIE, title)

    @Test fun missingTitleIsRetrievedByFallbackAndCachedAcrossEntryPoints() = runTest {
        val shining = movie(694, "The Shining")
        val calls = mutableListOf<String>()
        val search = TypoTolerantTitleSearch { query ->
            calls += query
            if (query == "shini") listOf(movie(9, "Shining Girls"), shining) else emptyList()
        }
        val result = search.search("The Shininf")
        assertEquals(listOf(shining), result.items)
        assertEquals("The Shining", result.correctedTitle)
        assertEquals(listOf("The Shininf", "shini"), calls)
        assertEquals(result, search.search("the shininf"))
        assertEquals(2, calls.size)
    }

    @Test fun correctTitlesAlwaysLeadAndIssueNoFallbackRequests() = runTest {
        val calls = mutableListOf<String>()
        val exact = movie(1, "The Shining")
        val search = TypoTolerantTitleSearch { calls += it; listOf(movie(2, "Shining Girls"), exact) }
        assertEquals(exact, search.search("The Shining").items.first())
        assertNull(search.search("The Shining").correctedTitle)
        assertEquals(listOf("The Shining"), calls)
    }

    @Test fun substitutionsInsertionsDeletionsAndTranspositionsAreAcceptedWithinBudget() {
        listOf("The Shininf", "The Shinnig", "The Shinining", "The Shinng").forEach {
            assertTrue(it, TypoTolerantTitleSearch.isCorrection(it, "The Shining"))
        }
        assertTrue(TypoTolerantTitleSearch.isCorrection("Interstllar", "Interstellar"))
        assertTrue(TypoTolerantTitleSearch.isCorrection("Interstlelar", "Interstellar"))
        assertTrue(TypoTolerantTitleSearch.isCorrection("The Shawshnk Redemptoin", "The Shawshank Redemption"))
        assertEquals(1, TypoTolerantTitleSearch.distance("shinnig", "shining"))
        assertEquals(2, TypoTolerantTitleSearch.distance("ca", "abc"))
    }

    @Test fun unrelatedAndShortTitlesNeverBecomeCorrections() = runTest {
        assertFalse(TypoTolerantTitleSearch.isCorrection("It", "Up"))
        assertFalse(TypoTolerantTitleSearch.isCorrection("Alien", "Aliens"))
        assertFalse(TypoTolerantTitleSearch.isCorrection("The Shininf", "Shining Girls"))
        assertFalse(TypoTolerantTitleSearch.isCorrection("Batman", "Badman Returns"))
        val calls = mutableListOf<String>()
        val search = TypoTolerantTitleSearch { calls += it; if (calls.size > 1) listOf(movie(1, "Unrelated")) else emptyList() }
        assertTrue(search.search("Xylophonist").items.isEmpty())
        assertTrue(calls.size <= 4)
    }

    @Test fun concurrentDiscoverAndSimilarShareOneRecovery() = runTest {
        var count = 0
        val search = TypoTolerantTitleSearch { query ->
            count++; delay(10)
            if (query == "shini") listOf(movie(1, "The Shining")) else emptyList()
        }
        val a = async { search.search("The Shininf") }
        val b = async { search.search("The Shininf") }
        assertEquals(a.await(), b.await())
        assertEquals(2, count)
    }

    @Test fun equallyCloseDifferentTitlesDoNotTriggerAmbiguousCorrection() = runTest {
        val search = TypoTolerantTitleSearch { query ->
            if (query == "The Shininf") emptyList() else listOf(movie(1, "The Shining"), movie(2, "The Shininx"))
        }
        val result = search.search("The Shininf")
        assertNull(result.correctedTitle)
        assertTrue(result.items.isEmpty())
    }
    @Test fun discoverTypeFiltersCannotPoisonSimilarSearchCache() = runTest {
        val film = movie(694, "The Shining")
        val series = Media(1, MediaType.TV, "The Shining")
        var requests = 0
        val search = TypoTolerantTitleSearch { query ->
            requests++
            if (query == "shini") listOf(film, series) else emptyList()
        }
        assertEquals(listOf(film), search.search("The Shininf", emptyList(), "Movies").items)
        assertEquals(listOf(film, series), search.search("The Shininf").items)
        assertEquals(2, requests)
    }

    @Test fun twoDamagedTokensUseKnownMetadataForActualRetrieval() = runTest {
        val intended = movie(278, "The Shawshank Redemption")
        val calls = mutableListOf<String>()
        val search = TypoTolerantTitleSearch(knownTitles = { listOf(intended) }) { query ->
            calls += query; if (query == intended.title) listOf(intended) else emptyList()
        }
        assertEquals(listOf(intended), search.search("The Shawshnk Redemptoin").items)
        assertEquals(listOf("The Shawshnk Redemptoin", intended.title), calls)
    }
    @Test fun damagedArticlesAndWordSeparatorsAreCharacterErrors() = runTest {
        val intended = movie(694, "The Shining")
        val search = TypoTolerantTitleSearch { query -> if (query == "shining") listOf(intended) else emptyList() }
        assertEquals(listOf(intended), search.search("Ths Shining").items)
        assertTrue(TypoTolerantTitleSearch.isCorrection("TheShining", intended.title))
    }

    @Test fun correctionDoesNotMixInDifferentTitlesWithWeakerEditMatches() = runTest {
        val intended = movie(694, "The Shining")
        val search = TypoTolerantTitleSearch { query ->
            if (query == "The Shininf") emptyList() else listOf(movie(2, "The Shinnng"), intended)
        }
        assertEquals(listOf(intended), search.search("The Shininf").items)
    }

    @Test fun damagedFirstCharacterIsRetrievedUsingAnIntactSuffix() = runTest {
        val intended = movie(694, "The Shining")
        val search = TypoTolerantTitleSearch { query -> if (query == "ning") listOf(intended) else emptyList() }
        assertEquals(listOf(intended), search.search("Xhining").items)
    }
    @Test fun shortRecognizableTitleCanRecoverFromKnownMetadataWithoutBroadMatches() = runTest {
        val intended = movie(578, "Jaws")
        val search = TypoTolerantTitleSearch(knownTitles = { listOf(intended) }) { query ->
            if (query == "Jaws") listOf(intended) else emptyList()
        }
        assertEquals(listOf(intended), search.search("Jwas").items)
        assertFalse(TypoTolerantTitleSearch.isCorrection("It", "Up"))
    }

    @Test fun deletionAndSubstitutionKeepTheTwoErrorBudgetForTheIntendedTitle() = runTest {
        val intended = movie(694, "The Shining")
        assertTrue(TypoTolerantTitleSearch.isCorrection("The Shninf", intended.title))
        val search = TypoTolerantTitleSearch(knownTitles = { listOf(intended) }) { query ->
            if (query == intended.title) listOf(intended) else emptyList()
        }
        assertEquals(listOf(intended), search.search("The Shninf").items)
    }
    @Test fun normalPartialTitlesRemainResponsiveWithoutFallbackOrCorrectionLabels() = runTest {
        val intended = movie(157336, "Interstellar")
        var requests = 0
        val search = TypoTolerantTitleSearch { requests++; listOf(intended) }
        val result = search.search("Interst")
        assertEquals(listOf(intended), result.items)
        assertEquals(1, requests)
        assertNull(result.correctedTitle)
        assertNull(TypoTolerantTitleSearch.correctionLabel("Interst", result.items))
    }

    @Test fun wrongMediaTypeCannotWinOrSuppressTheIntendedCorrection() = runTest {
        val film = movie(694, "The Shining")
        val closerSeries = Media(2, MediaType.TV, "The Shninf")
        val calls = mutableListOf<String>()
        val search = TypoTolerantTitleSearch(knownTitles = { listOf(closerSeries, film) }) { query ->
            calls += query
            if (query == film.title) listOf(closerSeries, film) else emptyList()
        }
        val result = search.search("The Shninf", emptyList(), "Movies")
        assertEquals(listOf(film), result.items)
        assertEquals(film.title, result.correctedTitle)
        assertEquals(listOf(film.title), calls)
    }

}
