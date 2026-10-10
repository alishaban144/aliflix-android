package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Narrative concepts normalize synonymous story descriptions; no lexical-overlap score. */
internal object StoryConcepts {
    private val concepts = linkedMapOf(
        "time" to listOf("time travel", "time loop", "reliv", "repeat the same day", "temporal", "past and future", "alter the past"),
        "identity" to listOf("identity", "amnesia", "memory loss", "memories", "remember who", "split personality", "doppelganger"),
        "dream" to listOf("dream", "subconscious", "lucid", "sleeping mind", "nightmare"),
        "simulation" to listOf("simulation", "virtual reality", "simulated", "artificial world", "computer generated", "reality is an illusion"),
        "space" to listOf("space travel", "astronaut", "interstellar", "space exploration", "distant planet", "spaceship", "galaxy", "wormhole"),
        "ai" to listOf("artificial intelligence", "android", "sentient machine", "humanoid robot", "conscious machine"),
        "survival" to listOf("survival", "survive", "stranded", "castaway", "shipwreck", "wilderness", "fight for their lives"),
        "isolation" to listOf("isolation", "isolated", "remote hotel", "alone in", "secluded", "snowbound", "cut off"),
        "haunting" to listOf("haunted", "haunting", "ghost", "supernatural presence", "possession", "possessed", "evil spirit", "paranormal"),
        "investigation" to listOf("investigation", "investigat", "detective", "solve a murder", "unsolved", "serial killer", "homicide"),
        "heist" to listOf("heist", "robbery", "bank robber", "steal", "con artist", "conman", "swindle", "casino robbery"),
        "organized crime" to listOf("mafia", "mobster", "organized crime", "drug cartel", "gangster", "crime family"),
        "justice" to listOf("courtroom", "trial", "wrongly accused", "wrongfully", "lawyer", "jury", "death row"),
        "escape" to listOf("prison", "imprison", "captivity", "hostage", "incarcerat", "escape from"),
        "revenge" to listOf("revenge", "vengeance", "avenge", "retaliation"),
        "coming of age" to listOf("coming of age", "adolescen", "teenage", "growing up", "high school", "schoolboy", "schoolgirl"),
        "romance" to listOf("fall in love", "falls in love", "romantic relationship", "love affair", "star crossed", "lovers", "courtship"),
        "grief" to listOf("grief", "grieving", "bereavement", "loss of a child", "death of his wife", "death of her husband", "mourning"),
        "family bonds" to listOf("estranged", "reunite", "reconcile", "parenthood", "father son", "mother daughter", "family reunion"),
        "war" to listOf("battlefield", "soldier", "warfare", "world war", "military conflict", "combat"),
        "oppression" to listOf("totalitarian", "dystopia", "surveillance", "authoritarian", "dictatorship", "resistance movement"),
        "politics" to listOf("political corruption", "political conspiracy", "election", "politician", "whistleblower"),
        "quest" to listOf("quest", "enchanted", "magic", "wizard", "dragon", "sorcerer", "magical realm"),
        "creature" to listOf("vampire", "zombie", "werewolf", "monster", "alien invasion", "extraterrestrial"),
        "art" to listOf("musician", "dancer", "artist", "performer", "music career", "concert", "composer"),
        "obsession" to listOf("obsession", "obsessed", "perfection", "ambition", "psychological breakdown", "descends into madness"),
    )
    private val patterns = concepts.mapValues { (_, phrases) ->
        phrases.map { phrase -> Regex("(?<![a-z])" + Regex.escape(phrase)) }
    }
    private val conArtist = Regex("\\bcon artists?\\b")
    fun extract(text: String): Set<String> {
        val normalized = normalize(text)
        val artisticText = conArtist.replace(normalized, "")
        return patterns.filter { (concept, values) ->
            values.any { it.containsMatchIn(if (concept == "art") artisticText else normalized) }
        }.keys
    }
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

object MobileTasteModel {
    fun diverseSeeds(items: List<Media>, limit: Int): List<Media> {
        fun facets(item: Media) = StoryConcepts.extract(item.overview + " " + item.keywords.joinToString(" ") { it.name })
            .map { "story:$it" }.toSet() + item.genres.map { "genre:${StoryConcepts.normalize(it)}" }
        val remaining = items.distinctBy(Media::key).take(100).toMutableList()
        val itemFacets = remaining.associateWith(::facets)
        val selected = mutableListOf<Media>()
        val covered = hashSetOf<String>()
        while (remaining.isNotEmpty() && selected.size < limit) {
            val next = remaining.maxBy { item -> itemFacets.getValue(item).sumOf { facet ->
                if (facet in covered) 0 else if (facet.startsWith("story:")) 3 else 1
            } }
            selected += next
            covered += itemFacets.getValue(next)
            remaining.remove(next)
        }
        return selected
    }
    private val enriched = MutableStateFlow<Map<String, Media>>(emptyMap())
    val metadata = enriched.asStateFlow()
    private var viewingHistory: List<Media> = emptyList()
    fun setHistory(history: List<Media>) { viewingHistory = history.distinctBy(Media::key).take(20) }
    fun history(): List<Media> = viewingHistory
    @Synchronized internal fun clear() { enriched.value = emptyMap(); viewingHistory = emptyList(); cache.clear() }
    private data class Features(val genres: Set<String>, val keywords: Set<Int>, val concepts: Set<String>,
        val directors: Set<Int>, val cast: Set<String>)
    private val cache = object : LinkedHashMap<Media, Features>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Media, Features>?) = size > 512
    }

    @Synchronized fun remember(items: List<Media>) {
        val updated = LinkedHashMap(enriched.value)
        items.forEach { item ->
            val previous = updated[item.key]
            updated[item.key] = if (previous == null) item else item.copy(
                overview = item.overview.ifBlank { previous.overview },
                genres = item.genres.ifEmpty { previous.genres },
                keywords = item.keywords.ifEmpty { previous.keywords },
                creators = item.creators.ifEmpty { previous.creators },
                cast = item.cast.ifEmpty { previous.cast },
                castPeople = item.castPeople.ifEmpty { previous.castPeople },
                omdbFullPlot = item.omdbFullPlot?.takeIf { it.isNotBlank() } ?: previous.omdbFullPlot,
                omdbGenres = item.omdbGenres.ifEmpty { previous.omdbGenres },
            )
        }
        while (updated.size > 256) updated.remove(updated.keys.first())
        enriched.value = updated
    }

    private fun enriched(item: Media) = enriched.value[item.key] ?: item
    @Synchronized private fun features(item: Media): Features = cache.getOrPut(item) {
        Features(
            (item.genres + item.omdbGenres).map(StoryConcepts::normalize).toSet(),
            item.keywords.filter { it.id > 0 && StoryConcepts.normalize(it.name) !in broadKeywords }.map { it.id }.toSet(),
            StoryConcepts.extract(item.overview + " " + item.omdbFullPlot.orEmpty() + " " + item.keywords.joinToString(" ") { it.name }),
            item.creators.filter { it.tmdbId > 0 && (it.role == null || it.role.equals("Director", true) || it.role.equals("Creator", true)) }
                .map { it.tmdbId }.toSet(),
            (item.castPeople.take(6).map { it.name } + item.cast.take(6)).map(StoryConcepts::normalize).filter { it.isNotEmpty() }.toSet(),
        )
    }
    private data class Affinity(val score: Int, val evidence: Set<String>)
    private fun compare(item: Media, anchor: Media): Affinity? {
        val a = features(enriched(item)); val b = features(enriched(anchor))
        val sharedConcepts = a.concepts intersect b.concepts
        val sharedKeywords = a.keywords intersect b.keywords
        val sharedDirectors = a.directors intersect b.directors
        val sharedCast = a.cast intersect b.cast
        val genreOverlap = a.genres intersect b.genres
        // Genre/popularity/cast alone never constitute a recommendation.
        if (sharedConcepts.isEmpty() && sharedKeywords.isEmpty() && sharedDirectors.isEmpty()) return null
        val storyRatio = if (sharedConcepts.isEmpty()) 0.0 else
            sharedConcepts.size.toDouble() / maxOf(2, minOf(a.concepts.size, b.concepts.size))
        val keywordRatio = if (sharedKeywords.isEmpty()) 0.0 else
            sharedKeywords.size.toDouble() / maxOf(3, minOf(a.keywords.size, b.keywords.size))
        val story = (storyRatio * 48).coerceAtMost(48.0)
        val keywords = (keywordRatio * 30).coerceAtMost(30.0)
        val director = if (sharedDirectors.isNotEmpty()) 18 else 0
        val cast = if (sharedCast.size >= 2) 4 else 0
        val genre = if (genreOverlap.isNotEmpty()) 5 else 0
        // Missing channels add no affinity and never inflate the denominator.
        val score = (story + keywords + director + cast + genre).roundToInt().coerceIn(0, 95)
        val evidence = sharedConcepts + sharedKeywords.map { "keyword:$it" } +
            sharedDirectors.map { "director:$it" }
        return Affinity(score, evidence)
    }

    fun match(item: Media, likes: List<Media>, history: List<Media> = emptyList()): PersonalMatch? {
        if (likes.any { it.key == item.key }) return PersonalMatch(100, PersonalMatchTier.LIKED)
        val explicit = likes.distinctBy(Media::key).take(100).mapNotNull { compare(item, it) }
        val implicit = history.distinctBy(Media::key).filter { h -> likes.none { it.key == h.key } }.take(20)
            .mapNotNull { compare(item, it)?.let { a -> a.copy(score = (a.score * .45).roundToInt()) } }
        val best = (explicit + implicit).maxByOrNull { it.score } ?: return null
        val tier = when {
            best.score >= 65 && best.evidence.size >= 3 -> PersonalMatchTier.STRONG
            best.score >= 40 && best.evidence.size >= 2 -> PersonalMatchTier.GOOD
            best.score >= 18 -> PersonalMatchTier.RELATED
            else -> return null
        }
        return PersonalMatch(best.score, tier, best.evidence)
    }
    private val broadKeywords = setOf("based on novel or book", "based on a true story", "sequel", "remake",
        "friendship", "love", "family", "drama", "comedy", "action", "movie", "woman director", "duringcreditsstinger")
}
