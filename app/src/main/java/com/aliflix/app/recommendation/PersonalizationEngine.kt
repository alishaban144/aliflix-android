package com.aliflix.app.recommendation

import com.aliflix.app.model.Media

/** A bounded content-affinity index, not a calibrated probability of enjoyment. */
enum class PersonalMatchTier(val label: String) {
    LIKED("Liked"), STRONG("Strong match"), GOOD("Good match"), RELATED("Related"),
}

data class PersonalMatch(
    val score: Int,
    val tier: PersonalMatchTier? = null,
    val evidence: Set<String> = emptySet(),
) {
    val label: String get() = tier?.label.orEmpty()
}

/** Presents the active mobile taste model on Home, Details, and library cards. */
object PersonalizationEngine {
    fun match(item: Media, likes: List<Media>): PersonalMatch? =
        MobileTasteModel.match(item, likes, MobileTasteModel.history())
}
