package com.aliflix.app.recommendation

import java.util.Locale

data class RecommendationParseResult(
    val preferences: RecommendationPreferences,
    val confirmation: RecommendationQuestion? = null,
)

object RecommendationPreferenceParser {
    private val strongConstraintWords = Regex(
        """\b(?:must|only|strictly|definitely|required|no|without|under|over|after|before|at least|at most)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val movieWords = Regex("""\b(?:movie|film|feature)\b""", RegexOption.IGNORE_CASE)
    private val tvWords = Regex(
        """\b(?:tv|show|series|miniseries|episode|binge)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(
        text: String,
        current: RecommendationPreferences = RecommendationPreferences(),
    ): RecommendationParseResult {
        val clean = text.trim()
        if (clean.isBlank()) return RecommendationParseResult(current)
        val normalized = normalize(clean)
        if (normalized in noPreferencePhrases) {
            return RecommendationParseResult(current)
        }

        val correction = correctionWords.containsMatchIn(normalized)
        var updated = current
        val explicit = PreferenceOrigin.EXPLICIT
        val hard = ConstraintStrength.HARD
        val soft = ConstraintStrength.SOFT
        if (correction) {
            updated = updated.copy(
                moods = emptyList(),
                includedGenres = emptyList(),
                semanticFacets = emptyList(),
                excludedFacets = emptyList(),
                unmatchedPreferences = emptyList(),
            )
        }
        val containsMovie = movieWords.containsMatchIn(clean)
        val containsTv = tvWords.containsMatchIn(clean)
        // Movie/Series is selected locally before free-text interpretation.
        // The prose can clarify an unset legacy session, but must never turn
        // a selected, cancellable catalogue stream back into the old EITHER
        // universe or silently switch it mid-request.
        if ((containsMovie || containsTv) && current.contentType == null) {
            val type = when {
                containsMovie && containsTv -> null
                containsTv -> RecommendationContentType.TV
                else -> RecommendationContentType.MOVIE
            }
            if (type != null) {
                updated = updated.copy(
                    contentType = PreferenceSignal(type, explicit, hard),
                    answeredDimensions =
                        updated.answeredDimensions + RecommendationDimension.CONTENT_TYPE,
                )
            }
        }

        if (
            Regex("""\b(?:surprise me|you choose|anything goes|pick for me)\b""")
                .containsMatchIn(normalized)
        ) {
            updated = updated.copy(surpriseMe = true)
        }

        moodAliases.forEach { (mood, aliases) ->
            if (aliases.any { it.containsMatchIn(normalized) }) {
                updated = updated.copy(
                    moods = updated.moods.upsert(
                        PreferenceSignal(mood, explicit, soft),
                    ) { it.value == mood },
                    answeredDimensions = updated.answeredDimensions + RecommendationDimension.MOOD,
                )
            }
        }

        genreAliases.forEach { (genre, aliases) ->
            val matchedAlias = aliases.firstOrNull { it.containsMatchIn(normalized) }
                ?: return@forEach
            val aliasText = matchedAlias.find(normalized)?.value.orEmpty()
            val exclusion = Regex(
                """\b(?:no|not|without|exclude|avoid|anything but)\s+(?:\w+\s+){0,2}${Regex.escape(aliasText)}\b""",
            ).containsMatchIn(normalized)
            if (exclusion) {
                updated = updated.copy(
                    excludedGenres = updated.excludedGenres.upsert(
                        PreferenceSignal(genre, explicit, hard),
                    ) { it.value.equals(genre, ignoreCase = true) },
                    includedGenres = updated.includedGenres.filterNot {
                        it.value.equals(genre, ignoreCase = true)
                    },
                    answeredDimensions = updated.answeredDimensions + RecommendationDimension.GENRE,
                )
            } else {
                val strength = if (
                    Regex(
                        """\b(?:only|must|strictly|required)\b.{0,28}\b${Regex.escape(aliasText)}\b|\b${Regex.escape(aliasText)}\b.{0,28}\b(?:only|required)\b""",
                    ).containsMatchIn(normalized)
                ) {
                    hard
                } else {
                    soft
                }
                updated = updated.copy(
                    includedGenres = updated.includedGenres.upsert(
                        PreferenceSignal(genre, explicit, strength),
                    ) { it.value.equals(genre, ignoreCase = true) },
                    excludedGenres = updated.excludedGenres.filterNot {
                        it.value.equals(genre, ignoreCase = true)
                    },
                    answeredDimensions = updated.answeredDimensions + RecommendationDimension.GENRE,
                )
            }
        }

        val facetMatches = RecommendationOntology.match(normalized)
        if (facetMatches.isNotEmpty()) {
            facetMatches.forEach { match ->
                if (match.negated) {
                    updated = updated.copy(
                        excludedFacets = updated.excludedFacets.upsert(
                            PreferenceSignal(
                                match.facet,
                                explicit,
                                soft,
                                confidence = 0.9,
                            ),
                        ) { it.value.id == match.facet.id },
                        semanticFacets = updated.semanticFacets.filterNot {
                            it.value.id == match.facet.id
                        },
                    )
                } else {
                    updated = updated.copy(
                        semanticFacets = updated.semanticFacets.upsert(
                            PreferenceSignal(match.facet, explicit, soft),
                        ) { it.value.id == match.facet.id },
                        excludedFacets = updated.excludedFacets.filterNot {
                            it.value.id == match.facet.id
                        },
                    )
                }
            }
            updated = updated.copy(
                answeredDimensions =
                    updated.answeredDimensions + RecommendationDimension.SUBJECTIVE_FACET,
            )
        }

        viewingAliases.firstNotNullOfOrNull { (context, patterns) ->
            context.takeIf { patterns.any { it.containsMatchIn(normalized) } }
        }?.let { context ->
            updated = updated.copy(
                viewingContext = PreferenceSignal(context, explicit, soft),
                answeredDimensions =
                    updated.answeredDimensions + RecommendationDimension.VIEWING_CONTEXT,
            )
        }

        parseRuntime(clean)?.let { runtime ->
            val oldMin = updated.runtimeMinimumMinutes?.value
            val oldMax = updated.runtimeMaximumMinutes?.value
            val conflicts = runtime.minimum?.let { minimum ->
                (runtime.maximum ?: oldMax)?.let { maximum -> minimum > maximum }
            } ?: runtime.maximum?.let { maximum ->
                oldMin?.let { minimum -> minimum > maximum }
            } ?: false
            if (conflicts) {
                return RecommendationParseResult(
                    preferences = updated,
                    confirmation = runtimeConflictQuestion(runtime),
                )
            }
            updated = updated.copy(
                runtimeMinimumMinutes = runtime.minimum?.let {
                    PreferenceSignal(it, explicit, hard)
                } ?: updated.runtimeMinimumMinutes,
                runtimeMaximumMinutes = runtime.maximum?.let {
                    PreferenceSignal(it, explicit, hard)
                } ?: updated.runtimeMaximumMinutes,
                preferredRuntimeMinutes = runtime.preferred?.let {
                    PreferenceSignal(it, explicit, soft)
                } ?: updated.preferredRuntimeMinutes,
                answeredDimensions = updated.answeredDimensions + RecommendationDimension.RUNTIME,
            )
        }

        parseYears(normalized)?.let { range ->
            val minimumYear = range.first
            val maximumYear = range.second
            if (
                minimumYear != null &&
                maximumYear != null &&
                minimumYear > maximumYear
            ) {
                return RecommendationParseResult(
                    preferences = updated,
                    confirmation = yearConflictQuestion(minimumYear, maximumYear),
                )
            }
            updated = updated.copy(
                yearMinimum = minimumYear?.let { PreferenceSignal(it, explicit, hard) }
                    ?: updated.yearMinimum,
                yearMaximum = maximumYear?.let { PreferenceSignal(it, explicit, hard) }
                    ?: updated.yearMaximum,
                answeredDimensions = updated.answeredDimensions + RecommendationDimension.ERA,
            )
        }

        parseRating(normalized, "imdb")?.let {
            updated = updated.copy(
                minimumImdb = PreferenceSignal(it, explicit, hard),
                answeredDimensions = updated.answeredDimensions + RecommendationDimension.QUALITY,
            )
        }
        parseRating(normalized, "(?:rt|rotten tomatoes)", percent = true)?.let {
            updated = updated.copy(
                minimumRottenTomatoes = PreferenceSignal(it.toInt(), explicit, hard),
                answeredDimensions = updated.answeredDimensions + RecommendationDimension.QUALITY,
            )
        }
        parseRating(normalized, "tmdb")?.let {
            updated = updated.copy(
                minimumTmdb = PreferenceSignal(it, explicit, hard),
                answeredDimensions = updated.answeredDimensions + RecommendationDimension.QUALITY,
            )
        }

        languageAliases.firstNotNullOfOrNull { (language, aliases) ->
            language.takeIf { aliases.any { alias -> Regex("""\b$alias\b""").containsMatchIn(normalized) } }
        }?.let { language ->
            updated = updated.copy(
                originalLanguage = PreferenceSignal(language, explicit, hard),
                answeredDimensions = updated.answeredDimensions + RecommendationDimension.LANGUAGE,
            )
        }

        parseSimilarityTitle(clean)?.let { title ->
            val relativeRuntime = when {
                Regex("""\bshorter\s+than\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(clean) ->
                    RelativeRuntimePreference.SHORTER_THAN_ANCHOR
                Regex("""\blonger\s+than\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(clean) ->
                    RelativeRuntimePreference.LONGER_THAN_ANCHOR
                else -> null
            }
            updated = updated.copy(
                similarityTitle = PreferenceSignal(title, explicit, soft),
                relativeRuntime = relativeRuntime?.let {
                    PreferenceSignal(it, explicit, hard)
                } ?: updated.relativeRuntime,
            )
        }

        val explicitlyNotMainstream = Regex(
            """\b(?:not|no|avoid)\s+(?:too\s+)?(?:famous|popular|mainstream|well[- ]known)\b""",
        ).containsMatchIn(normalized)
        val familiarity = if (explicitlyNotMainstream) {
            FamiliarityPreference.OBSCURE
        } else {
            familiarityAliases.firstNotNullOfOrNull { (value, patterns) ->
                value.takeIf { patterns.any { it.containsMatchIn(normalized) } }
            }
        }
        familiarity?.let {
            updated = updated.copy(
                familiarity = PreferenceSignal(it, explicit, soft),
                answeredDimensions = updated.answeredDimensions + RecommendationDimension.FAMILIARITY,
            )
        }

        if (
            containsTv &&
            Regex(
                """\b(?:finished|completed|complete|ended|concluded)\s+(?:\w+\s+){0,3}(?:show|series|miniseries)\b|\b(?:show|series|miniseries)\s+(?:that\s+)?(?:is\s+)?(?:finished|completed|ended|concluded)\b""",
            ).containsMatchIn(normalized)
        ) {
            updated = updated.copy(
                requiredStatus = PreferenceSignal("Ended", explicit, hard),
                answeredDimensions =
                    updated.answeredDimensions + RecommendationDimension.STATUS,
            )
        }

        parseNamedValues(
            clean,
            Regex(
                """\b(?:directed by|from director|made by)\s+([A-Za-z][A-Za-z .'-]{2,60})""",
                RegexOption.IGNORE_CASE,
            ),
        ).takeIf(List<String>::isNotEmpty)?.let { names ->
            updated = updated.copy(
                creatorNames = names.map {
                    PreferenceSignal(it, explicit, soft)
                },
            )
        }
        parseNamedValues(
            clean,
            Regex(
                """\b(?:starring|with actor|with actress|featuring)\s+([A-Za-z][A-Za-z .'-]{2,60})""",
                RegexOption.IGNORE_CASE,
            ),
        ).takeIf(List<String>::isNotEmpty)?.let { names ->
            updated = updated.copy(
                castNames = names.map {
                    PreferenceSignal(it, explicit, soft)
                },
            )
        }
        countryAliases.forEach { (country, aliases) ->
            if (aliases.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(normalized) }) {
                updated = updated.copy(
                    countryPreferences = updated.countryPreferences.upsert(
                        PreferenceSignal(country, explicit, soft),
                    ) { it.value.equals(country, ignoreCase = true) },
                )
            }
        }

        val unverified = unverifiablePatterns
            .filterValues { pattern -> pattern.containsMatchIn(normalized) }
            .keys
        if (unverified.isNotEmpty()) {
            updated = updated.copy(
                unverifiedTerms = (updated.unverifiedTerms + unverified).distinct(),
            )
            if (strongConstraintWords.containsMatchIn(clean)) {
                return RecommendationParseResult(
                    preferences = updated,
                    confirmation = RecommendationQuestion(
                        id = "unverified:${unverified.joinToString(",")}",
                        dimension = RecommendationDimension.UNSUPPORTED_CONFIRMATION,
                        text = "Aliflix can't verify ${unverified.joinToString(" or ")} reliably. Use it only as a web-search preference?",
                        type = RecommendationQuestionType.SINGLE_SELECT,
                        options = listOf(
                            RecommendationOption("use_soft", "Use as preference", "use_soft"),
                            RecommendationOption("remove", "Remove it", "remove"),
                        ),
                    ),
                )
            }
        }

        val unmatched = extractUnmatchedPreferences(normalized, facetMatches)
        if (unmatched.isNotEmpty()) {
            updated = updated.copy(
                unmatchedPreferences = (
                    if (correction) emptyList() else updated.unmatchedPreferences
                    ) + unmatched,
            ).let { value ->
                value.copy(
                    unmatchedPreferences = value.unmatchedPreferences
                        .distinctBy { "${it.negated}:${it.text}" }
                        .takeLast(MAX_UNMATCHED_PREFERENCES),
                )
            }
        }

        return RecommendationParseResult(updated)
    }

    fun clearDimension(
        preferences: RecommendationPreferences,
        dimension: RecommendationDimension,
    ): RecommendationPreferences {
        val answered = preferences.answeredDimensions + dimension
        return when (dimension) {
            RecommendationDimension.MOOD -> preferences.copy(
                moods = emptyList(),
                answeredDimensions = answered,
            )
            RecommendationDimension.CONTENT_TYPE -> preferences.copy(
                contentType = PreferenceSignal(
                    RecommendationContentType.EITHER,
                    PreferenceOrigin.EXPLICIT,
                    ConstraintStrength.SOFT,
                ),
                answeredDimensions = answered,
            )
            RecommendationDimension.GENRE -> preferences.copy(
                includedGenres = emptyList(),
                excludedGenres = preferences.excludedGenres,
                answeredDimensions = answered,
            )
            RecommendationDimension.VIEWING_CONTEXT -> preferences.copy(
                viewingContext = null,
                answeredDimensions = answered,
            )
            RecommendationDimension.RUNTIME -> preferences.copy(
                runtimeMinimumMinutes = null,
                runtimeMaximumMinutes = null,
                preferredRuntimeMinutes = null,
                answeredDimensions = answered,
            )
            RecommendationDimension.ERA -> preferences.copy(
                yearMinimum = null,
                yearMaximum = null,
                answeredDimensions = answered,
            )
            RecommendationDimension.QUALITY -> preferences.copy(
                minimumImdb = null,
                minimumRottenTomatoes = null,
                minimumTmdb = null,
                answeredDimensions = answered,
            )
            RecommendationDimension.LANGUAGE -> preferences.copy(
                originalLanguage = null,
                answeredDimensions = answered,
            )
            RecommendationDimension.STATUS -> preferences.copy(
                requiredStatus = null,
                answeredDimensions = answered,
            )
            RecommendationDimension.FAMILIARITY -> preferences.copy(
                familiarity = null,
                answeredDimensions = answered,
            )
            RecommendationDimension.SUBJECTIVE_FACET -> preferences.copy(
                semanticFacets = emptyList(),
                excludedFacets = emptyList(),
                unmatchedPreferences = emptyList(),
                answeredDimensions = answered,
            )
            RecommendationDimension.UNSUPPORTED_CONFIRMATION -> preferences.copy(
                unverifiedTerms = emptyList(),
                answeredDimensions = answered,
            )
        }
    }

    internal fun canonicalGenre(value: String): String? {
        val normalized = normalize(value)
        return genreAliases.entries.firstOrNull { (_, aliases) ->
            aliases.any { it.matches(normalized) || it.containsMatchIn(normalized) }
        }?.key
    }

    private fun parseRuntime(text: String): RuntimeRequest? {
        val normalized = normalize(text)
        val between = Regex(
            """\bbetween\s+([a-z]+|\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?)?\s+and\s+([a-z]+|\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?)\b""",
        ).find(normalized)
        if (between != null) {
            val first = durationToMinutes(between.groupValues[1], between.groupValues[2])
            val second = durationToMinutes(between.groupValues[3], between.groupValues[4])
            if (first != null && second != null) {
                return RuntimeRequest(minOf(first, second), maxOf(first, second), null)
            }
        }
        val range = Regex(
            """\b(\d{2,3})\s*[-–]\s*(\d{2,3})\s*(?:minutes?|mins?)\b""",
        ).find(normalized)
        if (range != null) {
            return RuntimeRequest(
                range.groupValues[1].toInt(),
                range.groupValues[2].toInt(),
                null,
            )
        }
        val under = Regex(
            """\b(?:under|less than|at most|max(?:imum)?|no longer than|shorter than)\s+([a-z]+|\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?)\b""",
        ).find(normalized)
        if (under != null) {
            durationToMinutes(under.groupValues[1], under.groupValues[2])?.let {
                return RuntimeRequest(null, it, null)
            }
        }
        val over = Regex(
            """\b(?:over|more than|at least|minimum|longer than)\s+([a-z]+|\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?)\b""",
        ).find(normalized)
        if (over != null) {
            durationToMinutes(over.groupValues[1], over.groupValues[2])?.let {
                return RuntimeRequest(it, null, null)
            }
        }
        val around = Regex(
            """\b(?:around|about|roughly|approximately)\s+([a-z]+|\d+(?:\.\d+)?)\s*(hours?|hrs?|minutes?|mins?)\b""",
        ).find(normalized)
        if (around != null) {
            durationToMinutes(around.groupValues[1], around.groupValues[2])?.let {
                return RuntimeRequest(null, null, it)
            }
        }
        return null
    }

    private fun parseYears(text: String): Pair<Int?, Int?>? {
        val minimum = Regex(
            """\b(?:after|newer than|since|from|made after|released after)\s+((?:19|20)\d{2})\b""",
        ).findAll(text).mapNotNull { match ->
            val raw = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            if (
                match.value.startsWith("after") ||
                match.value.startsWith("newer") ||
                match.value.startsWith("made after") ||
                match.value.startsWith("released after")
            ) {
                raw + 1
            } else {
                raw
            }
        }.maxOrNull()
        val maximum = Regex(
            """\b(?:before|older than|made before|released before)\s+((?:19|20)\d{2})\b""",
        ).findAll(text).mapNotNull { match ->
            match.groupValues[1].toIntOrNull()?.minus(1)
        }.minOrNull()
        if (minimum != null || maximum != null) return minimum to maximum
        Regex("""\b((?:19|20)\d0)s\b""").find(text)?.let {
            val start = it.groupValues[1].toInt()
            return start to start + 9
        }
        if (Regex("""\bmodern\b""").containsMatchIn(text)) return 2015 to null
        if (Regex("""\bclassic\b""").containsMatchIn(text)) return null to 1989
        return null
    }

    private fun parseRating(
        text: String,
        source: String,
        percent: Boolean = false,
    ): Double? {
        val suffix = if (percent) """\s*%?""" else ""
        val sourceFirst = Regex(
            """\b$source\s*(?:rating|score)?\s*(?:of|at least|minimum|>=|over|above|is)?\s*(\d{1,3}(?:\.\d+)?)\s*\+?$suffix\b""",
        ).find(text)
        val valueFirst = Regex(
            """\b(?:rated?|rating|score)?\s*(?:at least|minimum|>=|over|above)?\s*(\d{1,3}(?:\.\d+)?)\s*\+?$suffix\s*(?:on|at|from)?\s*$source\b""",
        ).find(text)
        val match = sourceFirst ?: valueFirst ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return if (percent) value.takeIf { it in 1.0..100.0 } else value.takeIf { it in 0.1..10.0 }
    }

    private fun parseSimilarityTitle(text: String): String? {
        val match = Regex(
            """\b(?:like|similar to|in the style of|shorter than|longer than)\s+(.+?)(?=\s+(?:but|and|with|under|over|after|before|that|shorter|longer|darker|lighter)\b|[,.!?]|$)""",
            RegexOption.IGNORE_CASE,
        ).find(text) ?: return null
        return match.groupValues[1]
            .trim(' ', '"', '\'', '“', '”')
            .takeIf { it.length in 2..80 }
    }

    private fun durationToMinutes(value: String, unit: String): Int? {
        val number = value.toDoubleOrNull() ?: numberWords[value] ?: return null
        return if (unit.startsWith("h")) {
            (number * 60.0).toInt()
        } else {
            number.toInt()
        }.takeIf { it in 1..600 }
    }

    private fun runtimeConflictQuestion(request: RuntimeRequest): RecommendationQuestion =
        RecommendationQuestion(
            id = "runtime_conflict",
            dimension = RecommendationDimension.RUNTIME,
            text = "Your runtime preferences conflict. Which one should Aliflix keep?",
            type = RecommendationQuestionType.SINGLE_SELECT,
            options = buildList {
                request.maximum?.let {
                    add(RecommendationOption("new_max", "Under $it min", "max:$it"))
                }
                request.minimum?.let {
                    add(RecommendationOption("new_min", "At least $it min", "min:$it"))
                }
                add(RecommendationOption("no_limit", "No runtime limit", "any"))
            },
        )

    private fun yearConflictQuestion(
        minimum: Int,
        maximum: Int,
    ): RecommendationQuestion = RecommendationQuestion(
        id = "year_conflict:$minimum:$maximum",
        dimension = RecommendationDimension.ERA,
        text = "Those release years conflict. Which limit should Aliflix keep?",
        type = RecommendationQuestionType.SINGLE_SELECT,
        options = listOf(
            RecommendationOption("new_min", "$minimum or newer", "min:$minimum"),
            RecommendationOption("new_max", "$maximum or older", "max:$maximum"),
            RecommendationOption("any", "Any release year", "any"),
        ),
    )

    private fun parseNamedValues(
        text: String,
        pattern: Regex,
    ): List<String> = pattern.findAll(text)
        .map { it.groupValues[1] }
        .map {
            it.replace(
                Regex(
                    """\s+(?:and|but|with|without|under|over|after|before)\b.*$""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).trim()
        }
        .filter { it.length in 2..60 }
        .toList()

    private fun extractUnmatchedPreferences(
        normalized: String,
        facetMatches: List<SemanticFacetMatch>,
    ): List<UnmatchedPreference> {
        val explicitNegatives = NEGATED_UNMATCHED_PATTERN
            .findAll(normalized)
            .map { match ->
                match.groupValues[1]
                    .trim()
                    .split(Regex("\\s+"))
                    .take(5)
                    .joinToString(" ")
            }
            .filter { it.length >= 4 }
            .map { phrase ->
                UnmatchedPreference(
                    text = phrase,
                    negated = true,
                    confidence = 0.9,
                )
            }
            .toList()
        var remainder = normalized
        facetMatches.sortedByDescending(SemanticFacetMatch::start).forEach { match ->
            if (match.start in 0..remainder.length && match.end <= remainder.length) {
                remainder = remainder.replaceRange(match.start, match.end, " ")
            }
        }
        remainder = unmatchedRemovalPatterns.fold(remainder) { value, pattern ->
            value.replace(pattern, " ")
        }
        val tokens = remainder
            .split(Regex("\\s+"))
            .map { it.trim('.', ',', '\'', '"') }
            .filter { it.length > 2 && it !in unmatchedStopWords }
        val positive = tokens
            .chunked(4)
            .map { words -> words.joinToString(" ").trim() }
            .filter { it.length >= 4 }
            .take(4)
            .map { phrase ->
                UnmatchedPreference(
                    text = phrase,
                    negated = Regex("""\b(?:no|not|without|avoid)\b""")
                        .containsMatchIn(phrase),
                )
            }
        return (explicitNegatives + positive)
            .distinctBy { "${it.negated}:${it.text}" }
            .take(4)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9+%.' -]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun <T> List<T>.upsert(value: T, matches: (T) -> Boolean): List<T> =
        filterNot(matches) + value

    private data class RuntimeRequest(
        val minimum: Int?,
        val maximum: Int?,
        val preferred: Int?,
    )

    private val noPreferencePhrases = setOf(
        "doesn't matter",
        "does not matter",
        "dont care",
        "don't care",
        "anything",
        "i don't know",
        "i do not know",
    )

    private val correctionWords = Regex(
        """\b(?:instead|actually|rather|change it to|make it|not that|scratch that)\b""",
    )

    private val NEGATED_UNMATCHED_PATTERN = Regex(
        """\b(?:no|not|without|avoid|exclude)\s+""" +
            """([a-z][a-z0-9' -]{2,55}?)(?=\s+(?:but|and|with|while)\b|[.!?]|$)""",
    )

    private val numberWords = mapOf(
        "one" to 1.0,
        "one and a half" to 1.5,
        "two" to 2.0,
        "two and a half" to 2.5,
        "three" to 3.0,
        "four" to 4.0,
    )

    private val genreAliases: Map<String, List<Regex>> = mapOf(
        "Action" to listOf(Regex("""\baction\b""")),
        "Adventure" to listOf(Regex("""\badventure\b""")),
        "Animation" to listOf(Regex("""\banimat(?:ion|ed)\b""")),
        "Comedy" to listOf(Regex("""\bcomed(?:y|ies|ic)\b"""), Regex("""\bfunny\b""")),
        "Crime" to listOf(Regex("""\bcrime\b"""), Regex("""\bgangster\b""")),
        "Documentary" to listOf(Regex("""\bdocumentar(?:y|ies)\b""")),
        "Drama" to listOf(Regex("""\bdrama\b""")),
        "Family" to listOf(Regex("""\bfamily\b"""), Regex("""\bkid(?:s)?\b""")),
        "Fantasy" to listOf(Regex("""\bfantasy\b"""), Regex("""\bmagical\b""")),
        "History" to listOf(Regex("""\bhistor(?:y|ical)\b""")),
        "Horror" to listOf(Regex("""\bhorror\b"""), Regex("""\bscary\b""")),
        "Music" to listOf(Regex("""\bmusic(?:al)?\b""")),
        "Mystery" to listOf(Regex("""\bmyster(?:y|ies|ious)\b""")),
        "Romance" to listOf(Regex("""\bromance\b"""), Regex("""\bromantic\b""")),
        "Science Fiction" to listOf(Regex("""\bsci[- ]?fi\b"""), Regex("""\bscience fiction\b""")),
        "Thriller" to listOf(Regex("""\bthriller\b"""), Regex("""\bsuspense\b""")),
        "War" to listOf(Regex("""\bwar\b""")),
        "Western" to listOf(Regex("""\bwestern\b""")),
    )

    private val moodAliases: Map<RecommendationMood, List<Regex>> = mapOf(
        RecommendationMood.FUNNY to listOf(Regex("""\bfunny\b"""), Regex("""\blaugh\b""")),
        RecommendationMood.SCARY to listOf(
            Regex("""\bscary\b"""),
            Regex("""\bcreepy\b"""),
            Regex("""\bterrifying\b"""),
        ),
        RecommendationMood.EMOTIONAL to listOf(
            Regex("""\bemotional\b"""),
            Regex("""\btearjerker\b"""),
            Regex("""\bcry\b"""),
        ),
        RecommendationMood.RELAXING to listOf(
            Regex("""\brelax(?:ing|ed)?\b"""),
            Regex("""\bcozy\b"""),
            Regex("""\bcalm\b"""),
        ),
        RecommendationMood.MIND_BENDING to listOf(
            Regex("""\bmind[- ]?bending\b"""),
            Regex("""\bwhat the hell\b"""),
            Regex("""\btwist[- ]?heavy\b"""),
        ),
        RecommendationMood.INTENSE to listOf(Regex("""\bintense\b"""), Regex("""\btense\b""")),
        RecommendationMood.ROMANTIC to listOf(Regex("""\bromantic\b"""), Regex("""\bdate night\b""")),
        RecommendationMood.EXCITING to listOf(
            Regex("""\bexciting\b"""),
            Regex("""\bthrilling\b"""),
            Regex("""\badrenaline\b"""),
        ),
        RecommendationMood.DARK to listOf(Regex("""\bdark(?:er)?\b"""), Regex("""\bbleak\b""")),
        RecommendationMood.FEEL_GOOD to listOf(
            Regex("""\bfeel[- ]?good\b"""),
            Regex("""\buplifting\b"""),
        ),
        RecommendationMood.THOUGHT_PROVOKING to listOf(
            Regex("""\bthought[- ]?provoking\b"""),
            Regex("""\bintellectual\b"""),
        ),
        RecommendationMood.NOSTALGIC to listOf(Regex("""\bnostalgi(?:a|c)\b""")),
    )

    private val viewingAliases: Map<ViewingContext, List<Regex>> = mapOf(
        ViewingContext.ALONE to listOf(Regex("""\b(?:alone|by myself|solo)\b""")),
        ViewingContext.PARTNER to listOf(Regex("""\b(?:partner|boyfriend|girlfriend|spouse|date)\b""")),
        ViewingContext.FRIENDS to listOf(Regex("""\b(?:friends|mates)\b""")),
        ViewingContext.FAMILY to listOf(Regex("""\b(?:with family|family night)\b""")),
        ViewingContext.CHILDREN to listOf(Regex("""\b(?:children|kids|child)\b""")),
        ViewingContext.GROUP to listOf(Regex("""\b(?:group|crowd|people)\b""")),
    )

    private val familiarityAliases: Map<FamiliarityPreference, List<Regex>> = mapOf(
        FamiliarityPreference.POPULAR to listOf(
            Regex("""\bpopular\b"""),
            Regex("""\bblockbuster\b"""),
        ),
        FamiliarityPreference.HIDDEN_GEM to listOf(Regex("""\bhidden gem\b""")),
        FamiliarityPreference.OBSCURE to listOf(
            Regex("""\bobscure\b"""),
            Regex("""\bnever heard of\b"""),
        ),
        FamiliarityPreference.FAMILIAR to listOf(
            Regex("""\bfamous\b"""),
            Regex("""\beveryone knows\b"""),
        ),
    )

    private val languageAliases = mapOf(
        "English" to listOf("english"),
        "German" to listOf("german", "deutsch"),
        "Arabic" to listOf("arabic"),
        "Korean" to listOf("korean"),
        "Japanese" to listOf("japanese"),
        "French" to listOf("french"),
        "Spanish" to listOf("spanish"),
        "Italian" to listOf("italian"),
        "Hindi" to listOf("hindi"),
        "Portuguese" to listOf("portuguese"),
        "Chinese" to listOf("chinese", "mandarin", "cantonese"),
        "Turkish" to listOf("turkish"),
        "Persian" to listOf("persian", "farsi"),
        "Swedish" to listOf("swedish"),
        "Danish" to listOf("danish"),
        "Norwegian" to listOf("norwegian"),
        "Polish" to listOf("polish"),
        "Russian" to listOf("russian"),
    )

    private val countryAliases = mapOf(
        "Japan" to listOf("japanese", "from japan"),
        "South Korea" to listOf("korean", "south korea"),
        "France" to listOf("french", "from france"),
        "Germany" to listOf("german", "from germany"),
        "Spain" to listOf("spanish", "from spain"),
        "Italy" to listOf("italian", "from italy"),
        "India" to listOf("indian", "from india"),
        "Egypt" to listOf("egyptian", "from egypt"),
        "Lebanon" to listOf("lebanese", "from lebanon"),
        "Iran" to listOf("iranian", "persian", "from iran"),
        "Turkey" to listOf("turkish", "from turkey"),
        "Ireland" to listOf("irish", "from ireland"),
        "United Kingdom" to listOf("british", "from the uk"),
    )

    private val unverifiablePatterns = mapOf(
        "dubbing availability" to Regex("""\b(?:dubbed|dubbing)\b"""),
        "subtitle availability" to Regex("""\bsubtitles?\b"""),
        "sexual-content level" to Regex("""\bsexual content\b"""),
        "gore level" to Regex("""\bgore|gory\b"""),
        "profanity level" to Regex("""\bprofanity|swearing\b"""),
        "ending type" to Regex("""\b(?:happy|sad|ambiguous|open[- ]ended) ending\b"""),
        "Rotten Tomatoes audience score" to Regex("""\b(?:rt|rotten tomatoes) audience\b"""),
    )

    private val unmatchedRemovalPatterns = listOf(
        movieWords,
        tvWords,
        Regex("""\b(?:want|watch|looking for|find me|show me|something|please|tonight|today)\b"""),
        Regex("""\b(?:imdb|tmdb|rotten tomatoes|rt)\s*(?:rating|score)?\s*\d+(?:\.\d+)?\s*\+?"""),
        Regex("""\b\d+(?:\.\d+)?\s*\+?\s*(?:imdb|tmdb|rotten tomatoes|rt)\b"""),
        Regex("""\b(?:under|over|around|about|at least|at most|less than|more than)\s+\d+\s*(?:minutes?|mins?|hours?|hrs?)\b"""),
        Regex("""\b(?:after|before|since|from|newer than|older than)\s+(?:19|20)\d{2}\b"""),
        Regex("""\b(?:must|only|strictly|required|prefer|maybe|kind of|sort of)\b"""),
    )

    private val unmatchedStopWords = setOf(
        "the", "and", "with", "without", "but", "for", "from", "that", "this",
        "have", "has", "very", "more", "less", "than", "into", "where", "when",
        "what", "which", "while", "like", "similar", "made", "released", "rated",
        "movie", "film", "show", "series", "episode", "episodes",
    )

    private const val MAX_UNMATCHED_PREFERENCES = 12
}
