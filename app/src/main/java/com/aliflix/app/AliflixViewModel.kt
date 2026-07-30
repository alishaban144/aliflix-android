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
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackPreferences
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.Season
import com.aliflix.app.recommendation.CatalogRecommendationCandidateRepository
import com.aliflix.app.recommendation.RecommendationOrchestrator
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationQuestion
import com.aliflix.app.recommendation.RecommendationStore
import com.aliflix.app.recommendation.RecommendationUiState
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val content: HomeContent? = null,
    val error: String? = null,
)

enum class SearchMode {
    TITLE,
    PLOT,
    AI,
}

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.TITLE,
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

class AliflixViewModel(application: Application) : AndroidViewModel(application) {
    private val client = CatalogClient(
        cacheStore = AndroidCatalogCacheStore(application),
    )
    private val library = LibraryStore(application)
    private val playbackProviderRepository = PlaybackProviderRepository(application)
    private val recommendationStore = RecommendationStore(application)
    private val recommendationOrchestrator = RecommendationOrchestrator(
        scope = viewModelScope,
        repository = CatalogRecommendationCandidateRepository(client),
        store = recommendationStore,
        likesProvider = { library.likes.value },
        recentlyPlayedProvider = { library.recent.value },
    )
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var episodeJob: Job? = null
    private var genreJob: Job? = null
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

    val myList = library.myList
    val recent = library.recent
    val likes = library.likes

    val playbackPreferences: StateFlow<PlaybackPreferences> =
        playbackProviderRepository.preferences
    val recommendation: StateFlow<RecommendationUiState> =
        recommendationOrchestrator.state
    val aiRecommendationsEnabled: StateFlow<Boolean> =
        recommendationStore.enabled

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
            _home.value = runCatching {
                client.home { partial ->
                    _home.value = HomeUiState(
                        loading = false,
                        content = partial,
                    )
                }
            }
                .fold(
                    onSuccess = {
                        lastHomeRefreshAt = System.currentTimeMillis()
                        HomeUiState(loading = false, content = it)
                    },
                    onFailure = {
                        HomeUiState(
                            loading = false,
                            content = previous.content,
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
        val mode = _search.value.mode
        val pending = _search.value.copy(
            query = query,
            loading = query.isNotBlank(),
            results = emptyList(),
            error = null,
        )
        _search.value = pending
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = SearchUiState(mode = mode)
            return
        }
        searchJob = viewModelScope.launch {
            try {
                delay(if (mode == SearchMode.PLOT) 650 else 220)
                if (_search.value.query != query || _search.value.mode != mode) {
                    return@launch
                }
                val loading = _search.value.copy(loading = true)
                _search.value = loading
                val results = if (mode == SearchMode.PLOT) {
                    client.searchByPlot(query)
                } else {
                    client.search(query)
                }
                if (_search.value.query == query && _search.value.mode == mode) {
                    val complete = SearchUiState(
                        query = query,
                        mode = mode,
                        loading = false,
                        results = results,
                    )
                    _search.value = complete
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_search.value.query == query && _search.value.mode == mode) {
                    val failed = SearchUiState(
                        query = query,
                        mode = mode,
                        loading = false,
                        error = error.message ?: "Search failed.",
                    )
                    _search.value = failed
                }
            }
        }
    }

    fun selectSearchMode(mode: SearchMode) {
        if (mode == SearchMode.AI && !recommendationStore.enabled.value) return
        if (_search.value.mode == mode) return
        searchJob?.cancel()
        _search.value = SearchUiState(mode = mode)
    }

    fun openGenre(genre: String, type: MediaType) {
        genreJob?.cancel()
        _genre.value = GenreUiState(
            genre = genre,
            type = type,
            loading = true,
        )
        genreJob = viewModelScope.launch {
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
        detailJob = viewModelScope.launch {
            _detail.value = runCatching {
                val detailsRequest = async { client.details(item) }
                val seasonsRequest = async {
                    if (item.type == MediaType.TV) client.seasons(item) else emptyList()
                }
                val (details, recommendations) = detailsRequest.await()
                library.refreshMetadata(details)
                val seasons = seasonsRequest.await()
                val selectedSeason = seasons.firstOrNull()?.number ?: 1
                val episodes = if (item.type == MediaType.TV) {
                    client.episodes(details, selectedSeason)
                } else {
                    emptyList()
                }
                DetailUiState(
                    loading = false,
                    item = details,
                    recommendations = recommendations,
                    seasons = seasons,
                    selectedSeason = selectedSeason,
                    episodes = episodes,
                )
            }
                .fold(
                    onSuccess = { it },
                    onFailure = {
                        DetailUiState(
                            loading = false,
                            item = item,
                            error = it.message,
                        )
                    },
                )
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
        episodeJob = viewModelScope.launch {
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

    fun toggleMyList(item: Media) = library.toggleMyList(item)

    fun isInMyList(item: Media): Boolean = library.isInMyList(item)

    fun toggleLike(item: Media) = library.toggleLike(item)

    fun isLiked(item: Media): Boolean = library.isLiked(item)

    fun markPlayed(item: Media) = library.markPlayed(item)

    fun removeRecent(item: Media) = library.removeRecent(item)

    fun clearRecent() = library.clearRecent()

    fun submitRecommendationText(text: String) {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.submitText(text)
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

    fun relaxRecommendationConstraint(id: String) {
        pauseBackgroundHomeRefresh()
        recommendationOrchestrator.applyRelaxation(id)
    }

    fun setAiRecommendationsEnabled(enabled: Boolean) {
        recommendationStore.setEnabled(enabled)
        if (!enabled) {
            recommendationOrchestrator.restart()
            if (_search.value.mode == SearchMode.AI) {
                _search.value = SearchUiState(mode = SearchMode.TITLE)
            }
        }
    }

    fun resetRecommendationTaste() = recommendationOrchestrator.resetTaste()

    private fun pauseBackgroundHomeRefresh() {
        homeRefreshJob?.cancel()
        homeRefreshJob = null
    }

    private companion object {
        const val MIN_GENRE_RESULTS = 20
        const val HOME_STALE_AFTER_MS = 5 * 60 * 1_000L
        const val HOME_REFRESH_INTERVAL_MS = 30 * 60 * 1_000L
    }
}
