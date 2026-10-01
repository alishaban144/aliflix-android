package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlin.math.ln

/** Popularity breaks ties within a relevant neighborhood; never pads with unrelated hits. */
object TastePicks {
    private val broadGenres = setOf("drama", "comedy", "action")
    fun rank(candidates: List<Media>, anchors: List<Media>, excluded: Set<String>): List<Media> {
        if (anchors.isEmpty()) return emptyList()
        return candidates.distinctBy(Media::key).filter { item ->
            item.key !in excluded && item.posterPath != null && item.rating >= 6.0 &&
                (item.tmdbVoteCount ?: 0) >= (if (item.type == MediaType.MOVIE) 200 else 100) &&
                anchors.any { anchor ->
                    val sharedGenres = item.genres.map { it.lowercase() }.toSet()
                        .intersect(anchor.genres.map { it.lowercase() }.toSet())
                    val keyword = item.keywords.any { word -> anchor.keywords.any { it.id == word.id } }
                    val creator = item.creators.any { person -> anchor.creators.any { it.tmdbId == person.tmdbId } }
                    sharedGenres.size >= 2 || sharedGenres.any { it !in broadGenres } || keyword || creator
                }
        }.sortedByDescending { item ->
            (PersonalizationEngine.match(item, anchors)?.score ?: 0) * .7 +
                ln(1.0 + (item.tmdbVoteCount ?: 0)) * 2.5 + item.rating
        }.take(20)
    }
}
