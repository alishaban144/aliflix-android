package com.aliflix.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aliflix.app.data.CatalogClient
import com.aliflix.app.data.AndroidCatalogCacheStore
import com.aliflix.app.data.LibraryStore
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaCreator
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackPreferences
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.Season
import com.aliflix.app.recommendation.CatalogRecommendationCandidateRepository
import com.aliflix.app.recommendation.RecommendationOrchestrator
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.PreferenceCorrection
import com.aliflix.app.recommendation.RecommendationQuestion
import com.aliflix.app.recommendation.RecommendationDispatchers
import com.aliflix.app.recommendation.RecommendationStore
import com.aliflix.app.recommendation.RecommendationUiState
import com.aliflix.app.recommendation.RecommendationRequestDraft
import com.aliflix.app.recommendation.AndroidSemanticModelManager
import com.aliflix.app.recommendation.SemanticModelState
import com.aliflix.app.recommendation.V3CatalogMedia
import com.aliflix.app.recommendation.V3TitleDetails
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val loading: Boolean = true,
    val content: HomeContent? = null,
    val editorialPicks: List<Media> = emptyList(),
    val error: String? = null,
)

enum class SearchMode {
    TITLE,
    AI,
}

private fun V3CatalogMedia.toMedia(fallback: Media? = null): Media {
    val type = MediaType.from(mediaType)
    return (fallback ?: Media(id = tmdbId, type = type, title = title)).copy(
        id = tmdbId,
        type = type,
        title = title,
        overview = overview ?: fallback?.overview.orEmpty(),
        posterPath = posterPath ?: fallback?.posterPath,
        backdropPath = backdropPath ?: fallback?.backdropPath,
        year = releaseDate?.take(4) ?: fallback?.year.orEmpty(),
        rating = tmdbRating ?: fallback?.rating ?: 0.0,
        tmdbVoteCount = tmdbVoteCount ?: fallback?.tmdbVoteCount,
        genres = genres.ifEmpty { fallback?.genres.orEmpty() },
        originalLanguage = originalLanguage ?: fallback?.originalLanguage.orEmpty(),
        runtime = runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" }
            ?: fallback?.runtime.orEmpty(),
    )
}

private fun V3TitleDetails.toMedia(fallback: Media): Media = media.toMedia(fallback).copy(
    status = status.orEmpty(),
    creators = creators.map { creator ->
        MediaCreator(
            tmdbId = creator.tmdbId,
            name = creator.name,
            profilePath = creator.profilePath,
        )
    },
    cast = cast.map { it.name },
)

enum class SearchPhase {
    IDLE,
    TYPING,
    LOADING,
    RESULTS,
    EMPTY,
    ERROR,
}

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.TITLE,
    val phase: SearchPhase = SearchPhase.IDLE,
    val loading: Boolean = false,
    val results: List<Media> = emptyList(),
    val error: String? = null,
)

data class DetailUiState(
    val loading: Boolean = false,
    val item: Media? = null,
    val recommendations: List<Media> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val selectedSeason: Int = 1,
    val episodes: List<Episode> = emptyList(),
    val episodesLoading: Boolean = false,
    val error: String? = null,
)

data class GenreUiState(
    val genre: String = "",
    val type: MediaType = MediaType.MOVIE,
    val loading: Boolean = false,
    val items: List<Media> = emptyList(),
    val error: String? = null,
)

data class PersonUiState(
    val creator: MediaCreator? = null,
    val loading: Boolean = false,
    val items: List<Media> = emptyList(),
    val error: String? = null,
)

class AliflixViewModel(application: Application) : AndroidViewModel(application) {
    private val recommendationDispatchers = RecommendationDispatchers.Default
    private val omdbCacheStore = com.aliflix.app.data.omdb.OmdbCacheStore(
        context = application,
        ioDispatcher = recommendationDispatchers.io,
    )
    private val omdbClient = com.aliflix.app.data.omdb.OmdbMetadataClient(
        baseUrl = BuildConfig.RECOMMENDATION_AI_BASE_URL,
        cacheStore = omdbCacheStore,
        ioDispatcher = recommendationDispatchers.io,
    )
    private val client = CatalogClient(
        cacheStore = AndroidCatalogCacheStore(
            context = application,
            ioDispatcher = recommendationDispatchers.io,
            computationDispatcher = recommendationDispatchers.computation,
        ),
        omdbClientOverride = omdbClient,
        ioDispatcher = recommendationDispatchers.io,
        computationDispatcher = recommendationDispatchers.computation,
    )
    private val library = LibraryStore(application)
    private val playbackProviderRepository = PlaybackProviderRepository(application)
    private val recommendationStore = RecommendationStore(
        context = application,
        scope = viewModelScope,
        dispatchers = recommendationDispatchers,
    )
    private val semanticModelManager = AndroidSemanticModelManager(
        context = application,
        scope = viewModelScope,
        ioDispatcher = recommendationDispatchers.io,
    )
    private val aiClient = com.aliflix.app.recommendation.RecommendationAiClient(
        baseUrl = BuildConfig.RECOMMENDATION_AI_BASE_URL,
        ioDispatcher = recommendationDispatchers.io
    )
    private val recommendationOrchestrator = RecommendationOrchestrator(
        scope = viewModelScope,
        repository = CatalogRecommendationCandidateRepository(client, aiClient, omdbClient),
        store = recommendationStore,
        likesProvider = { library.likes.value },
        recentlyPlayedProvider = { library.recent.value },
        semanticBatchScorerProvider = semanticModelManager::batchScorerOrNull,
        dispatchers = recommendationDispatchers,
        aiClient = aiClient,
        omdbClient = omdbClient,
    )
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var episodeJob: Job? = null
    private var genreJob: Job? = null
    private var personJob: Job? = null
    private var homeRefreshJob: Job? = null
    private var lastHomeRefreshAt = 0L

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _detail = MutableStateFlow(DetailUiState())
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private val _genre = MutableStateFlow(GenreUiState())
    val genre: StateFlow<GenreUiState> = _genre.asStateFlow()

    private val _person = MutableStateFlow(PersonUiState())
    val person: StateFlow<PersonUiState> = _person.asStateFlow()

    val myList = library.myList
    val recent = library.recent
    val likes = library.likes



    private val _askUiState = MutableStateFlow<com.aliflix.app.ui.discover.AskAliflixUiState>(com.aliflix.app.ui.discover.AskAliflixUiState.Editing)
    val askUiState: StateFlow<com.aliflix.app.ui.discover.AskAliflixUiState> = _askUiState.asStateFlow()

    private val _askEditorState = MutableStateFlow(com.aliflix.app.ui.discover.AskAliflixEditorState())
    val askEditorState: StateFlow<com.aliflix.app.ui.discover.AskAliflixEditorState> = _askEditorState.asStateFlow()

    private var activeAskJob: Job? = null
    private var askSessionToken = 0L
    private var activeAskRequest: com.aliflix.app.recommendation.V3RecommendationRequest? = null
    private var activeAskSummary: String? = null
    private var activeAskSpec: com.aliflix.app.recommendation.CatalogDiscoverySpec? = null

    fun submitAskAliflix(request: com.aliflix.app.ui.discover.AskAliflixRequest) {
        pauseBackgroundHomeRefresh()
        activeAskJob?.cancel()
        val token = ++askSessionToken
        
        val mapped = com.aliflix.app.ui.discover.AskAliflixRequestMapper.map(request)
        val summary = mapped.summary
        activeAskRequest = mapped.workerRequest
        activeAskSummary = summary
        activeAskSpec = mapped.spec

        _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Searching(summary)
        activeAskJob = viewModelScope.launch {
            try {
                val clientAi = aiClient
                val response = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    clientAi.getRecommendations(mapped.workerRequest)
                }
                
                if (token != askSessionToken) return@launch

                val candidates = response.results.mapIndexed(::mapAskResult)

                if (candidates.isEmpty()) {
                    _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Empty(summary, "No titles found.")
                } else {
                    _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Results(
                        requestSummary = summary,
                        spec = mapped.spec,
                        items = candidates,
                        hasMore = response.hasMore,
                        nextCursor = response.nextCursor,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (token != askSessionToken) return@launch
                _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Error(summary, e.message ?: "Failed to find titles")
            }
        }
    }

    fun editAskAliflix() {
        _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Editing
    }

    fun resetAskAliflix() {
        activeAskJob?.cancel()
        activeAskJob = null
        askSessionToken++
        activeAskRequest = null
        activeAskSummary = null
        activeAskSpec = null
        _askEditorState.value = com.aliflix.app.ui.discover.AskAliflixEditorState()
        _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Editing
    }

    fun setAskEditorState(state: com.aliflix.app.ui.discover.AskAliflixEditorState) {
        _askEditorState.value = state
    }

    fun loadMoreAskAliflix() {
        val currentResults = _askUiState.value as? com.aliflix.app.ui.discover.AskAliflixUiState.Results ?: return
        val original = activeAskRequest ?: return
        val cursor = currentResults.nextCursor ?: return
        if (currentResults.loadingMore || !currentResults.hasMore) return

        val token = askSessionToken
        _askUiState.value = currentResults.copy(loadingMore = true)

        activeAskJob = viewModelScope.launch {
            try {
                val response = aiClient.getRecommendations(original.copy(cursor = cursor))
                if (token != askSessionToken) return@launch
                val appended = (currentResults.items + response.results.mapIndexed(::mapAskResult))
                    .distinctBy { it.media.key }
                _askUiState.value = currentResults.copy(
                    items = appended,
                    loadingMore = false,
                    hasMore = response.hasMore,
                    nextCursor = response.nextCursor,
                    loadMoreError = null,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token != askSessionToken) return@launch
                _askUiState.value = currentResults.copy(loadingMore = false, loadMoreError = error.message ?: "Could not load more matches")
            }
        }
    }

    fun retryAskAliflix() {
        val original = activeAskRequest ?: return
        val summary = activeAskSummary ?: return
        val spec = activeAskSpec ?: return
        activeAskJob?.cancel()
        val token = ++askSessionToken
        _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Searching(summary)
        activeAskJob = viewModelScope.launch {
            try {
                val response = aiClient.getRecommendations(original.copy(cursor = null))
                if (token != askSessionToken) return@launch
                val candidates = response.results.mapIndexed(::mapAskResult)
                _askUiState.value = if (candidates.isEmpty()) {
                    com.aliflix.app.ui.discover.AskAliflixUiState.Empty(summary, "No titles found.")
                } else {
                    com.aliflix.app.ui.discover.AskAliflixUiState.Results(summary, spec, candidates, hasMore = response.hasMore, nextCursor = response.nextCursor)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == askSessionToken) _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Error(summary, error.message ?: "Failed to find titles")
            }
        }
    }

    private fun mapAskResult(index: Int, result: com.aliflix.app.recommendation.V3RecommendationResult) =
        com.aliflix.app.recommendation.RecommendationCandidate(
            media = com.aliflix.app.model.Media(
                id = result.tmdbId,
                title = result.title,
                overview = result.overview.orEmpty(),
                posterPath = result.posterPath,
                backdropPath = result.backdropPath,
                type = com.aliflix.app.model.MediaType.from(result.mediaType),
                year = result.releaseDate?.take(4).orEmpty(),
                rating = result.tmdbRating ?: 0.0,
                genres = result.genres,
                runtime = result.runtimeMinutes?.let { "$it min" }.orEmpty(),
            ),
            metadata = com.aliflix.app.recommendation.VerifiedMediaMetadata(
                genresVerified = true,
                runtimeMinutes = result.runtimeMinutes,
                originalLanguage = result.originalLanguage,
                originCountries = result.originCountries,
                tmdbVoteCount = result.tmdbVoteCount,
                verifiedAtMillis = System.currentTimeMillis(),
            ),
            evidence = result.overview.orEmpty(),
            sources = result.retrievalSources.toSet(),
            sourceCount = result.retrievalSources.size,
            sourcePosition = index,
            score = com.aliflix.app.recommendation.RecommendationScoreBreakdown(
                semanticRelevance = result.finalScore,
                confidence = result.finalScore,
                total = result.finalScore,
                finalScore = result.finalScore,
            ),
            explanation = result.matchLevel,
            precomputedSemanticScore = result.finalScore,
            alternativeTitles = setOfNotNull(result.originalTitle).filterNot { it == result.title }.toSet(),
        )

    val playbackPreferences: StateFlow<PlaybackPreferences> =
        playbackProviderRepository.preferences
    val recommendation: StateFlow<RecommendationUiState> =
        recommendationOrchestrator.state
    val aiRecommendationsEnabled: StateFlow<Boolean> =
        recommendationStore.enabled
    val semanticModelState: StateFlow<SemanticModelState> =
        semanticModelManager.state
    val shouldOfferSemanticModel: StateFlow<Boolean> =
        semanticModelManager.shouldOfferDownload

    fun selectGeneralPlaybackProvider(provider: PlaybackProviderId) =
        playbackProviderRepository.selectGeneralProvider(provider)

    fun updateRamoflixUrl(newUrl: String) =
        playbackProviderRepository.updateRamoflixUrl(newUrl)

    fun resetRamoflixUrl() = playbackProviderRepository.resetRamoflixUrl()

    fun updateMoviepireUrl(newUrl: String) =
        playbackProviderRepository.updateMoviepireUrl(newUrl)

    fun resetMoviepireUrl() = playbackProviderRepository.resetMoviepireUrl()

    fun updateDorabyUrl(newUrl: String) =
        playbackProviderRepository.updateDorabyUrl(newUrl)

    fun resetDorabyUrl() = playbackProviderRepository.resetDorabyUrl()

    init {
        refreshHome()
        val connectivityManager = application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkRequest = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                refreshHomeIfStale()
            }
        })
        viewModelScope.launch {
            while (true) {
                delay(HOME_REFRESH_INTERVAL_MS)
                refreshHomeInternal(force = true, showLoading = false)
            }
        }
    }

    fun refreshHome() = refreshHomeInternal(force = true, showLoading = true)

    fun refreshHomeIfStale() =
        refreshHomeInternal(force = false, showLoading = false)

    private fun refreshHomeInternal(force: Boolean, showLoading: Boolean) {
        if (!force && System.currentTimeMillis() - lastHomeRefreshAt < HOME_STALE_AFTER_MS) return
        if (homeRefreshJob?.isActive == true) return
        homeRefreshJob = viewModelScope.launch {
            val previous = _home.value
            _home.value = previous.copy(
                loading = showLoading && previous.content == null,
                error = null,
            )
            if (!BuildConfig.IS_TV) {
                _home.value = runCatching {
                    aiClient.getHomeFeed().toStableMobileHome(
                        previousContent = previous.content,
                        previousEditorialPicks = previous.editorialPicks,
                    )
                }.fold(
                    onSuccess = { snapshot ->
                        lastHomeRefreshAt = System.currentTimeMillis()
                        HomeUiState(
                            loading = false,
                            content = snapshot.content,
                            editorialPicks = snapshot.editorialPicks,
                        )
                    },
                    onFailure = { error ->
                        HomeUiState(
                            loading = false,
                            content = previous.content,
                            editorialPicks = previous.editorialPicks,
                            error = if (previous.content == null) {
                                error.message ?: "Unable to load TMDB Home."
                            } else {
                                null
                            },
                        )
                    },
                )
                return@launch
            }
            val editorialRequest = async {
                runCatching {
                    aiClient.getEditorialPicks()
                        .map { it.toMedia() }
                        .distinctBy(Media::key)
                }.getOrDefault(previous.editorialPicks)
            }
            val homeResult = runCatching {
                client.home { partial ->
                    _home.value = HomeUiState(
                        loading = false,
                        content = partial,
                        editorialPicks = previous.editorialPicks,
                    )
                }
            }
            val editorialPicks = editorialRequest.await()
            _home.value = homeResult
                .fold(
                    onSuccess = {
                        lastHomeRefreshAt = System.currentTimeMillis()
                        HomeUiState(
                            loading = false,
                            content = it,
                            editorialPicks = editorialPicks,
                        )
                    },
                    onFailure = {
                        HomeUiState(
                            loading = false,
                            content = previous.content,
                            editorialPicks = editorialPicks,
                            error = if (previous.content == null) {
                                it.message ?: "Unable to load the Aliflix catalogue."
                            } else {
                                null
                            },
                        )
                    },
                )
        }
    }

    fun updateSearch(query: String) {
        searchJob?.cancel()
        val current = _search.value
        val mode = current.mode
        if (mode == SearchMode.AI) {
            _search.value = current.copy(
                query = query,
                phase = if (query.isBlank()) SearchPhase.IDLE else SearchPhase.TYPING,
                loading = false,
                error = null,
            )
            return
        }
        if (query.isBlank()) {
            _search.value = current.copy(
                query = "",
                phase = SearchPhase.IDLE,
                loading = false,
                error = null,
            )
            return
        }
        _search.value = current.copy(
            query = query,
            phase = SearchPhase.TYPING,
            loading = false,
            error = null,
        )
        searchJob = viewModelScope.launch(com.aliflix.app.data.ForegroundRequestPriorityElement) {
            try {
                delay(220)
                if (_search.value.query != query || _search.value.mode != mode) {
                    return@launch
                }
                val loading = _search.value.copy(
                    phase = SearchPhase.LOADING,
                    loading = true,
                )
                _search.value = loading
                val results = client.search(query)
                if (_search.value.query == query && _search.value.mode == mode) {
                    val complete = _search.value.copy(
                        query = query,
                        mode = mode,
                        phase = if (results.isEmpty()) SearchPhase.EMPTY else SearchPhase.RESULTS,
                        loading = false,
                        results = results,
                        error = null,
                    )
                    _search.value = complete
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_search.value.query == query && _search.value.mode == mode) {
                    val failed = _search.value.copy(
                        phase = SearchPhase.ERROR,
                        loading = false,
                        error = error.message ?: "Search failed.",
                    )
                    _search.value = failed
                }
            }
        }
    }

    fun submitCatalogueSearch(query: String) {
        val trimmed = query.trim()
        searchJob?.cancel()
        val current = _search.value
        val mode = current.mode
        
        if (mode == SearchMode.AI) return
        
        if (trimmed.isBlank()) {
            _search.value = current.copy(
                query = "",
                phase = SearchPhase.IDLE,
                loading = false,
                error = null,
            )
            return
        }
        
        _search.value = current.copy(
            query = trimmed,
            phase = SearchPhase.LOADING,
            loading = true,
            error = null,
        )
        
        searchJob = viewModelScope.launch(com.aliflix.app.data.ForegroundRequestPriorityElement) {
            try {
                val results = client.search(trimmed)
                if (_search.value.query == trimmed && _search.value.mode == mode) {
                    val complete = _search.value.copy(
                        query = trimmed,
                        mode = mode,
                        phase = if (results.isEmpty()) SearchPhase.EMPTY else SearchPhase.RESULTS,
                        loading = false,
                        results = results,
                        error = null,
                    )
                    _search.value = complete
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_search.value.query == trimmed && _search.value.mode == mode) {
                    val failed = _search.value.copy(
                        phase = SearchPhase.ERROR,
                        loading = false,
                        error = error.message ?: "Search failed.",
                    )
                    _search.value = failed
                }
            }
        }
    }

    suspend fun searchTitles(query: String): List<Media> = client.search(query.trim())

    fun selectSearchMode(mode: SearchMode) {
        if (mode == SearchMode.AI && !recommendationStore.enabled.value) return
        if (_search.value.mode == mode) return
        searchJob?.cancel()
        val query = _search.value.query
        _search.value = _search.value.copy(
            mode = mode,
            phase = if (query.isBlank()) SearchPhase.IDLE else SearchPhase.TYPING,
            loading = false,
            error = null,
        )
        if (mode == SearchMode.TITLE && query.isNotBlank()) {
            updateSearch(query)
        }
    }

    fun openGenre(genre: String, type: MediaType) {
        genreJob?.cancel()
        _genre.value = GenreUiState(
            genre = genre,
            type = type,
            loading = true,
        )
        genreJob = viewModelScope.launch(com.aliflix.app.data.ForegroundRequestPriorityElement) {
            _genre.value = runCatching { client.browseGenre(genre, type) }
                .fold(
                    onSuccess = { items ->
                        if (items.size >= MIN_GENRE_RESULTS) {
                            GenreUiState(
                                genre = genre,
                                type = type,
                                items = items,
                            )
                        } else {
                            GenreUiState(
                                genre = genre,
                                type = type,
                                error = "This genre could not be filled yet. Check your connection and retry.",
                            )
                        }
                    },
                    onFailure = { error ->
                        GenreUiState(
                            genre = genre,
                            type = type,
                            error = error.message ?: "This genre could not be loaded.",
                        )
                    },
                )
        }
    }

    fun retryGenre() {
        val current = _genre.value
        if (current.genre.isNotBlank()) openGenre(current.genre, current.type)
    }

    fun closeGenre() {
        genreJob?.cancel()
        _genre.value = GenreUiState()
    }

    fun openDetails(item: Media) {
        detailJob?.cancel()
        episodeJob?.cancel()
        _detail.value = DetailUiState(loading = true, item = item)
        detailJob = viewModelScope.launch(com.aliflix.app.data.ForegroundRequestPriorityElement) {
            try {
                val tmdbDetails = runCatching {
                    aiClient.getTitleDetails(item.type.routeName, item.id)
                }.getOrNull()
                val authoritativeItem = if (!BuildConfig.IS_TV) {
                    tmdbDetails?.toStableMobileMedia(item) ?: item
                } else {
                    tmdbDetails?.toMedia(item) ?: item
                }
                _detail.value = _detail.value.copy(item = authoritativeItem)

                val seasonsRequest = async {
                    if (authoritativeItem.type == MediaType.TV) {
                        client.seasons(authoritativeItem)
                    } else {
                        emptyList()
                    }
                }

                launch {
                    val seasons = seasonsRequest.await()
                    val selectedSeason = seasons.firstOrNull()?.number ?: 1
                    _detail.value = _detail.value.copy(
                        seasons = seasons,
                        selectedSeason = selectedSeason,
                        episodesLoading = authoritativeItem.type == MediaType.TV,
                    )
                    
                    if (authoritativeItem.type == MediaType.TV) {
                        val currentItem = _detail.value.item ?: authoritativeItem
                        val episodes = client.episodes(currentItem, selectedSeason)
                        _detail.value = _detail.value.copy(
                            episodes = episodes,
                            episodesLoading = false,
                        )
                    }
                }

                client.details(authoritativeItem) { details, recommendations ->
                    val displayDetails = if (!BuildConfig.IS_TV) {
                        authoritativeItem.mergeStableMobileDetailUpdate(details)
                    } else {
                        details
                    }
                    library.refreshMetadata(displayDetails)
                    _detail.value = _detail.value.copy(
                        loading = false,
                        item = displayDetails,
                        recommendations = recommendations ?: emptyList(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _detail.value = _detail.value.copy(
                    loading = false,
                    error = error.message,
                )
            }
        }
    }

    fun selectSeason(number: Int) {
        val current = _detail.value
        val item = current.item ?: return
        if (item.type != MediaType.TV || number == current.selectedSeason) return
        episodeJob?.cancel()
        _detail.value = current.copy(
            selectedSeason = number,
            episodes = emptyList(),
            episodesLoading = true,
            error = null,
        )
        episodeJob = viewModelScope.launch(com.aliflix.app.data.ForegroundRequestPriorityElement) {
            _detail.value = runCatching { client.episodes(item, number) }
                .fold(
                    onSuccess = { episodes ->
                        _detail.value.copy(
                            episodes = episodes,
                            episodesLoading = false,
                        )
                    },
                    onFailure = {
                        _detail.value.copy(
                            episodesLoading = false,
                            error = it.message ?: "Episodes could not be loaded.",
                        )
                    },
                )
        }
    }

    fun closeDetails() {
        detailJob?.cancel()
        episodeJob?.cancel()
        _detail.value = DetailUiState()
    }

    fun openPerson(creator: MediaCreator) {
        personJob?.cancel()
        _person.value = PersonUiState(creator = creator, loading = true)
        personJob = viewModelScope.launch {
            _person.value = runCatching {
                aiClient.getPersonCredits(creator.tmdbId)
            }.fold(
                onSuccess = { response ->
                    PersonUiState(
                        creator = creator.copy(
                            name = response.person.name.takeUnless { name ->
                                name.isBlank() || name.equals("Creator", ignoreCase = true)
                            } ?: creator.name,
                            profilePath = response.person.profilePath ?: creator.profilePath,
                        ),
                        items = response.results.map { it.toMedia() }.distinctBy(Media::key),
                    )
                },
                onFailure = { error ->
                    PersonUiState(
                        creator = creator,
                        error = error.message ?: "Creator credits could not be loaded.",
                    )
                },
            )
        }
    }

    fun retryPerson() {
        _person.value.creator?.let(::openPerson)
    }

    fun closePerson() {
        personJob?.cancel()
        _person.value = PersonUiState()
    }

    fun toggleMyList(item: Media) = library.toggleMyList(item)

    fun isInMyList(item: Media): Boolean = library.isInMyList(item)

    fun toggleLike(item: Media) = library.toggleLike(item)

    fun isLiked(item: Media): Boolean = library.isLiked(item)

    fun markPlayed(item: Media) = library.markPlayed(item)

    fun removeRecent(item: Media) = library.removeRecent(item)

    fun clearRecent() = library.clearRecent()

    fun submitRecommendationDraft(draft: RecommendationRequestDraft) {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.submitDraft(draft)
    }

    fun selectRecommendationType(type: RecommendationMediaKind) =
        recommendationOrchestrator.selectType(type)

    fun showRecommendationMatches() {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.showMatches()
    }

    fun loadMoreRecommendations() {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.loadMore()
    }

    fun retryRecommendationPage() {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.retryPage()
    }

    fun surpriseRecommendation() {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.surpriseMe()
    }

    fun answerRecommendation(
        question: RecommendationQuestion,
        values: List<String>,
    ) {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.answer(question, values)
    }

    fun previousRecommendationStep() = recommendationOrchestrator.goBack()

    fun restartRecommendations() = recommendationOrchestrator.restart()

    fun retryRecommendations() {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.retry()
    }

    fun requestAnotherRecommendation(
        media: Media,
        reason: String? = null,
    ) {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.requestAnother(media, reason)
    }

    fun acceptRecommendation(media: Media) =
        recommendationOrchestrator.accept(media)

    fun moreLikeRecommendation(media: Media) =
        recommendationOrchestrator.moreLike(media)

    fun lessLikeRecommendation(media: Media) =
        recommendationOrchestrator.lessLike(media)

    fun markRecommendationSeen(media: Media) =
        recommendationOrchestrator.alreadySeen(media)

    fun correctRecommendationPreference(key: String) =
        recommendationOrchestrator.applyCorrection(
            PreferenceCorrection(key = key, replacement = null),
        )

    fun relaxRecommendationConstraint(id: String) {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.applyRelaxation(id)
    }

    fun setAiRecommendationsEnabled(enabled: Boolean) {
        recommendationStore.setEnabled(enabled)
        if (!enabled) {
            recommendationOrchestrator.restart()
            if (_search.value.mode == SearchMode.AI) {
                selectSearchMode(SearchMode.TITLE)
            }
        }
    }

    fun resetRecommendationTaste() = recommendationOrchestrator.resetTaste()

    fun downloadSemanticModel() = semanticModelManager.download()

    fun dismissSemanticModelOffer() = semanticModelManager.dismissOffer()

    fun deleteSemanticModel() = semanticModelManager.delete()

    private fun pauseBackgroundHomeRefresh() {
        homeRefreshJob?.cancel()
        homeRefreshJob = null
    }

    override fun onCleared() {
        client.close()
        semanticModelManager.close()
    }

    private companion object {
        const val MIN_GENRE_RESULTS = 20
        const val HOME_STALE_AFTER_MS = 5 * 60 * 1_000L
        const val HOME_REFRESH_INTERVAL_MS = 30 * 60 * 1_000L
    }
}
