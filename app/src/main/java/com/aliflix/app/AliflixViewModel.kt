package com.aliflix.app

import android.app.Application
import android.app.Activity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aliflix.app.account.AccountActionResult
import com.aliflix.app.account.createAccountServices
import com.aliflix.app.data.CatalogClient
import com.aliflix.app.data.AndroidCatalogCacheStore
import com.aliflix.app.data.HomeSnapshotStore
import com.aliflix.app.data.AndroidHomeSnapshotStore
import com.aliflix.app.data.PersistedHomeSnapshot
import com.aliflix.app.data.LibraryStore
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.data.PlaybackProgressStore
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaCreator
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackPreferences
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.SubtitleLanguage
import com.aliflix.app.model.Season
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationDispatchers
import com.aliflix.app.recommendation.RecommendationStore
import com.aliflix.app.recommendation.RecommendationAiModel
import com.aliflix.app.recommendation.buildAskAliflixShowMoreRequest
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

data class TvNetworksUiState(
    val loading: Boolean = false,
    val rails: List<ContentRail> = emptyList(),
    val attribution: String = "Streaming availability data by JustWatch",
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
    imdbId = imdbId ?: fallback.imdbId,
    status = status.orEmpty(),
    creators = creators.map { creator ->
        MediaCreator(
            tmdbId = creator.tmdbId,
            name = creator.name,
            profilePath = creator.profilePath,
        )
    },
    cast = cast.map { it.name },
    reviews = reviews.map { review ->
        com.aliflix.app.model.MediaReview(
            id = review.id,
            author = review.author,
            authorName = review.authorName,
            authorUsername = review.authorUsername,
            avatarPath = review.avatarPath,
            rating = review.rating,
            content = review.content,
            createdAt = review.createdAt,
            url = review.url,
        )
    }.ifEmpty { fallback.reviews },
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
    val playbackProgressStore = (application as AliflixApplication).playbackProgressStore
    private val recommendationStore = RecommendationStore(
        context = application,
    )
    private val accountServices = createAccountServices(
        application = application,
        libraryStore = library,
        playbackRepository = playbackProviderRepository,
        playbackProgressStore = playbackProgressStore,
        recommendationStore = recommendationStore,
        scope = viewModelScope,
    )
    private val aiClient = com.aliflix.app.recommendation.RecommendationAiClient(
        baseUrl = BuildConfig.RECOMMENDATION_AI_BASE_URL,
        ioDispatcher = recommendationDispatchers.io
    )
    private val homeSnapshotStore: HomeSnapshotStore = AndroidHomeSnapshotStore(
        context = application,
        ioDispatcher = recommendationDispatchers.io,
        computationDispatcher = recommendationDispatchers.computation,
    )
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var episodeJob: Job? = null
    private var genreJob: Job? = null
    private var personJob: Job? = null
    private var homeRefreshJob: Job? = null
    private var tvNetworksJob: Job? = null
    private var lastHomeRefreshAt = 0L

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _tvNetworks = MutableStateFlow(TvNetworksUiState())
    val tvNetworks: StateFlow<TvNetworksUiState> = _tvNetworks.asStateFlow()

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
    val accountState = accountServices.accountRepository.state
    val accountSyncState = accountServices.syncRepository.state

    private val _askUiState = MutableStateFlow<com.aliflix.app.ui.discover.AskAliflixUiState>(com.aliflix.app.ui.discover.AskAliflixUiState.Editing)
    val askUiState: StateFlow<com.aliflix.app.ui.discover.AskAliflixUiState> = _askUiState.asStateFlow()

    private val _askEditorState = MutableStateFlow(com.aliflix.app.ui.discover.AskAliflixEditorState())
    val askEditorState: StateFlow<com.aliflix.app.ui.discover.AskAliflixEditorState> = _askEditorState.asStateFlow()

    private var activeAskJob: Job? = null
    private var askSessionToken = 0L
    private var activeAskRequest: com.aliflix.app.recommendation.V3RecommendationRequest? = null
    private var activeAskUiRequest: com.aliflix.app.ui.discover.AskAliflixRequest? = null
    private var activeAskSummary: String? = null
    private var activeAskSpec: com.aliflix.app.recommendation.CatalogDiscoverySpec? = null

    fun submitAskAliflix(request: com.aliflix.app.ui.discover.AskAliflixRequest) {
        activeAskJob?.cancel()
        val token = ++askSessionToken
        
        val mapped = com.aliflix.app.ui.discover.AskAliflixRequestMapper.map(
            request = request,
            aiModel = recommendationStore.aiModel.value,
        )
        val summary = mapped.summary
        val workerRequest = mapped.workerRequest

        activeAskRequest = workerRequest
        activeAskUiRequest = request
        activeAskSummary = summary
        activeAskSpec = mapped.spec

        _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Searching(summary)
        activeAskJob = viewModelScope.launch {
            try {
                val response = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    aiClient.getRecommendations(workerRequest)
                }
                
                if (token != askSessionToken) return@launch

                val candidates = response.results.map(::mapAskResult)

                if (candidates.isEmpty()) {
                    _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Empty(summary, "No titles found.")
                } else {
                    _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Results(
                        requestSummary = summary,
                        spec = mapped.spec,
                        items = candidates,
                        totalAvailable = response.totalResults,
                        hasMore = response.hasMore,
                        nextCursor = response.nextCursor,
                        activeRequest = request,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (token != askSessionToken) return@launch
                _askUiState.value = if (
                    e is com.aliflix.app.recommendation.RecommendationAiClientException &&
                    e.code in setOf("TMDB_UNAVAILABLE", "TMDB_AUTH_FAILED")
                ) {
                    com.aliflix.app.ui.discover.AskAliflixUiState.SourceUnavailable(
                        summary,
                        askAliflixErrorMessage(e, "Movie and series details are temporarily unavailable. Please try again."),
                    )
                } else {
                    com.aliflix.app.ui.discover.AskAliflixUiState.Error(
                        summary,
                        askAliflixErrorMessage(e, "Ask Aliflix could not complete this search."),
                    )
                }
            }
        }
    }

    fun refineAskAliflix(refinement: String) {
        val normalized = refinement.trim()
        if (normalized.isBlank()) return
        val currentResults = _askUiState.value as? com.aliflix.app.ui.discover.AskAliflixUiState.Results ?: return
        if (currentResults.refining) return
        val currentRequest = currentResults.activeRequest ?: activeAskUiRequest ?: return
        val refinedRequest = when (currentRequest) {
            is com.aliflix.app.ui.discover.AskAliflixRequest.Describe -> {
                val previousText = currentRequest.text.trim()
                currentRequest.copy(
                    text = listOf(previousText, normalized).filter(String::isNotBlank).joinToString(", "),
                    previousText = previousText,
                    refinementText = normalized,
                )
            }
            is com.aliflix.app.ui.discover.AskAliflixRequest.Similar -> currentRequest.copy(
                refinementText = listOfNotNull(
                    currentRequest.refinementText?.trim()?.takeIf(String::isNotBlank),
                    normalized,
                ).joinToString(", "),
            )
            is com.aliflix.app.ui.discover.AskAliflixRequest.Filters -> return
        }
        val mapped = com.aliflix.app.ui.discover.AskAliflixRequestMapper.map(
            request = refinedRequest,
            aiModel = recommendationStore.aiModel.value,
        )
        activeAskJob?.cancel()
        val token = ++askSessionToken
        _askUiState.value = currentResults.copy(refining = true, refineError = null)
        activeAskJob = viewModelScope.launch {
            try {
                val response = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    aiClient.getRecommendations(mapped.workerRequest)
                }
                if (token != askSessionToken) return@launch
                val candidates = response.results.map(::mapAskResult)
                if (candidates.isEmpty()) {
                    _askUiState.value = currentResults.copy(
                        refining = false,
                        refineError = "No additional matches fit that refinement. Your current results are unchanged.",
                    )
                    return@launch
                }

                activeAskRequest = mapped.workerRequest
                activeAskUiRequest = refinedRequest
                activeAskSummary = mapped.summary
                activeAskSpec = mapped.spec
                if (refinedRequest is com.aliflix.app.ui.discover.AskAliflixRequest.Describe) {
                    _askEditorState.value = _askEditorState.value.copy(describeText = refinedRequest.text)
                }
                _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Results(
                    requestSummary = mapped.summary,
                    spec = mapped.spec,
                    items = candidates,
                    totalAvailable = response.totalResults,
                    hasMore = response.hasMore,
                    nextCursor = response.nextCursor,
                    appliedRefinements = currentResults.appliedRefinements + normalized,
                    activeRequest = refinedRequest,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token == askSessionToken) {
                    _askUiState.value = currentResults.copy(
                        refining = false,
                        refineError = askAliflixErrorMessage(error, "That refinement could not be applied. Your current results are unchanged."),
                    )
                }
            }
        }
    }

    fun toggleAskHideMySpaceTitles(hide: Boolean) {
        _askEditorState.value = _askEditorState.value.copy(hideMySpaceTitles = hide)
    }

    fun editAskAliflix() {
        activeAskJob?.cancel()
        activeAskJob = null
        askSessionToken++
        _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Editing
    }

    fun resetAskAliflix() {
        activeAskJob?.cancel()
        activeAskJob = null
        askSessionToken++
        activeAskRequest = null
        activeAskUiRequest = null
        activeAskSummary = null
        activeAskSpec = null
        val previous = _askEditorState.value
        _askEditorState.value = previous.copy(
            describeText = "",
            similarQuery = "",
            selectedAnchor = null,
            selectedAnchors = emptyList(),
            spec = com.aliflix.app.recommendation.CatalogDiscoverySpec(
                mediaKind = if (previous.mediaType == com.aliflix.app.model.MediaType.TV) {
                    com.aliflix.app.recommendation.RecommendationMediaKind.SERIES
                } else {
                    com.aliflix.app.recommendation.RecommendationMediaKind.MOVIE
                },
            ),
        )
        _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Editing
    }

    fun setAskEditorState(state: com.aliflix.app.ui.discover.AskAliflixEditorState) {
        _askEditorState.value = state
    }

    fun loadMoreAskAliflix() {
        val currentResults = _askUiState.value as? com.aliflix.app.ui.discover.AskAliflixUiState.Results ?: return
        val original = activeAskRequest ?: return
        if (currentResults.loadingMore || !currentResults.hasMore) return
        val cursor = currentResults.nextCursor ?: return
        val nextRequest = buildAskAliflixShowMoreRequest(original, cursor)

        val token = askSessionToken
        _askUiState.value = currentResults.copy(loadingMore = true, loadMoreError = null)

        activeAskJob = viewModelScope.launch {
            try {
                val response = aiClient.getRecommendations(nextRequest)
                if (token != askSessionToken) return@launch
                val additional = response.results.map(::mapAskResult)
                val appended = (currentResults.items + additional)
                    .distinctBy { it.media.key }
                _askUiState.value = currentResults.copy(
                    items = appended,
                    totalAvailable = maxOf(
                        currentResults.totalAvailable,
                        response.totalResults,
                        appended.size,
                    ),
                    loadingMore = false,
                    hasMore = response.hasMore,
                    nextCursor = response.nextCursor,
                    loadMoreError = null,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token != askSessionToken) return@launch
                _askUiState.value = currentResults.copy(
                    loadingMore = false,
                    loadMoreError = askAliflixErrorMessage(error, "Could not find another batch right now."),
                )
            }
        }
    }

    fun retryAskAliflix() {
        val original = activeAskRequest ?: return
        val uiRequest = activeAskUiRequest
        val summary = activeAskSummary ?: return
        val spec = activeAskSpec ?: return
        activeAskJob?.cancel()
        val token = ++askSessionToken
        _askUiState.value = com.aliflix.app.ui.discover.AskAliflixUiState.Searching(summary)
        activeAskJob = viewModelScope.launch {
            try {
                val response = aiClient.getRecommendations(
                    original.copy(
                        cursor = null,
                        aiModel = recommendationStore.aiModel.value.workerValue,
                    ),
                )
                if (token != askSessionToken) return@launch
                val candidates = response.results.map(::mapAskResult)
                _askUiState.value = if (candidates.isEmpty()) {
                    com.aliflix.app.ui.discover.AskAliflixUiState.Empty(summary, "No titles found.")
                } else {
                    com.aliflix.app.ui.discover.AskAliflixUiState.Results(
                        requestSummary = summary,
                        spec = spec,
                        items = candidates,
                        totalAvailable = response.totalResults,
                        hasMore = response.hasMore,
                        nextCursor = response.nextCursor,
                        activeRequest = uiRequest,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == askSessionToken) {
                    _askUiState.value = if (
                        error is com.aliflix.app.recommendation.RecommendationAiClientException &&
                        error.code in setOf("TMDB_UNAVAILABLE", "TMDB_AUTH_FAILED")
                    ) {
                        com.aliflix.app.ui.discover.AskAliflixUiState.SourceUnavailable(summary, askAliflixErrorMessage(error, "TMDB is temporarily unavailable."))
                    } else {
                        com.aliflix.app.ui.discover.AskAliflixUiState.Error(summary, askAliflixErrorMessage(error, "Ask Aliflix could not complete this search."))
                    }
                }
            }
        }
    }

    private fun mapAskResult(result: com.aliflix.app.recommendation.V3RecommendationResult) =
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
                status = result.status.orEmpty(),
                runtime = result.runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" }.orEmpty(),
            ),
            matchLevel = result.matchLevel,
            matchScore = result.finalScore,
            matchReasons = result.matchReasons,
        )

    private fun askAliflixErrorMessage(error: Exception, fallback: String): String {
        val clientError = error as? com.aliflix.app.recommendation.RecommendationAiClientException
        return when (clientError?.code) {
            "NETWORK_ERROR" -> "Check your connection and try again."
            "TMDB_UNAVAILABLE", "TMDB_AUTH_FAILED" -> "Movie and series details are temporarily unavailable. Please try again."
            "RATE_LIMITED", "RESOURCE_EXHAUSTED", "GROQ_RATE_LIMITED" -> "Ask Aliflix is busy right now. Please wait a moment and try again."
            "GROQ_UNAVAILABLE", "GEMINI_UNAVAILABLE", "AI_UNAVAILABLE" -> "The selected recommendation model is temporarily unavailable. Please try again."
            else -> fallback
        }
    }

    val playbackPreferences: StateFlow<PlaybackPreferences> =
        playbackProviderRepository.preferences
    val aiRecommendationsEnabled: StateFlow<Boolean> =
        recommendationStore.enabled
    val recommendationAiModel: StateFlow<RecommendationAiModel> =
        recommendationStore.aiModel

    fun selectGeneralPlaybackProvider(provider: PlaybackProviderId) =
        playbackProviderRepository.selectGeneralProvider(provider)

    fun updateRamoflixUrl(newUrl: String) =
        playbackProviderRepository.updateRamoflixUrl(newUrl)

    fun resetRamoflixUrl() = playbackProviderRepository.resetRamoflixUrl()

    fun updateMoviepireUrl(newUrl: String) =
        playbackProviderRepository.updateMoviepireUrl(newUrl)

    fun resetMoviepireUrl() = playbackProviderRepository.resetMoviepireUrl()

    fun selectPreferredSubtitleLanguage(language: SubtitleLanguage) =
        playbackProviderRepository.selectPreferredSubtitleLanguage(language)

    fun setAutoDisplaySubtitles(enabled: Boolean) =
        playbackProviderRepository.setAutoDisplaySubtitles(enabled)

    fun updateDorabyUrl(newUrl: String) =
        playbackProviderRepository.updateDorabyUrl(newUrl)

    fun resetDorabyUrl() = playbackProviderRepository.resetDorabyUrl()

    init {
        if (!BuildConfig.IS_TV) {
            viewModelScope.launch {
                val cached = homeSnapshotStore.loadSnapshot()
                if (cached != null && _home.value.content == null) {
                    _home.value = _home.value.copy(
                        content = cached.content,
                        editorialPicks = cached.editorialPicks,
                    )
                }
            }
        }
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

    fun loadTvNetworks(force: Boolean = false) {
        if (BuildConfig.IS_TV || tvNetworksJob?.isActive == true) return
        if (!force && _tvNetworks.value.rails.isNotEmpty()) return
        tvNetworksJob = viewModelScope.launch {
            val previous = _tvNetworks.value
            _tvNetworks.value = previous.copy(loading = true, error = null)
            _tvNetworks.value = runCatching {
                aiClient.getTvNetworkFeed(java.util.Locale.getDefault().country).let { feed ->
                    TvNetworksUiState(
                        rails = feed.rails.mapNotNull { rail ->
                            val category = when (rail.category) {
                                "network" -> "Network"
                                "streaming_provider" -> "Streaming"
                                "production_company" -> "Production company"
                                else -> return@mapNotNull null
                            }
                            ContentRail(
                                title = "${rail.title} · $category",
                                items = rail.items.map { it.toMedia() }.distinctBy(Media::key),
                            ).takeIf { it.items.isNotEmpty() }
                        },
                        attribution = feed.attribution,
                    )
                }
            }.getOrElse { error ->
                previous.copy(
                    loading = false,
                    error = if (previous.rails.isEmpty()) {
                        error.message ?: "Unable to load TV networks."
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun refreshHomeInternal(force: Boolean, showLoading: Boolean) {
        if (!force && System.currentTimeMillis() - lastHomeRefreshAt < HOME_STALE_AFTER_MS) return
        if (homeRefreshJob?.isActive == true) return
        homeRefreshJob = viewModelScope.launch {
            val previous = _home.value
            _home.value = previous.copy(
                loading = if (showLoading) true else previous.loading,
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
                        viewModelScope.launch {
                            homeSnapshotStore.saveSnapshot(
                                PersistedHomeSnapshot(
                                    content = snapshot.content,
                                    editorialPicks = snapshot.editorialPicks,
                                )
                            )
                        }
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
                val results = runCatching { searchTitles(query) }
                    .getOrElse { client.search(query) }
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
                val results = runCatching { searchTitles(trimmed) }
                    .getOrElse { client.search(trimmed) }
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

    suspend fun searchTitles(query: String): List<Media> = aiClient.searchTitles(query.trim())
        .map { it.toMedia() }
        .distinctBy(Media::key)

    suspend fun searchCompanies(query: String): List<com.aliflix.app.recommendation.ProductionCompanyFilter> =
        aiClient.searchCompanies(query.trim())

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

    private val mobileEpisodes by lazy { com.aliflix.app.data.MobileEpisodeRepository(getApplication(), aiClient) }
    private val mobileDetailCache = object : LinkedHashMap<String, Media>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Media>?) = size > 60
    }

    private var episodePrefetchJob: Job? = null
    fun preloadRecentlyWatchedEpisodes() {
        if (BuildConfig.IS_TV) return
        episodePrefetchJob?.cancel()
        episodePrefetchJob = viewModelScope.launch {
            recent.value.filter { it.type == MediaType.TV }.take(3).forEach { show ->
                val season = playbackProgressStore.entries.value.values.filter { it.media.key == show.key }
                    .maxByOrNull { it.updatedAtMillis }?.seasonNumber ?: 1
                try { mobileEpisodes.episodes(show.id, season); mobileEpisodes.seasons(show.id) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
        }
    }

    private fun openMobileDetails(item: Media) {
        detailJob?.cancel(); episodeJob?.cancel()
        val initial = mobileDetailCache[item.key] ?: item
        val season = playbackProgressStore.entries.value.values.filter { it.media.key == item.key }
            .maxByOrNull { it.updatedAtMillis }?.seasonNumber ?: 1
        _detail.value = DetailUiState(item = initial, selectedSeason = season, episodesLoading = item.type == MediaType.TV)
        if (item.type == MediaType.TV) loadMobileSeason(initial, season)
        detailJob = viewModelScope.launch(com.aliflix.app.data.ForegroundRequestPriorityElement) {
            if (item.type == MediaType.TV) launch {
                try {
                    val seasons = mobileEpisodes.seasons(item.id)
                    if (_detail.value.item?.key == item.key) _detail.value = _detail.value.copy(seasons = seasons)
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
            launch {
                try {
                    val details = aiClient.getTitleDetails(item.type.routeName, item.id)
                    if (_detail.value.item?.key == item.key) {
                        val stable = details.toStableMobileMedia(_detail.value.item ?: initial)
                        _detail.value = _detail.value.copy(item = stable, recommendations = details.recommendations.map { it.toMedia() })
                        mobileDetailCache[item.key] = stable
                    }
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
            launch {
                try {
                    client.details(initial, nativeMetadata = true) { update, _ ->
                        val current = _detail.value
                        if (current.item?.key == item.key) {
                            val merged = current.item.mergeStableMobileDetailUpdate(update)
                            _detail.value = current.copy(item = merged)
                            mobileDetailCache[item.key] = merged; library.refreshMetadata(merged)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
        }
    }

    private fun loadMobileSeason(item: Media, season: Int) {
        episodeJob?.cancel()
        _detail.value = _detail.value.copy(selectedSeason = season, episodes = emptyList(), episodesLoading = true, error = null)
        episodeJob = viewModelScope.launch(com.aliflix.app.data.ForegroundRequestPriorityElement) {
            fun publish(episodes: List<Episode>) {
                val latest = _detail.value
                if (latest.item?.key == item.key && latest.selectedSeason == season) _detail.value = latest.copy(episodes = episodes, episodesLoading = false)
            }
            try {
                val episodes = mobileEpisodes.episodes(item.id, season) { publish(it) }
                publish(episodes)
                // Ratings enrich visible rows; they never gate the list or the Play action.
                val enriched = client.mobileEpisodeRatings(_detail.value.item ?: item, season, episodes)
                publish(enriched)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                val latest = _detail.value
                if (latest.item?.key == item.key && latest.selectedSeason == season) _detail.value = latest.copy(episodesLoading = false,
                    error = if (latest.episodes.isEmpty()) "Episodes couldn't load. Select the season to retry." else null)
            }
        }
    }

    fun openDetails(item: Media) {
        if (!BuildConfig.IS_TV) { openMobileDetails(item); return }
        detailJob?.cancel()
        episodeJob?.cancel()
        _detail.value = DetailUiState(loading = true, item = item)
        detailJob = viewModelScope.launch(com.aliflix.app.data.ForegroundRequestPriorityElement) {
            try {
                val tmdbDetails = runCatching {
                    aiClient.getTitleDetails(item.type.routeName, item.id)
                }.getOrNull()
                val tmdbRecommendations = tmdbDetails?.recommendations?.map { it.toMedia() }.orEmpty()
                val authoritativeItem = if (!BuildConfig.IS_TV) {
                    tmdbDetails?.toStableMobileMedia(item) ?: item
                } else {
                    tmdbDetails?.toMedia(item) ?: item
                }
                _detail.value = _detail.value.copy(
                    item = authoritativeItem,
                    recommendations = tmdbRecommendations.ifEmpty { _detail.value.recommendations },
                )

                val seasonsRequest = async {
                    if (authoritativeItem.type == MediaType.TV) {
                        client.seasons(authoritativeItem)
                    } else {
                        emptyList()
                    }
                }

                episodeJob = launch {
                    val seasons = seasonsRequest.await()
                    val selectedSeason = seasons.firstOrNull()?.number ?: 1
                    _detail.value = _detail.value.copy(
                        seasons = seasons,
                        selectedSeason = selectedSeason,
                        episodesLoading = authoritativeItem.type == MediaType.TV,
                    )
                    
                    if (authoritativeItem.type == MediaType.TV) {
                        val currentItem = _detail.value.item ?: authoritativeItem
                        val episodes = client.episodes(currentItem, selectedSeason) { progress ->
                            val current = _detail.value
                            if (
                                current.item?.key == currentItem.key &&
                                current.selectedSeason == selectedSeason
                            ) {
                                _detail.value = current.copy(
                                    episodes = progress,
                                    episodesLoading = false,
                                )
                            }
                        }
                        val current = _detail.value
                        if (
                            current.item?.key == currentItem.key &&
                            current.selectedSeason == selectedSeason
                        ) {
                            _detail.value = current.copy(
                                episodes = episodes,
                                episodesLoading = false,
                            )
                        }
                    }
                }

                client.details(authoritativeItem) { details, recommendations ->
                    val displayDetails = if (!BuildConfig.IS_TV) {
                        authoritativeItem.mergeStableMobileDetailUpdate(details)
                    } else {
                        details
                    }
                    library.refreshMetadata(displayDetails)
                    val resolvedRecs = if (tmdbRecommendations.isNotEmpty()) {
                        tmdbRecommendations
                    } else if (!recommendations.isNullOrEmpty()) {
                        recommendations
                    } else {
                        _detail.value.recommendations
                    }
                    _detail.value = _detail.value.copy(
                        loading = false,
                        item = displayDetails,
                        recommendations = resolvedRecs,
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
        if (!BuildConfig.IS_TV) {
            val item = _detail.value.item ?: return
            if (item.type == MediaType.TV && (number != _detail.value.selectedSeason || _detail.value.episodes.isEmpty())) loadMobileSeason(item, number)
            return
        }
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
            _detail.value = runCatching {
                client.episodes(item, number) { progress ->
                    val latest = _detail.value
                    if (latest.item?.key == item.key && latest.selectedSeason == number) {
                        _detail.value = latest.copy(
                            episodes = progress,
                            episodesLoading = false,
                        )
                    }
                }
            }
                .fold(
                    onSuccess = { episodes ->
                        val latest = _detail.value
                        if (latest.item?.key == item.key && latest.selectedSeason == number) {
                            latest.copy(
                                episodes = episodes,
                                episodesLoading = false,
                            )
                        } else {
                            latest
                        }
                    },
                    onFailure = {
                        val latest = _detail.value
                        if (latest.item?.key == item.key && latest.selectedSeason == number) {
                            latest.copy(
                                episodesLoading = false,
                                error = it.message ?: "Episodes could not be loaded.",
                            )
                        } else {
                            latest
                        }
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

    fun setAiRecommendationsEnabled(enabled: Boolean) {
        recommendationStore.setEnabled(enabled)
        if (!enabled) {
            if (_search.value.mode == SearchMode.AI) {
                selectSearchMode(SearchMode.TITLE)
            }
        }
    }

    fun setRecommendationAiModel(model: RecommendationAiModel) {
        recommendationStore.setAiModel(model)
    }

    suspend fun signInWithGoogle(activity: Activity): AccountActionResult =
        accountServices.accountRepository.signInWithGoogle(activity)

    suspend fun createEmailAccount(
        email: String,
        password: String,
        displayName: String? = null,
    ): AccountActionResult = accountServices.accountRepository.createEmailAccount(
        email = email,
        password = password,
        displayName = displayName,
    )

    suspend fun signInWithEmail(email: String, password: String): AccountActionResult =
        accountServices.accountRepository.signInWithEmail(email, password)

    suspend fun sendPasswordResetEmail(email: String): AccountActionResult =
        accountServices.accountRepository.sendPasswordResetEmail(email)

    suspend fun signOutAccount(): AccountActionResult =
        accountServices.accountRepository.signOut()

    suspend fun reauthenticateAccountWithPassword(password: String): AccountActionResult =
        accountServices.accountRepository.reauthenticateWithPassword(password)

    suspend fun reauthenticateAccountWithGoogle(activity: Activity): AccountActionResult =
        accountServices.accountRepository.reauthenticateWithGoogle(activity)

    suspend fun deleteCurrentAccount(): AccountActionResult =
        accountServices.deleteCurrentAccount()

    fun clearAccountMessage() = accountServices.accountRepository.clearMessage()

    fun retryAccountSync() = accountServices.syncRepository.retry()

    private fun pauseBackgroundHomeRefresh() {
        homeRefreshJob?.cancel()
        homeRefreshJob = null
    }

    override fun onCleared() {
        accountServices.close()
        super.onCleared()
    }

    private companion object {
        const val MIN_GENRE_RESULTS = 20
        const val HOME_STALE_AFTER_MS = 5 * 60 * 1_000L
        const val HOME_REFRESH_INTERVAL_MS = 30 * 60 * 1_000L
    }
}
