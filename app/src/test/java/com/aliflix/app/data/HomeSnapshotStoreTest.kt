package com.aliflix.app.data

import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HomeSnapshotStoreTest {
    private lateinit var tempDir: File
    private lateinit var store: AndroidHomeSnapshotStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("home-snapshot-test").toFile()
        store = AndroidHomeSnapshotStore(cacheDir = tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun returnsNullWhenNoSnapshotExists() = runTest {
        val snapshot = store.loadSnapshot()
        assertNull(snapshot)
    }

    @Test
    fun savesAndRestoresCompleteHomeSnapshot() = runTest {
        val hero = Media(
            id = 101,
            type = MediaType.MOVIE,
            title = "Midnight Echo",
            overview = "A compelling drama.",
            year = "2026",
            rating = 8.5,
        )
        val rail1 = ContentRail(
            title = "Trending Now",
            items = listOf(
                Media(id = 201, type = MediaType.MOVIE, title = "Shadow Realm"),
                Media(id = 202, type = MediaType.TV, title = "Cyber Pulse"),
            ),
        )
        val rail2 = ContentRail(
            title = "Top Series",
            items = listOf(
                Media(id = 301, type = MediaType.TV, title = "Neon Horizon"),
            ),
        )
        val editorialPicks = listOf(
            Media(id = 401, type = MediaType.MOVIE, title = "Aurora"),
        )
        val content = HomeContent(hero = hero, rails = listOf(rail1, rail2))
        val originalSnapshot = PersistedHomeSnapshot(
            content = content,
            editorialPicks = editorialPicks,
            savedAt = 1234567890L,
        )

        store.saveSnapshot(originalSnapshot)

        val restored = store.loadSnapshot()
        assertNotNull(restored)
        assertEquals("Midnight Echo", restored!!.content.hero.title)
        assertEquals(101, restored.content.hero.id)
        assertEquals(2, restored.content.rails.size)
        assertEquals("Trending Now", restored.content.rails[0].title)
        assertEquals(2, restored.content.rails[0].items.size)
        assertEquals("Shadow Realm", restored.content.rails[0].items[0].title)
        assertEquals("Cyber Pulse", restored.content.rails[0].items[1].title)
        assertEquals("Top Series", restored.content.rails[1].title)
        assertEquals(1, restored.content.rails[1].items.size)
        assertEquals("Neon Horizon", restored.content.rails[1].items[0].title)
        assertEquals(1, restored.editorialPicks.size)
        assertEquals("Aurora", restored.editorialPicks[0].title)
        assertEquals(1234567890L, restored.savedAt)
    }

    @Test
    fun clearsSnapshot() = runTest {
        val hero = Media(id = 1, type = MediaType.MOVIE, title = "Test Hero")
        val snapshot = PersistedHomeSnapshot(
            content = HomeContent(hero = hero, rails = emptyList()),
        )
        store.saveSnapshot(snapshot)
        assertNotNull(store.loadSnapshot())

        store.clearSnapshot()
        assertNull(store.loadSnapshot())
    }
}
