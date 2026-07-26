package com.aliflix.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aliflix.app.data.CatalogClient
import com.aliflix.app.data.LibraryStore
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackPreferences
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.Season
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

data class SearchUiState(
    val query: String = "",
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

class AliflixViewModel(application: Application) : AndroidViewModel(application) {
    private val client = CatalogClient()
    private val library = LibraryStore(application)
    private val playbackProviderRepository = PlaybackProviderRepository(application)
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var episodeJob: Job? = null
    private var homeRefreshJob: Job? = null
    private var lastHomeRefreshAt = 0L

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _detail = MutableStateFlow(DetailUiState())
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    val myList = library.myList
    val recent = library.recent
    val likes = library.likes

    val playbackPreferences: StateFlow<PlaybackPreferences> =
        playbackProviderRepository.preferences

    fun selectGeneralPlaybackProvider(provider: PlaybackProviderId) =
        playbackProviderRepository.selectGeneralProvider(provider)

    fun updateRamoflixUrl(newUrl: String) =
        playbackProviderRepository.updateRamoflixUrl(newUrl)

    fun resetRamoflixUrl() = playbackProviderRepository.resetRamoflixUrl()

    fun updateBcineUrl(newUrl: String) =
        playbackProviderRepository.updateBcineUrl(newUrl)

    fun resetBcineUrl() = playbackProviderRepository.resetBcineUrl()

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
            _home.value = runCatching { client.home() }
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
        val pending = _search.value.copy(
            query = query,
            loading = query.isNotBlank(),
            results = emptyList(),
            error = null,
        )
        _search.value = pending
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = SearchUiState()
            return
        }
        searchJob = viewModelScope.launch {
            try {
                delay(220)
                if (_search.value.query != query) {
                    return@launch
                }
                val loading = _search.value.copy(loading = true)
                _search.value = loading
                val results = client.search(query)
                if (_search.value.query == query) {
                    val complete = SearchUiState(
                        query = query,
                        loading = false,
                        results = results,
                    )
                    _search.value = complete
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_search.value.query == query) {
                    val failed = SearchUiState(
                        query = query,
                        loading = false,
                        error = error.message ?: "Search failed.",
                    )
                    _search.value = failed
                }
            }
        }
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

    private companion object {
        const val HOME_STALE_AFTER_MS = 5 * 60 * 1_000L
        const val HOME_REFRESH_INTERVAL_MS = 30 * 60 * 1_000L
    }
}
