package com.aliflix.app.data

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotSearchRankerTest {
    @Test
    fun literalDetailsPreserveCanonicalTitleResolutionEvidence() {
        val description =
            "a movie where a thief enters other people's dreams to steal secrets"
        val inception = Media(
            id = 27205,
            type = MediaType.MOVIE,
            title = "Inception",
            overview = "A thief enters shared dreams and steals secrets from the subconscious.",
        )
        val paprika = Media(
            id = 4977,
            type = MediaType.MOVIE,
            title = "Paprika",
            overview = "A psychologist uses a device to enter patients' dreams.",
        )

        val inceptionLiteral = PlotSearchRanker.literalTextRelevanceScore(
            description,
            "${inception.title} ${inception.overview}",
        )
        val paprikaLiteral = PlotSearchRanker.literalTextRelevanceScore(
            description,
            "${paprika.title} ${paprika.overview}",
        )
        assertTrue(inceptionLiteral > paprikaLiteral)
    }
}
