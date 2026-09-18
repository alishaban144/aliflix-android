package com.aliflix.app.player

import org.junit.Assert.*
import org.junit.Test

class NativeServerPinTest {
    @Test fun strictPinSelectsOnlyExactLabelIncludingDecorations() {
        val servers = listOf("Vid", "Vidmux", "Vid - HD", "vid - hd", "Mist")
        assertEquals("Vid - HD", selectNativeServer(servers, "Vid - HD", emptySet(), true) { it })
    }

    @Test fun strictPinRejectsMissingServerRatherThanChoosingFirstOrSimilarName() {
        for (servers in listOf(emptyList(), listOf("Mist", "Vidmux"), listOf("vid"), listOf("Vid - HD"))) {
            val error = assertThrows(IllegalStateException::class.java) {
                selectNativeServer(servers, "Vid", emptySet(), true) { it }
            }
            assertTrue(error.message.orEmpty().contains("Vid"))
            assertTrue(error.message.orEmpty().contains("Retry"))
        }
    }

    @Test fun strictPinRejectsExcludedServer() {
        assertThrows(IllegalStateException::class.java) {
            selectNativeServer(listOf("Vid", "Mist"), "Vid", setOf("Vid"), true) { it }
        }
    }

    @Test fun strictPinRequiresNonblankPreferredServer() {
        for (server in listOf(null, "", " ")) {
            assertThrows(IllegalArgumentException::class.java) {
                selectNativeServer(listOf("Vid"), server, emptySet(), true) { it }
            }
        }
    }

    @Test fun streamingDefaultRetainsCaseInsensitivePreferenceAndFallback() {
        val servers = listOf("Vid", "Mist")
        assertEquals("Mist", selectNativeServer(servers, "mist", emptySet()) { it })
        assertEquals("Vid", selectNativeServer(servers, "Missing", emptySet()) { it })
        assertEquals("Mist", selectNativeServer(servers, null, setOf("Vid")) { it })
        assertNull(selectNativeServer(servers, null, servers.toSet()) { it })
    }
}
