package com.aliflix.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileEpisodeParsingTest {
    @Test fun returnsPlayableEpisodeRowsWithoutWaitingForExternalRatings() {
        val episodes = parseMobileEpisodes(JSONObject("""{"seasonNumber":3,"episodes":[
          {"seasonNumber":3,"number":2,"title":"Second","overview":"Story","stillPath":null,"runtime":48},
          {"seasonNumber":2,"number":1,"title":"Wrong season"},
          {"seasonNumber":3,"number":1,"title":"First"}
        ]}"""))
        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals("48 min", episodes.last().runtime)
        assertNull(episodes.last().stillPath)
        assertNull(episodes.first().imdbRating)
    }
}
