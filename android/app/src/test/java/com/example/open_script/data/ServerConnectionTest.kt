package com.example.open_script.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConnectionTest {
    @Test fun savedOriginWins() {
        listOf(true, false).forEach { hasSession ->
            listOf(true, false).forEach { debug ->
                assertEquals("  https://saved.example/  ", ServerConnection.resolveOrigin(
                    "  https://saved.example/  ", "https://new.example", hasSession, debug,
                ))
            }
        }
    }

    @Test fun savedEmptyStaysEmpty() {
        listOf(true, false).forEach { hasSession ->
            listOf(true, false).forEach { debug ->
                assertEquals("", ServerConnection.resolveOrigin("", "https://new.example", hasSession, debug))
            }
        }
    }

    @Test fun legacyDebugKeepsLocalServer() {
        assertEquals("http://10.0.2.2:8000", ServerConnection.resolveOrigin(null, "https://new.example", true, true))
    }

    @Test fun legacyReleaseBlocksNewServer() {
        assertEquals("", ServerConnection.resolveOrigin(null, "https://new.example", true, false))
    }

    @Test fun signedOutUsesBuildOrigin() {
        listOf(true, false).forEach { debug ->
            listOf("", "https://new.example").forEach { configured ->
                assertEquals(configured, ServerConnection.resolveOrigin(null, configured, false, debug))
            }
        }
    }
}
