package com.aliflix.app.recommendation

import com.aliflix.app.model.Media

/** Popularity breaks ties within a relevant neighborhood; never pads with unrelated hits. */
object TastePicks {
    fun rank(candidates: List<Media>, anchors: List<Media>, excluded: Set<String>): List<Media> =
        rankMobile(candidates, anchors, excluded)

    fun rankMobile(candidates: List<Media>, likes: List<Media>, excluded: Set<String>, history: List<Media> = emptyList()): List<Media> =
        candidates.distinctBy(Media::key).filter { it.key !in excluded && it.posterPath != null }
            .mapNotNull { item -> MobileTasteModel.match(item, likes, history)?.let { item to it } }
            .sortedWith(compareByDescending<Pair<Media, PersonalMatch>> { it.second.score }
                .thenBy { it.first.key })
            .take(20).map { it.first }
}
