package com.aliflix.app.player

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AnimeCatalogueRecoveryTest {
    @Test fun onlyTransientCatalogueFailuresCanRetry() {
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue(animeCatalogueShouldRetry(AnimeCatalogueHttpException(it))) }
        listOf(400, 401, 403, 404, 410).forEach { assertFalse(animeCatalogueShouldRetry(AnimeCatalogueHttpException(it))) }
        assertTrue(animeCatalogueShouldRetry(IOException("connection reset")))
        assertFalse(animeCatalogueShouldRetry(CancellationException()))
        assertFalse(animeCatalogueShouldRetry(IllegalArgumentException("invalid payload")))
    }
}
