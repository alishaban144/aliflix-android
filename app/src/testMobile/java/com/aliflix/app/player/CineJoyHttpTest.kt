package com.aliflix.app.player

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CineJoyHttpTest {
    @Test fun winnerDoesNotWaitForStalledCatalogueServerAndKeepsSiteHeaders() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/slow") { exchange ->
            started.complete(Unit)
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        server.createContext("/fast") { exchange ->
            // Desktop JDK strips Origin as a restricted header; Android's implementation does not.
            val body = "${exchange.requestHeaders.getFirst("User-Agent")}|${exchange.requestHeaders.getFirst("Referer")}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val result = withTimeout(2_000) {
                firstSuccessful(listOf(
                    suspend { CineJoyHttp.request("$base/slow", "test").decodeToString() },
                    suspend { started.await(); CineJoyHttp.request("$base/fast", "test").decodeToString() },
                ))
            }
            assertEquals("test|https://cinejoy.pk/", result)
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
