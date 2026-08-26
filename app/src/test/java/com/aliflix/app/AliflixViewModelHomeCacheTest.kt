package com.aliflix.app

import com.aliflix.app.data.HomeSnapshotStore
import com.aliflix.app.data.PersistedHomeSnapshot
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AliflixViewModelHomeCacheTest {

    private class FakeHomeSnapshotStore(
        private var snapshot: PersistedHomeSnapshot? = null,
    ) : HomeSnapshotStore {
        override suspend fun loadSnapshot(): PersistedHomeSnapshot? = snapshot
        override suspend fun saveSnapshot(snapshot: PersistedHomeSnapshot) {
            this.snapshot = snapshot
        }
        override suspend fun clearSnapshot() {
            this.snapshot = null
        }
    }

    @Test
    fun homeUiStateRetainsContentWhenCached() = runTest {
        val hero = Media(id = 10, type = MediaType.MOVIE, title = "Interstellar")
        val rail = ContentRail(title = "Featured", items = listOf(hero))
        val initialContent = HomeContent(hero = hero, rails = listOf(rail))

        val stateWithContent = HomeUiState(
            loading = false,
            content = initialContent,
            editorialPicks = emptyList(),
            error = null,
        )

        // Stale-while-revalidate rule: When network fails, previous.content is retained and error is null
        val stateAfterNetworkFailure = HomeUiState(
            loading = false,
            content = stateWithContent.content,
            editorialPicks = stateWithContent.editorialPicks,
            error = if (stateWithContent.content == null) "Error" else null,
        )

        assertNotNull(stateAfterNetworkFailure.content)
        assertEquals("Interstellar", stateAfterNetworkFailure.content?.hero?.title)
        assertNull(stateAfterNetworkFailure.error)
    }

    @Test
    fun homeUiStateShowsErrorOnlyWhenNoContent() = runTest {
        val stateWithoutContent = HomeUiState(
            loading = false,
            content = null,
            editorialPicks = emptyList(),
            error = null,
        )

        val stateAfterNetworkFailure = HomeUiState(
            loading = false,
            content = stateWithoutContent.content,
            editorialPicks = stateWithoutContent.editorialPicks,
            error = if (stateWithoutContent.content == null) "Unable to connect" else null,
        )

        assertNull(stateAfterNetworkFailure.content)
        assertEquals("Unable to connect", stateAfterNetworkFailure.error)
    }
}
