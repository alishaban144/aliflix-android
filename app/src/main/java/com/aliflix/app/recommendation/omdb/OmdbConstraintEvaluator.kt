package com.aliflix.app.recommendation.omdb

import com.aliflix.app.data.omdb.OmdbTitleMetadata
import com.aliflix.app.model.MediaType

data class OmdbEvaluationResult(
    val accepted: Boolean,
    val failedConstraints: List<String> = emptyList(),
    val matchedConstraints: List<String> = emptyList()
)

object OmdbConstraintEvaluator {

    fun evaluate(spec: OmdbRecommendationSpec, metadata: OmdbTitleMetadata?): OmdbEvaluationResult {
        if (metadata == null || !metadata.found) {
            return OmdbEvaluationResult(
                accepted = false,
                failedConstraints = listOf("metadata_not_found")
            )
        }

        val failed = mutableListOf<String>()
        val matched = mutableListOf<String>()

        // 1. Media Type Check
        val metaType = metadata.type?.lowercase()
        val expectedTypeMatch = when (spec.mediaType) {
            MediaType.MOVIE -> metaType == "movie"
            MediaType.TV -> metaType == "series" || metaType == "tv" || metaType == "show"
        }
        if (!expectedTypeMatch) {
            failed.add("type_mismatch (expected ${spec.mediaType.name.lowercase()}, got $metaType)")
        } else {
            matched.add("type: ${spec.mediaType.name.lowercase()}")
        }

        // Normalize OMDb metadata genres
        val normalizedMetaGenres = metadata.genres.map { OmdbGenre.normalizeName(it) }.toSet()

        // 2. Included Genres (ALL required - AND logic)
        for (requiredGenre in spec.normalizedIncludedGenres) {
            if (normalizedMetaGenres.none { it.equals(requiredGenre, ignoreCase = true) }) {
                failed.add("missing_included_genre: $requiredGenre")
            } else {
                matched.add("genre: $requiredGenre")
            }
        }

        // 3. Excluded Genres (NONE allowed - ANY causes rejection)
        for (excludedGenre in spec.normalizedExcludedGenres) {
            if (normalizedMetaGenres.any { it.equals(excludedGenre, ignoreCase = true) }) {
                failed.add("contains_excluded_genre: $excludedGenre")
            }
        }

        // 4. Year bounds
        if (spec.minimumYear != null) {
            val yr = metadata.year
            if (yr == null || yr < spec.minimumYear) {
                failed.add("minimum_year_failed (min=${spec.minimumYear}, actual=$yr)")
            } else {
                matched.add("year >= ${spec.minimumYear}")
            }
        }
        if (spec.maximumYear != null) {
            val yr = metadata.year
            if (yr == null || yr > spec.maximumYear) {
                failed.add("maximum_year_failed (max=${spec.maximumYear}, actual=$yr)")
            } else {
                matched.add("year <= ${spec.maximumYear}")
            }
        }

        // 5. Runtime bounds
        if (spec.minimumRuntimeMinutes != null) {
            val rt = metadata.runtimeMinutes
            if (rt == null || rt < spec.minimumRuntimeMinutes) {
                failed.add("minimum_runtime_failed (min=${spec.minimumRuntimeMinutes}, actual=$rt)")
            } else {
                matched.add("runtime >= ${spec.minimumRuntimeMinutes} min")
            }
        }
        if (spec.maximumRuntimeMinutes != null) {
            val rt = metadata.runtimeMinutes
            if (rt == null || rt > spec.maximumRuntimeMinutes) {
                failed.add("maximum_runtime_failed (max=${spec.maximumRuntimeMinutes}, actual=$rt)")
            } else {
                matched.add("runtime <= ${spec.maximumRuntimeMinutes} min")
            }
        }

        // 6. IMDb Rating
        if (spec.minimumImdbRating != null) {
            val imdb = metadata.imdbRating
            if (imdb == null || imdb < spec.minimumImdbRating) {
                failed.add("minimum_imdb_failed (min=${spec.minimumImdbRating}, actual=$imdb)")
            } else {
                matched.add("IMDb $imdb >= ${spec.minimumImdbRating}")
            }
        }

        // 7. IMDb Votes
        if (spec.minimumImdbVotes != null) {
            val votes = metadata.imdbVotes
            if (votes == null || votes < spec.minimumImdbVotes) {
                failed.add("minimum_imdb_votes_failed (min=${spec.minimumImdbVotes}, actual=$votes)")
            } else {
                matched.add("IMDb votes $votes >= ${spec.minimumImdbVotes}")
            }
        }

        // 8. Rotten Tomatoes Rating
        if (spec.minimumRottenTomatoesRating != null) {
            val rt = metadata.rottenTomatoesRating
            if (rt == null || rt < spec.minimumRottenTomatoesRating) {
                failed.add("minimum_rt_failed (min=${spec.minimumRottenTomatoesRating}, actual=$rt)")
            } else {
                matched.add("RT $rt% >= ${spec.minimumRottenTomatoesRating}%")
            }
        }

        // 9. Metascore
        if (spec.minimumMetascore != null) {
            val meta = metadata.metascore
            if (meta == null || meta < spec.minimumMetascore) {
                failed.add("minimum_metascore_failed (min=${spec.minimumMetascore}, actual=$meta)")
            } else {
                matched.add("Metascore $meta >= ${spec.minimumMetascore}")
            }
        }

        // 10. Content Rating (OR logic within selected)
        if (spec.contentRatings.isNotEmpty()) {
            val actualRating = metadata.contentRating?.trim()
            if (actualRating.isNullOrBlank() || spec.contentRatings.none { it.equals(actualRating, ignoreCase = true) }) {
                failed.add("content_rating_failed (allowed=${spec.contentRatings}, actual=$actualRating)")
            } else {
                matched.add("contentRating: $actualRating")
            }
        }

        // 11. Languages (ANY logic)
        if (spec.languages.isNotEmpty()) {
            val metaLangs = metadata.languages.map { it.lowercase() }
            val hasLangMatch = spec.languages.any { reqLang ->
                metaLangs.any { it.contains(reqLang.lowercase()) }
            }
            if (!hasLangMatch) {
                failed.add("language_failed (allowed=${spec.languages}, actual=${metadata.languages})")
            } else {
                matched.add("language match")
            }
        }

        // 12. Countries (ANY logic)
        if (spec.countries.isNotEmpty()) {
            val metaCountries = metadata.countries.map { it.lowercase() }
            val hasCountryMatch = spec.countries.any { reqCountry ->
                metaCountries.any { it.contains(reqCountry.lowercase()) }
            }
            if (!hasCountryMatch) {
                failed.add("country_failed (allowed=${spec.countries}, actual=${metadata.countries})")
            } else {
                matched.add("country match")
            }
        }

        // 13. Actors (ALL required)
        if (spec.actors.isNotEmpty()) {
            val metaActors = metadata.actors.map { it.lowercase() }
            for (reqActor in spec.actors) {
                val foundActor = metaActors.any { it.contains(reqActor.lowercase()) }
                if (!foundActor) {
                    failed.add("actor_missing: $reqActor")
                } else {
                    matched.add("actor: $reqActor")
                }
            }
        }

        // 14. Directors (ALL required)
        if (spec.directors.isNotEmpty()) {
            val metaDirector = metadata.director?.lowercase() ?: ""
            for (reqDir in spec.directors) {
                if (!metaDirector.contains(reqDir.lowercase())) {
                    failed.add("director_missing: $reqDir")
                } else {
                    matched.add("director: $reqDir")
                }
            }
        }

        // 15. Writers (ALL required)
        if (spec.writers.isNotEmpty()) {
            val metaWriters = metadata.writers.map { it.lowercase() }
            for (reqWriter in spec.writers) {
                val foundWriter = metaWriters.any { it.contains(reqWriter.lowercase()) }
                if (!foundWriter) {
                    failed.add("writer_missing: $reqWriter")
                } else {
                    matched.add("writer: $reqWriter")
                }
            }
        }

        // 16. Seasons (Series only)
        if (spec.mediaType == MediaType.TV) {
            if (spec.minimumSeasons != null) {
                val seasons = metadata.totalSeasons
                if (seasons == null || seasons < spec.minimumSeasons) {
                    failed.add("minimum_seasons_failed (min=${spec.minimumSeasons}, actual=$seasons)")
                } else {
                    matched.add("seasons >= ${spec.minimumSeasons}")
                }
            }
            if (spec.maximumSeasons != null) {
                val seasons = metadata.totalSeasons
                if (seasons == null || seasons > spec.maximumSeasons) {
                    failed.add("maximum_seasons_failed (max=${spec.maximumSeasons}, actual=$seasons)")
                } else {
                    matched.add("seasons <= ${spec.maximumSeasons}")
                }
            }
        }

        return OmdbEvaluationResult(
            accepted = failed.isEmpty(),
            failedConstraints = failed,
            matchedConstraints = matched
        )
    }
}
