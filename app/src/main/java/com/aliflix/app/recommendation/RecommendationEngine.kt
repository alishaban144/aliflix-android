package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

object RecommendationQuestionSelector {
    const val MAX_QUESTIONS = 5
    private const val MIN_INFORMATION_GAIN = 0.15
    private const val MIN_METADATA_COVERAGE = 0.70

    fun nextQuestion(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
    ): RecommendationQuestion? {
        if (preferences.askedQuestionIds.size >= MAX_QUESTIONS || preferences.surpriseMe) {
            return null
        }
        if (
            preferences.explicitSignalCount == 0 &&
            RecommendationDimension.MOOD !in preferences.answeredDimensions
        ) {
            return moodQuestion()
        }

        val proposals = buildList {
            if (RecommendationDimension.VIEWING_CONTEXT !in preferences.answeredDimensions) {
                val contextRelevance = if (
                    preferences.moods.any {
                        it.value in setOf(
                            RecommendationMood.SCARY,
                            RecommendationMood.FUNNY,
                            RecommendationMood.ROMANTIC,
                        )
                    }
                ) {
                    0.58
                } else {
                    0.10
                }
                add(QuestionProposal(viewingContextQuestion(), contextRelevance, 1.0))
            }
            if (RecommendationDimension.CONTENT_TYPE !in preferences.answeredDimensions) {
                add(
                    distributionProposal(
                        question = contentTypeQuestion(),
                        candidates = candidates,
                        bucket = { it.media.type.routeName },
                    ),
                )
            }
            if (RecommendationDimension.RUNTIME !in preferences.answeredDimensions) {
                add(
                    distributionProposal(
                        question = runtimeQuestion(),
                        candidates = candidates,
                        valuePresent = { runtimeFor(it) != null },
                        bucket = { candidate ->
                            runtimeFor(candidate)?.let {
                                when {
                                    it < 90 -> "short"
                                    it <= 120 -> "medium"
                                    it <= 150 -> "long"
                                    else -> "epic"
                                }
                            }
                        },
                    ),
                )
            }
            if (
                RecommendationDimension.GENRE !in preferences.answeredDimensions &&
                preferences.includedGenres.isEmpty()
            ) {
                add(
                    distributionProposal(
                        question = genreQuestion(candidates),
                        candidates = candidates,
                        bucket = { it.media.genres.firstOrNull()?.lowercase() },
                    ),
                )
            }
            if (RecommendationDimension.ERA !in preferences.answeredDimensions) {
                add(
                    distributionProposal(
                        question = eraQuestion(),
                        candidates = candidates,
                        valuePresent = { it.media.year.take(4).toIntOrNull() != null },
                        bucket = {
                            it.media.year.take(4).toIntOrNull()?.let { year ->
                                "${year / 10 * 10}s"
                            }
                        },
                    ),
                )
            }
            if (RecommendationDimension.LANGUAGE !in preferences.answeredDimensions) {
                add(
                    distributionProposal(
                        question = languageQuestion(candidates),
                        candidates = candidates,
                        valuePresent = { !it.metadata.originalLanguage.isNullOrBlank() },
                        bucket = { it.metadata.originalLanguage?.lowercase() },
                    ),
                )
            }
            if (RecommendationDimension.QUALITY !in preferences.answeredDimensions) {
                add(
                    distributionProposal(
                        question = qualityQuestion(),
                        candidates = candidates,
                        valuePresent = {
                            it.media.imdbRating != null ||
                                it.media.rottenTomatoesRating != null ||
                                it.media.rating > 0.0
                        },
                        bucket = {
                            val quality = it.media.imdbRating ?: it.media.rating
                            when {
                                quality >= 8.0 -> "excellent"
                                quality >= 7.0 -> "strong"
                                else -> "open"
                            }
                        },
                    ),
                )
            }
            if (RecommendationDimension.FAMILIARITY !in preferences.answeredDimensions) {
                add(QuestionProposal(familiarityQuestion(), 0.16, 1.0))
            }
        }

        return proposals
            .asSequence()
            .filter { it.coverage >= MIN_METADATA_COVERAGE }
            .filter { it.gain >= MIN_INFORMATION_GAIN }
            .filterNot { it.question.id in preferences.askedQuestionIds }
            .maxByOrNull(QuestionProposal::gain)
            ?.question
    }

    fun progressMessage(preferences: RecommendationPreferences): String = when {
        preferences.askedQuestionIds.isEmpty() ->
            "A little context will make the matches much stronger."
        preferences.askedQuestionIds.size >= 3 ->
            "I have a good picture now. One useful detail may still help."
        else ->
            "The shortlist is taking shape."
    }

    internal fun informationGain(values: List<String>): Double {
        if (values.size < 2) return 0.0
        val groups = values.groupingBy(String::lowercase).eachCount()
        if (groups.size < 2) return 0.0
        val entropy = groups.values.sumOf { count ->
            val probability = count.toDouble() / values.size
            -probability * ln(probability)
        }
        return (entropy / ln(groups.size.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun distributionProposal(
        question: RecommendationQuestion,
        candidates: List<RecommendationCandidate>,
        valuePresent: (RecommendationCandidate) -> Boolean = { true },
        bucket: (RecommendationCandidate) -> String?,
    ): QuestionProposal {
        if (candidates.isEmpty()) return QuestionProposal(question, 0.0, 0.0)
        val covered = candidates.filter(valuePresent).mapNotNull(bucket)
        val coverage = covered.size.toDouble() / candidates.size
        return QuestionProposal(
            question = question,
            gain = informationGain(covered) * coverage,
            coverage = coverage,
        )
    }

    private fun runtimeFor(candidate: RecommendationCandidate): Int? =
        if (candidate.media.type == MediaType.TV) {
            candidate.metadata.averageEpisodeRuntimeMinutes
        } else {
            candidate.metadata.runtimeMinutes
        }

    private fun moodQuestion() = RecommendationQuestion(
        id = "mood",
        dimension = RecommendationDimension.MOOD,
        text = "What kind of mood should it have?",
        type = RecommendationQuestionType.MULTI_SELECT,
        options = RecommendationMood.entries.take(10).map {
            RecommendationOption(it.name.lowercase(), it.label, it.name)
        } + RecommendationOption("any", "Surprise me", "any"),
    )

    private fun contentTypeQuestion() = RecommendationQuestion(
        id = "content_type",
        dimension = RecommendationDimension.CONTENT_TYPE,
        text = "Movie or series?",
        type = RecommendationQuestionType.SINGLE_SELECT,
        options = listOf(
            RecommendationOption("movie", "Movie", RecommendationContentType.MOVIE.name),
            RecommendationOption("tv", "Series", RecommendationContentType.TV.name),
        ),
    )

    private fun viewingContextQuestion() = RecommendationQuestion(
        id = "viewing_context",
        dimension = RecommendationDimension.VIEWING_CONTEXT,
        text = "Who's watching?",
        type = RecommendationQuestionType.SINGLE_SELECT,
        options = listOf(
            ViewingContext.ALONE,
            ViewingContext.PARTNER,
            ViewingContext.FRIENDS,
            ViewingContext.FAMILY,
        ).map { RecommendationOption(it.name.lowercase(), it.label, it.name) } +
            RecommendationOption("any", "Doesn't matter", "any"),
    )

    private fun runtimeQuestion() = RecommendationQuestion(
        id = "runtime",
        dimension = RecommendationDimension.RUNTIME,
        text = "How much time do you have?",
        type = RecommendationQuestionType.SINGLE_SELECT,
        options = listOf(
            RecommendationOption("under90", "Under 90 min", "max:89"),
            RecommendationOption("90to120", "90–120 min", "range:90:120"),
            RecommendationOption("120to150", "120–150 min", "range:120:150"),
            RecommendationOption("over150", "150+ min", "min:150"),
            RecommendationOption("any", "Doesn't matter", "any"),
        ),
    )

    private fun genreQuestion(candidates: List<RecommendationCandidate>): RecommendationQuestion {
        val available = candidates
            .flatMap { it.media.genres }
            .groupingBy(String::trim)
            .eachCount()
            .entries
            .sortedByDescending(Map.Entry<String, Int>::value)
            .map(Map.Entry<String, Int>::key)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .take(7)
            .ifEmpty { listOf("Comedy", "Drama", "Thriller", "Action", "Horror") }
        return RecommendationQuestion(
            id = "genre",
            dimension = RecommendationDimension.GENRE,
            text = "Any genres you want to lean toward?",
            type = RecommendationQuestionType.MULTI_SELECT,
            options = available.map {
                RecommendationOption(it.lowercase().replace(' ', '_'), it, it)
            } + RecommendationOption("any", "Doesn't matter", "any"),
        )
    }

    private fun eraQuestion() = RecommendationQuestion(
        id = "era",
        dimension = RecommendationDimension.ERA,
        text = "Any era preference?",
        type = RecommendationQuestionType.SINGLE_SELECT,
        options = listOf(
            RecommendationOption("modern", "2015 or newer", "min:2015"),
            RecommendationOption("2000s", "2000s–2010s", "range:2000:2019"),
            RecommendationOption("classic", "Before 1990", "max:1989"),
            RecommendationOption("any", "Any era", "any"),
        ),
    )

    private fun languageQuestion(candidates: List<RecommendationCandidate>): RecommendationQuestion {
        val languages = candidates.mapNotNull { it.metadata.originalLanguage }
            .groupingBy(String::trim)
            .eachCount()
            .entries
            .sortedByDescending(Map.Entry<String, Int>::value)
            .map(Map.Entry<String, Int>::key)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .take(5)
        return RecommendationQuestion(
            id = "language",
            dimension = RecommendationDimension.LANGUAGE,
            text = "Original language preference?",
            type = RecommendationQuestionType.SINGLE_SELECT,
            options = languages.map {
                RecommendationOption(it.lowercase().replace(' ', '_'), it, it)
            } + RecommendationOption("any", "Any language", "any"),
        )
    }

    private fun qualityQuestion() = RecommendationQuestion(
        id = "quality",
        dimension = RecommendationDimension.QUALITY,
        text = "How selective should I be about ratings?",
        type = RecommendationQuestionType.SINGLE_SELECT,
        options = listOf(
            RecommendationOption("imdb8", "IMDb 8+", "imdb:8"),
            RecommendationOption("imdb7", "IMDb 7+", "imdb:7"),
            RecommendationOption("acclaimed", "Critically strong", "rt:75"),
            RecommendationOption("any", "Ratings don't matter", "any"),
        ),
    )

    private fun familiarityQuestion() = RecommendationQuestion(
        id = "familiarity",
        dimension = RecommendationDimension.FAMILIARITY,
        text = "Familiar favorite or something less obvious?",
        type = RecommendationQuestionType.SINGLE_SELECT,
        options = FamiliarityPreference.entries.map {
            RecommendationOption(it.name.lowercase(), it.label, it.name)
        } + RecommendationOption("any", "Doesn't matter", "any"),
    )

    private data class QuestionProposal(
        val question: RecommendationQuestion,
        val gain: Double,
        val coverage: Double,
    )
}
object RecommendationRanker {
    const val RECOMMEND_THRESHOLD = 60.0
    const val EARLY_STOP_THRESHOLD = 70.0
    private const val DIVERSITY_LAMBDA = 0.78

    fun hardFilter(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
        recentlyPlayedKeys: Set<String> = emptySet(),
        seenKeys: Set<String> = emptySet(),
    ): List<RecommendationCandidate> = candidates
        .asSequence()
        .distinctBy { it.media.key }
        .filterNot { it.media.key in preferences.rejectedKeys }
        .filterNot { it.media.key in preferences.shownKeys }
        .filterNot { it.media.key in recentlyPlayedKeys }
        .filterNot { it.media.key in seenKeys }
        .filter { candidate -> satisfiesHardConstraints(preferences, candidate) }
        .toList()

    fun rank(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
        likes: List<Media> = emptyList(),
        taste: TasteProfile = TasteProfile(),
        similarityAnchor: Media? = null,
    ): List<RecommendationCandidate> {
        val scored = candidates.map { candidate ->
            scoreCandidate(preferences, candidate, likes, taste, similarityAnchor)
        }.sortedByDescending { it.score.total }
        return diversify(scored, scored.size)
    }

    fun rankAll(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
        likes: List<Media> = emptyList(),
        taste: TasteProfile = TasteProfile(),
        similarityAnchor: Media? = null,
    ): List<RecommendationCandidate> =
        rank(preferences, candidates, likes, taste, similarityAnchor)

    fun shouldRecommend(
        preferences: RecommendationPreferences,
        ranked: List<RecommendationCandidate>,
        nextQuestion: RecommendationQuestion?,
    ): Boolean {
        if (ranked.isEmpty()) return false
        if (preferences.surpriseMe) return true
        return nextQuestion == null
    }

    fun relaxationOptions(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
    ): List<ConstraintRelaxation> {
        val trials = buildList {
            preferences.runtimeMaximumMinutes?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
                add(
                    ConstraintRelaxation(
                        "runtime_max",
                        "Increase the ${it.value}-minute limit",
                        countWithout(preferences.copy(runtimeMaximumMinutes = null), candidates),
                    ),
                )
            }
            preferences.runtimeMinimumMinutes?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
                add(
                    ConstraintRelaxation(
                        "runtime_min",
                        "Remove the minimum runtime",
                        countWithout(preferences.copy(runtimeMinimumMinutes = null), candidates),
                    ),
                )
            }
            preferences.minimumImdb?.let {
                add(
                    ConstraintRelaxation(
                        "imdb",
                        "Lower the IMDb ${it.value}+ requirement",
                        countWithout(preferences.copy(minimumImdb = null), candidates),
                    ),
                )
            }
            preferences.minimumRottenTomatoes?.let {
                add(
                    ConstraintRelaxation(
                        "rt",
                        "Lower the RT ${it.value}% requirement",
                        countWithout(preferences.copy(minimumRottenTomatoes = null), candidates),
                    ),
                )
            }
            preferences.yearMinimum?.let {
                add(
                    ConstraintRelaxation(
                        "year_min",
                        "Include titles from before ${it.value}",
                        countWithout(preferences.copy(yearMinimum = null), candidates),
                    ),
                )
            }
            preferences.yearMaximum?.let {
                add(
                    ConstraintRelaxation(
                        "year_max",
                        "Include titles after ${it.value}",
                        countWithout(preferences.copy(yearMaximum = null), candidates),
                    ),
                )
            }
            preferences.originalLanguage?.let {
                add(
                    ConstraintRelaxation(
                        "language",
                        "Allow another original language",
                        countWithout(preferences.copy(originalLanguage = null), candidates),
                    ),
                )
            }
            if (preferences.excludedGenres.isNotEmpty()) {
                add(
                    ConstraintRelaxation(
                        "excluded_genres",
                        "Remove a genre exclusion",
                        countWithout(preferences.copy(excludedGenres = emptyList()), candidates),
                    ),
                )
            }
        }
        return trials
            .filter { it.recoveredCandidates > 0 }
            .sortedWith(
                compareByDescending<ConstraintRelaxation>(ConstraintRelaxation::recoveredCandidates)
                    .thenBy(ConstraintRelaxation::label),
            )
            .take(3)
    }

    fun applyRelaxation(
        preferences: RecommendationPreferences,
        id: String,
    ): RecommendationPreferences = when (id) {
        "runtime_max" -> preferences.copy(runtimeMaximumMinutes = null)
        "runtime_min" -> preferences.copy(runtimeMinimumMinutes = null)
        "imdb" -> preferences.copy(minimumImdb = null)
        "rt" -> preferences.copy(minimumRottenTomatoes = null)
        "year_min" -> preferences.copy(yearMinimum = null)
        "year_max" -> preferences.copy(yearMaximum = null)
        "language" -> preferences.copy(originalLanguage = null)
        "excluded_genres" -> preferences.copy(excludedGenres = emptyList())
        else -> preferences
    }

    internal fun satisfiesHardConstraints(
        preferences: RecommendationPreferences,
        candidate: RecommendationCandidate,
    ): Boolean {
        val media = candidate.media
        val metadata = candidate.metadata
        preferences.contentType?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
            if (!it.value.accepts(media.type)) return false
        }
        val genres = media.genres.map(::normalize).toSet()
        val hardGenreKnowledgeRequired =
            preferences.includedGenres.any {
                it.strength == ConstraintStrength.HARD
            } || preferences.excludedGenres.isNotEmpty()
        if (hardGenreKnowledgeRequired && !metadata.genresVerified) return false
        preferences.includedGenres.filter { it.strength == ConstraintStrength.HARD }.forEach {
            if (normalize(it.value) !in genres) return false
        }
        preferences.excludedGenres.forEach {
            if (normalize(it.value) in genres) return false
        }
        val runtime = runtimeFor(candidate)
        preferences.runtimeMinimumMinutes?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
            if (runtime == null || runtime < it.value) return false
        }
        preferences.runtimeMaximumMinutes?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
            if (runtime == null || runtime > it.value) return false
        }
        val year = media.year.take(4).toIntOrNull()
        preferences.yearMinimum?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
            if (year == null || year < it.value) return false
        }
        preferences.yearMaximum?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
            if (year == null || year > it.value) return false
        }
        preferences.minimumImdb?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
            if (media.imdbRating == null || media.imdbRating < it.value) return false
        }
        preferences.minimumRottenTomatoes
            ?.takeIf { it.strength == ConstraintStrength.HARD }
            ?.let {
                if (
                    media.rottenTomatoesRating == null ||
                    media.rottenTomatoesRating < it.value
                ) {
                    return false
                }
            }
        preferences.minimumTmdb?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
            if (media.rating <= 0.0 || media.rating < it.value) return false
        }
        preferences.originalLanguage?.takeIf { it.strength == ConstraintStrength.HARD }?.let {
            if (
                metadata.originalLanguage.isNullOrBlank() ||
                !metadata.originalLanguage.equals(it.value, ignoreCase = true)
            ) {
                return false
            }
        }
        return true
    }

    private fun scoreCandidate(
        preferences: RecommendationPreferences,
        candidate: RecommendationCandidate,
        likes: List<Media>,
        taste: TasteProfile,
        anchor: Media?,
    ): RecommendationCandidate {
        val media = candidate.media
        val genres = media.genres.map(::normalize).toSet()
        val evidence = normalize(
            listOf(media.overview, candidate.evidence, media.genres.joinToString(" "))
                .joinToString(" "),
        )

        val requestedGenres = preferences.includedGenres.map { normalize(it.value) }
        val genreRatio = if (requestedGenres.isEmpty()) {
            0.62
        } else {
            requestedGenres.count(genres::contains).toDouble() / requestedGenres.size
        }
        val moodRatio = if (preferences.moods.isEmpty()) {
            0.62
        } else {
            preferences.moods.count { signal ->
                moodKeywords[signal.value].orEmpty().any(evidence::contains)
            }.toDouble() / preferences.moods.size
        }
        val contentScore = (genreRatio * 0.62 + moodRatio * 0.38) * 30.0

        val similarityScore = if (anchor != null && media.key != anchor.key) {
            (RelatedContentEngine.similarity(anchor, media) / 120.0)
                .coerceIn(0.0, 1.0) * 18.0
        } else {
            9.0
        }

        val runtime = runtimeFor(candidate)
        val runtimeFit = when {
            preferences.preferredRuntimeMinutes != null && runtime != null ->
                (1.0 - abs(runtime - preferences.preferredRuntimeMinutes.value) / 90.0)
                    .coerceIn(0.0, 1.0)
            preferences.runtimeMinimumMinutes != null && runtime != null ->
                if (runtime >= preferences.runtimeMinimumMinutes.value) 1.0 else 0.0
            preferences.runtimeMaximumMinutes != null && runtime != null ->
                if (runtime <= preferences.runtimeMaximumMinutes.value) 1.0 else 0.0
            else -> 0.62
        }
        val year = media.year.take(4).toIntOrNull()
        val yearFit = when {
            preferences.yearMinimum != null && year != null ->
                if (year >= preferences.yearMinimum.value) 1.0 else 0.0
            preferences.yearMaximum != null && year != null ->
                if (year <= preferences.yearMaximum.value) 1.0 else 0.0
            else -> 0.62
        }
        val languageFit = when {
            preferences.originalLanguage != null &&
                !candidate.metadata.originalLanguage.isNullOrBlank() ->
                if (
                    candidate.metadata.originalLanguage.equals(
                        preferences.originalLanguage.value,
                        ignoreCase = true,
                    )
                ) {
                    1.0
                } else {
                    0.0
                }
            else -> 0.62
        }
        val contextualFit = (runtimeFit * 0.5 + yearFit * 0.25 + languageFit * 0.25) * 16.0

        val availableRatings = buildList {
            media.imdbRating?.let { add(it / 10.0) }
            media.rottenTomatoesRating?.let { add(it / 100.0) }
            media.rating.takeIf { it > 0.0 }?.let { add(it / 10.0) }
        }
        val qualityCoverage = availableRatings.size / 3.0
        val qualityMean = availableRatings.average().takeUnless(Double::isNaN) ?: 0.5
        val qualityScore = qualityMean.coerceIn(0.0, 1.0) * 16.0

        val personal = PersonalizationEngine.match(media, likes)?.score?.div(100.0) ?: 0.5
        val tasteAffinity = media.genres.map { "genre:${normalize(it)}" }
            .mapNotNull(taste.signals::get)
            .map { signal -> (signal.affinity * signal.confidence + 1.0) / 2.0 }
            .average()
            .takeUnless(Double::isNaN)
            ?: 0.5
        val tasteScore = (personal * 0.55 + tasteAffinity * 0.45) * 10.0

        val consensus = (candidate.sourceCount.coerceAtMost(3) / 3.0) * 0.65
        val position = (1.0 - candidate.sourcePosition.coerceAtMost(20) / 20.0) * 0.35
        val discoveryScore = (consensus + position) * 6.0

        val noveltyScore = when (preferences.familiarity?.value) {
            FamiliarityPreference.HIDDEN_GEM,
            FamiliarityPreference.OBSCURE,
            -> (1.0 - (media.rating / 10.0).coerceIn(0.0, 1.0) * 0.25) * 4.0
            FamiliarityPreference.POPULAR,
            FamiliarityPreference.FAMILIAR,
            -> ((candidate.sourceCount / 3.0).coerceIn(0.0, 1.0) * 0.6 + 0.4) * 4.0
            null -> 2.5
        }

        val metadataSignals = listOf(
            runtime != null,
            year != null,
            !candidate.metadata.originalLanguage.isNullOrBlank(),
            media.imdbRating != null,
            media.rottenTomatoesRating != null,
            media.genres.isNotEmpty(),
        )
        val coverage = (
            metadataSignals.count { it }.toDouble() / metadataSignals.size
            ).coerceIn(0.0, 1.0)
        val coveragePenalty = 0.85 + coverage * 0.15
        val total = (
            contentScore + similarityScore + contextualFit + qualityScore +
                tasteScore + discoveryScore + noveltyScore
            ) * coveragePenalty

        val breakdown = RecommendationScoreBreakdown(
            contentMatch = contentScore,
            similarity = similarityScore,
            contextualFit = contextualFit,
            quality = qualityScore,
            taste = tasteScore,
            discovery = discoveryScore,
            novelty = noveltyScore,
            coverage = max(coverage, qualityCoverage),
            total = total.coerceIn(0.0, 100.0),
        )
        return candidate.copy(
            score = breakdown,
            explanation = explanationFor(preferences, candidate, anchor),
        )
    }

    private fun diversify(
        sorted: List<RecommendationCandidate>,
        limit: Int,
    ): List<RecommendationCandidate> {
        if (sorted.size <= 1) return sorted.take(limit)
        val chosen = mutableListOf(sorted.first())
        while (chosen.size < limit) {
            val next = sorted
                .asSequence()
                .filterNot { candidate -> chosen.any { it.media.key == candidate.media.key } }
                .maxByOrNull { candidate ->
                    val similarity = chosen.maxOf { selected ->
                        candidateSimilarity(candidate, selected)
                    }
                    DIVERSITY_LAMBDA * candidate.score.total -
                        (1.0 - DIVERSITY_LAMBDA) * similarity * 100.0
                } ?: break
            chosen += next
        }
        return chosen
    }

    private fun candidateSimilarity(
        first: RecommendationCandidate,
        second: RecommendationCandidate,
    ): Double {
        val firstGenres = first.media.genres.map(::normalize).toSet()
        val secondGenres = second.media.genres.map(::normalize).toSet()
        val union = firstGenres union secondGenres
        val genre = if (union.isEmpty()) 0.0 else (firstGenres intersect secondGenres).size.toDouble() / union.size
        val firstYear = first.media.year.take(4).toIntOrNull()
        val secondYear = second.media.year.take(4).toIntOrNull()
        val era = if (firstYear != null && secondYear != null) {
            (1.0 - abs(firstYear - secondYear) / 30.0).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val type = if (first.media.type == second.media.type) 1.0 else 0.0
        return genre * 0.65 + era * 0.2 + type * 0.15
    }

    private fun explanationFor(
        preferences: RecommendationPreferences,
        candidate: RecommendationCandidate,
        anchor: Media?,
    ): String {
        val facts = buildList {
            val requested = preferences.includedGenres.map { normalize(it.value) }.toSet()
            val matchingGenres = candidate.media.genres.filter { normalize(it) in requested }
            if (matchingGenres.isNotEmpty()) {
                add("${matchingGenres.take(2).joinToString(" and ")} matches your genre preference")
            }
            runtimeFor(candidate)?.let { runtime ->
                if (
                    preferences.runtimeMaximumMinutes?.value?.let { runtime <= it } == true ||
                    preferences.runtimeMinimumMinutes?.value?.let { runtime >= it } == true ||
                    preferences.preferredRuntimeMinutes != null
                ) {
                    add("${runtime} minutes fits your available time")
                }
            }
            candidate.media.imdbRating?.let { rating ->
                if (preferences.minimumImdb != null) add("IMDb ${formatOneDecimal(rating)} meets your minimum")
            }
            candidate.media.rottenTomatoesRating?.let { rating ->
                if (preferences.minimumRottenTomatoes != null) add("RT critic score is $rating%")
            }
            candidate.metadata.originalLanguage?.let { language ->
                if (preferences.originalLanguage != null) add("its original language is $language")
            }
            if (anchor != null) add("it shares concrete genre and story signals with ${anchor.title}")
        }
        return when {
            facts.isNotEmpty() -> facts.take(3).joinToString(". ").replaceFirstChar(Char::uppercase) + "."
            candidate.media.genres.isNotEmpty() ->
                "${candidate.media.genres.take(2).joinToString(" and ")} plus verified catalog quality made this a strong match."
            else ->
                "Its verified catalog metadata and agreement across discovery sources made it one of the strongest matches."
        }
    }

    private fun runtimeFor(candidate: RecommendationCandidate): Int? =
        if (candidate.media.type == MediaType.TV) {
            candidate.metadata.averageEpisodeRuntimeMinutes
        } else {
            candidate.metadata.runtimeMinutes
        }

    private fun countWithout(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
    ): Int = candidates.count { satisfiesHardConstraints(preferences, it) }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun formatOneDecimal(value: Double): String =
        String.format(java.util.Locale.US, "%.1f", value)

    private val moodKeywords = mapOf(
        RecommendationMood.FUNNY to setOf("comedy", "funny", "humor", "laugh"),
        RecommendationMood.SCARY to setOf("horror", "scary", "terror", "haunted", "nightmare"),
        RecommendationMood.EMOTIONAL to setOf("drama", "emotional", "family", "loss", "love"),
        RecommendationMood.RELAXING to setOf("family", "comedy", "gentle", "cozy", "friendship"),
        RecommendationMood.MIND_BENDING to setOf(
            "science fiction",
            "mystery",
            "dream",
            "reality",
            "time",
            "psychological",
        ),
        RecommendationMood.INTENSE to setOf("thriller", "crime", "war", "survival", "intense"),
        RecommendationMood.ROMANTIC to setOf("romance", "love", "relationship"),
        RecommendationMood.EXCITING to setOf("action", "adventure", "thriller", "mission"),
        RecommendationMood.DARK to setOf("crime", "thriller", "horror", "dark", "murder"),
        RecommendationMood.FEEL_GOOD to setOf("comedy", "family", "music", "friendship", "uplifting"),
        RecommendationMood.THOUGHT_PROVOKING to setOf(
            "documentary",
            "drama",
            "philosophy",
            "society",
            "identity",
        ),
        RecommendationMood.NOSTALGIC to setOf("history", "childhood", "classic", "coming of age"),
    )
}
