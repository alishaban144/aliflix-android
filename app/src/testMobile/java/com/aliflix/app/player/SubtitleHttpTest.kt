package com.aliflix.app.player

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubtitleHttpTest {
    @Test fun cancellationReturnsPromptlyAndDisconnectsBlockedDownload() = runBlocking {
        val started = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://example.com")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun disconnect() { disconnected.countDown() }
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    started.countDown()
                    check(disconnected.await(2, TimeUnit.SECONDS))
                    throw java.io.IOException("Cancelled")
                }
            }
        }
        val request = async(Dispatchers.Default) { readMobileSubtitleBytes("https://example.com") { connection } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        withTimeout(500) { request.cancelAndJoin() }
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertTrue(request.isCancelled)
    }
    @Test fun failedResponseIsNeverParsedAsSubtitle() = runBlocking {
        val connection = object : HttpURLConnection(URL("https://example.com")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 503
            override fun disconnect() = Unit
        }
        val failure = runCatching { readMobileSubtitleBytes("https://example.com") { connection } }.exceptionOrNull()
        assertTrue(failure is SubtitleException)
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
        assertTrue(connection.getRequestProperty("User-Agent").contains("Chrome/120.0.0.0"))
    }
}
