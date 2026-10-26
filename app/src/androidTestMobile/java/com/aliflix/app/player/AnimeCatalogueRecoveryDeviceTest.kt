package com.aliflix.app.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AnimeCatalogueRecoveryDeviceTest {
    @Test fun transientCatalogueFailureRecoversWithoutAnotherViewerAction() = runBlocking {
        grantNativeFixtureNetworkPermission()
        val count = AtomicInteger()
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val worker = thread {
                repeat(2) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        val attempt = count.incrementAndGet()
                        val payload = if (attempt == 1) "unavailable" else "{\"ok\":true}"
                        val status = if (attempt == 1) "503 Service Unavailable" else "200 OK"
                        socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload").toByteArray())
                    }
                }
            }
            val response = AnimeCatalogueHttp.get("http://127.0.0.1:${server.localPort}/catalogue", "Aliflix physical test", "http://127.0.0.1/")
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertEquals(2, count.get())
            assertEquals("{\"ok\":true}", response.bytes.toString(Charsets.UTF_8))
        }
    }
}
