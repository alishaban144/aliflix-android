package com.aliflix.app.recommendation

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

fun interface SemanticTextScorer {
    fun similarity(query: String, document: String): Double?
}

object RecommendationQuestionSelector {
    const val MAX_QUESTIONS = 2
    private const val MIN_INFORMATION_GAIN = 0.12
    private const val MIN_METADATA_COVERAGE = 0.55
    private const val MIN_CANDIDATE_POOL = 12

    fun nextQuestion(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
    ): RecommendationQuestion? {
        if (
            preferences.askedQuestionIds.count(::isTailoredQuestionId) >= MAX_QUESTIONS ||
            preferences.surpriseMe ||
            candidates.size < MIN_CANDIDATE_POOL
        ) {
            return null
        }

        val proposals = buildList {
            if (RecommendationDimension.SUBJECTIVE_FACET !in preferences.answeredDimensions) {
                facetContrastProposal(preferences, candidates)?.let(::add)
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
            if (preferences.includedGenres.isEmpty()) {
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
        preferences.askedQuestionIds.none(::isTailoredQuestionId) ->
            "One useful distinction can sharpen these matches."
        else ->
            "One final distinction may help."
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

    private fun facetContrastProposal(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
    ): QuestionProposal? {
        val selectedIds = (
            preferences.semanticFacets.map { it.value.id } +
                preferences.excludedFacets.map { it.value.id }
            ).toSet()
        val facetsByCandidate = candidates.associateWith { candidate ->
            RecommendationOntology.detect(
                listOf(
                    candidate.media.title,
                    candidate.media.overview,
                    candidate.media.genres.joinToString(" "),
                    candidate.evidence,
                ).joinToString(" "),
            ).filterNot { it.id in selectedIds }
        }
        val category = SemanticFacetCategory.entries
            .asSequence()
            .map { category ->
                val values = facetsByCandidate.values.mapNotNull { facets ->
                    facets.firstOrNull { it.category == category }?.id
                }
                Triple(category, informationGain(values), values.size.toDouble() / candidates.size)
            }
            .filter { (_, gain, coverage) ->
                gain >= MIN_INFORMATION_GAIN && coverage >= MIN_METADATA_COVERAGE
            }
            .maxByOrNull { (_, gain, coverage) -> gain * coverage }
            ?: return null
        val categoryFacets = facetsByCandidate.values
            .flatten()
            .filter { it.category == category.first }
            .groupingBy(SemanticFacet::id)
            .eachCount()
            .entries
            .sortedByDescending(Map.Entry<String, Int>::value)
            .mapNotNull { RecommendationOntology.byId(it.key) }
            .take(3)
        if (categoryFacets.size < 2) return null
        val question = RecommendationQuestion(
            id = "contrast:${category.first.name.lowercase()}:" +
                categoryFacets.joinToString(",") { it.id },
            dimension = RecommendationDimension.SUBJECTIVE_FACET,
            text = when (category.first) {
                SemanticFacetCategory.PACE -> "Which pace feels closer?"
                SemanticFacetCategory.TONE -> "Which tone fits tonight?"
                SemanticFacetCategory.THEME -> "Which theme matters more?"
                SemanticFacetCategory.STYLE -> "Which style are you after?"
                SemanticFacetCategory.NARRATIVE -> "Which story direction fits better?"
                else -> "Which direction feels closer?"
            },
            type = RecommendationQuestionType.SINGLE_SELECT,
            options = categoryFacets.map {
                RecommendationOption(it.id, it.label, "facet:${it.id}")
            } + RecommendationOption("any", "Doesn't matter", "any"),
            supportingText = "These are the strongest differences in the current matches.",
        )
        return QuestionProposal(
            question = question,
            gain = category.second * category.third + 0.08,
            coverage = category.third,
        )
    }

    private fun isTailoredQuestionId(id: String): Boolean =
        id.startsWith("contrast:") ||
            id in setOf("runtime", "genre", "era", "language", "quality")

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
        text = "The strongest matches split by length. Which side fits better?",
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
            text = "The current matches cluster around these genres. Which is closer?",
            type = RecommendationQuestionType.MULTI_SELECT,
            options = available.map {
                RecommendationOption(it.lowercase().replace(' ', '_'), it, it)
            } + RecommendationOption("any", "Doesn't matter", "any"),
        )
    }

    private fun eraQuestion() = RecommendationQuestion(
        id = "era",
        dimension = RecommendationDimension.ERA,
        text = "The leading matches come from different eras. Which feels closer?",
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
            text = "The leading matches split by original language. Which is closer?",
            type = RecommendationQuestionType.SINGLE_SELECT,
            options = languages.map {
                RecommendationOption(it.lowercase().replace(' ', '_'), it, it)
            } + RecommendationOption("any", "Any language", "any"),
        )
    }

    private fun qualityQuestion() = RecommendationQuestion(
        id = "quality",
        dimension = RecommendationDimension.QUALITY,
        text = "The leading matches split by rating profile. Which matters more?",
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
        semanticScorer: SemanticTextScorer? = null,
    ): List<RecommendationCandidate> {
        val scored = candidates.map { candidate ->
            scoreCandidate(
                preferences,
                candidate,
                likes,
                taste,
                similarityAnchor,
                semanticScorer,
            )
        }.sortedByDescending { it.score.total }
        return diversify(scored, scored.size)
    }

    fun rankAll(
        preferences: RecommendationPreferences,
        candidates: List<RecommendationCandidate>,
        likes: List<Media> = emptyList(),
        taste: TasteProfile = TasteProfile(),
        similarityAnchor: Media? = null,
        semanticScorer: SemanticTextScorer? = null,
    ): List<RecommendationCandidate> =
        rank(
            preferences,
            candidates,
            likes,
            taste,
            similarityAnchor,
            semanticScorer,
        )

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
        preferences.requiredStatus
            ?.takeIf { it.strength == ConstraintStrength.HARD }
            ?.let { required ->
                val status = metadata.status?.trim()
                if (
                    status.isNullOrBlank() ||
                    !status.equals(required.value, ignoreCase = true)
                ) {
                    return false
                }
            }
        val document = normalize(
            listOf(
                media.title,
                media.overview,
                media.genres.joinToString(" "),
                media.cast.joinToString(" "),
                metadata.director.orEmpty(),
                candidate.evidence,
            ).joinToString(" "),
        )
        val detectedFacetIds = RecommendationOntology.detect(document)
            .map(SemanticFacet::id)
            .toSet()
        preferences.excludedFacets.forEach { signal ->
            if (
                signal.value.id in detectedFacetIds ||
                signal.value.discoveryTerms.any { term ->
                    document.contains(normalize(term))
                }
            ) {
                return false
            }
        }
        preferences.unmatchedPreferences
            .filter { it.negated && it.confidence >= 0.85 }
            .forEach { exclusion ->
                val tokens = normalize(exclusion.text)
                    .split(' ')
                    .filter { it.length > 2 }
                if (tokens.isNotEmpty() && tokens.all(document::contains)) {
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
        semanticScorer: SemanticTextScorer?,
    ): RecommendationCandidate {
        val media = candidate.media
        val genres = media.genres.map(::normalize).toSet()
        val document = listOf(
            media.title,
            media.overview,
            media.genres.joinToString(" "),
            media.cast.joinToString(" "),
            candidate.metadata.director.orEmpty(),
            candidate.evidence,
        ).filter(String::isNotBlank).joinToString(". ")
        val normalizedDocument = normalize(document)
        val detectedFacets = RecommendationOntology.detect(document)
            .map(SemanticFacet::id)
            .toSet()

        val requestedGenres = preferences.includedGenres.map { normalize(it.value) }
        val genreFit = ratio(requestedGenres) { it in genres }
        val moodFit = ratio(preferences.moods) { signal ->
            moodKeywords[signal.value].orEmpty().any(normalizedDocument::contains)
        }
        val facetFit = ratio(preferences.semanticFacets) {
            it.value.id in detectedFacets ||
                it.value.discoveryTerms.any { term ->
                    normalizedDocument.contains(normalize(term))
                }
        }
        val unmatchedTerms = preferences.unmatchedPreferences
            .filterNot(UnmatchedPreference::negated)
            .flatMap { normalize(it.text).split(' ') }
            .filter { it.length > 2 }
            .distinct()
        val unmatchedFit = ratio(unmatchedTerms, normalizedDocument::contains)
        val contextFit = viewingContextFit(preferences.viewingContext?.value, normalizedDocument)
        val activeLexical = buildList {
            if (requestedGenres.isNotEmpty()) add(genreFit to 0.32)
            if (preferences.moods.isNotEmpty()) add(moodFit to 0.18)
            if (preferences.semanticFacets.isNotEmpty()) add(facetFit to 0.30)
            if (unmatchedTerms.isNotEmpty()) add(unmatchedFit to 0.15)
            if (preferences.viewingContext != null) add(contextFit to 0.05)
        }
        val lexicalValue = weightedAverage(activeLexical)

        val semanticQuery = RecommendationQueryBuilder.build(preferences)
        val semanticValue = if (
            semanticScorer != null &&
            semanticQuery.isNotBlank() &&
            document.isNotBlank()
        ) {
            semanticScorer.similarity(semanticQuery, document)
                ?.coerceIn(0.0, 1.0)
        } else {
            null
        }

        val sourceRanks = if (candidate.sourceRanks.isNotEmpty()) {
            candidate.sourceRanks
        } else {
            candidate.sources.associateWith { candidate.sourcePosition.coerceAtLeast(0) }
        }
        val sourceValue = reciprocalRankFusion(sourceRanks)

        val ratings = listOf(
            media.imdbRating?.let { rating ->
                val voteConfidence = media.imdbVoteCount?.let {
                    (log10(it.toDouble() + 1.0) / 6.0).coerceIn(0.35, 1.0)
                } ?: 0.58
                (rating / 10.0).coerceIn(0.0, 1.0) * voteConfidence
            },
            media.rottenTomatoesRating?.let { (it / 100.0).coerceIn(0.0, 1.0) },
            media.rating.takeIf { it > 0.0 }?.let { (it / 10.0).coerceIn(0.0, 1.0) },
        )
        val qualityValue = ratings.filterNotNull().sum() / ratings.size

        val anchorValue = anchor
            ?.takeIf { media.key != it.key }
            ?.let {
                (RelatedContentEngine.similarity(it, media) / 120.0)
                    .coerceIn(0.0, 1.0)
            }

        val personal = PersonalizationEngine.match(media, likes)
            ?.score
            ?.div(100.0)
        val era = media.year.take(4).toIntOrNull()?.let { "${it / 10 * 10}s" }
        val tasteKeys = buildList {
            add("type:${media.type.routeName}")
            addAll(media.genres.map { "genre:${normalize(it)}" })
            era?.let { add("era:$it") }
            addAll(detectedFacets.map { "facet:$it" })
        }
        val learnedTaste = tasteKeys
            .mapNotNull(taste.signals::get)
            .map { signal ->
                ((signal.affinity * signal.confidence) + 1.0) / 2.0
            }
            .average()
            .takeUnless(Double::isNaN)
        val novelty = familiarityFit(preferences.familiarity?.value, media, candidate)
        val tasteValue = listOfNotNull(personal, learnedTaste, novelty)
            .average()
            .takeUnless(Double::isNaN)

        val components = buildList {
            semanticValue?.let { add(WeightedComponent("semantic", 35.0, it)) }
            if (activeLexical.isNotEmpty()) {
                add(WeightedComponent("lexical", 20.0, lexicalValue))
            }
            add(WeightedComponent("source", 15.0, sourceValue))
            add(WeightedComponent("quality", 15.0, qualityValue))
            anchorValue?.let { add(WeightedComponent("anchor", 10.0, it)) }
            tasteValue?.let { add(WeightedComponent("taste", 5.0, it)) }
        }
        val activeWeight = components.sumOf(WeightedComponent::weight).coerceAtLeast(1.0)
        val negativeFacetHits = preferences.excludedFacets.count { signal ->
            signal.value.id in detectedFacets ||
                signal.value.discoveryTerms.any {
                    normalizedDocument.contains(normalize(it))
                }
        }
        val negativeTermHits = preferences.unmatchedPreferences
            .filter(UnmatchedPreference::negated)
            .count { normalizedDocument.contains(normalize(it.text)) }
        val softExclusionPenalty = (
            negativeFacetHits * 12.0 + negativeTermHits * 8.0
            ).coerceAtMost(30.0)
        val total = (
            components.sumOf { it.weight * it.value } / activeWeight * 100.0 -
                softExclusionPenalty
            ).coerceIn(0.0, 100.0)

        val breakdown = RecommendationScoreBreakdown(
            contentMatch = lexicalValue * 20.0,
            similarity = (anchorValue ?: semanticValue ?: 0.0) * 10.0,
            contextualFit = contextFit * 5.0,
            quality = qualityValue * 15.0,
            taste = (tasteValue ?: 0.0) * 5.0,
            discovery = sourceValue * 15.0,
            novelty = novelty * 5.0,
            coverage = components.count { it.value > 0.0 }.toDouble() /
                components.size.coerceAtLeast(1),
            total = total,
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

    private fun <T> ratio(
        values: List<T>,
        matches: (T) -> Boolean,
    ): Double = if (values.isEmpty()) {
        0.0
    } else {
        values.count(matches).toDouble() / values.size
    }

    private fun weightedAverage(values: List<Pair<Double, Double>>): Double {
        val weight = values.sumOf { it.second }
        if (weight <= 0.0) return 0.0
        return values.sumOf { (value, itemWeight) -> value * itemWeight } / weight
    }

    private fun viewingContextFit(
        context: ViewingContext?,
        document: String,
    ): Double = when (context) {
        ViewingContext.FRIENDS,
        ViewingContext.GROUP,
        -> lexicalAny(document, "comedy", "funny", "action", "mystery", "exciting")
        ViewingContext.FAMILY,
        ViewingContext.CHILDREN,
        -> lexicalAny(document, "family", "animation", "adventure", "friendship")
        ViewingContext.PARTNER ->
            lexicalAny(document, "romance", "comedy", "mystery", "relationship", "drama")
        ViewingContext.ALONE ->
            lexicalAny(document, "psychological", "mystery", "drama", "documentary")
        null -> 0.0
    }

    private fun lexicalAny(document: String, vararg words: String): Double {
        if (words.isEmpty()) return 0.0
        return words.count(document::contains).toDouble() / words.size
    }

    private fun reciprocalRankFusion(ranks: Map<String, Int>): Double {
        if (ranks.isEmpty()) return 0.0
        var total = 0.0
        ranks.forEach { (source, rank) ->
            val familyCap = when {
                "REDDIT" in source.uppercase() -> 0.65
                "SESSION" in source.uppercase() -> 0.40
                else -> 1.0
            }
            total += familyCap / (60.0 + rank.coerceAtLeast(0) + 1.0)
        }
        val idealSources = minOf(4, ranks.size).coerceAtLeast(1)
        val ideal = (0 until idealSources).sumOf { 1.0 / (61.0 + it) }
        return (total / ideal).coerceIn(0.0, 1.0)
    }

    private fun familiarityFit(
        preference: FamiliarityPreference?,
        media: Media,
        candidate: RecommendationCandidate,
    ): Double {
        val votes = media.imdbVoteCount ?: 0
        val popularity = (
            log10(votes.toDouble() + 1.0) / 6.0 * 0.75 +
                (candidate.sourceCount.coerceAtMost(4) / 4.0) * 0.25
            ).coerceIn(0.0, 1.0)
        return when (preference) {
            FamiliarityPreference.HIDDEN_GEM -> (1.0 - abs(popularity - 0.42) / 0.58)
                .coerceIn(0.0, 1.0)
            FamiliarityPreference.OBSCURE -> 1.0 - popularity
            FamiliarityPreference.POPULAR,
            FamiliarityPreference.FAMILIAR,
            -> popularity
            null -> 0.5
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun formatOneDecimal(value: Double): String =
        String.format(java.util.Locale.US, "%.1f", value)

    private data class WeightedComponent(
        val name: String,
        val weight: Double,
        val value: Double,
    )

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
