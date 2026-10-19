package com.example.open_script.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiContractTest {
    @Test fun productionOriginRequiresHttpsAndContainsNoPathOrCredentials() {
        listOf(
            "http://example.com", "https://example.com/api/v1", "https://user:password@example.com",
            "https://example.com?target=other", "https://example.com#section", "file:///tmp/site",
            "https://example.com:0", "https://example.com:65536", "//example.com", "https://",
        ).forEach { origin ->
            assertThrows(origin, IllegalArgumentException::class.java) { OriginValidation.normalize(origin) }
        }
        assertEquals("https://example.com", OriginValidation.normalize("  HTTPS://EXAMPLE.COM/  "))
        assertEquals("https://example.com:8443", OriginValidation.normalize("https://example.com:8443"))
    }

    @Test fun debugHttpIsLimitedToNetworkPolicyHostsAndCannotUseLookalikeDomains() {
        listOf("localhost", "127.0.0.1", "10.0.2.2").forEach { host ->
            assertEquals("http://$host:8000", OriginValidation.normalize("http://$host:8000", allowLocalHttp = true))
            assertThrows(IllegalArgumentException::class.java) { OriginValidation.normalize("http://$host:8000") }
        }
        listOf("192.168.0.1", "10.0.0.1", "localhost.evil.test", "127.0.0.1.evil.test", "2130706433").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { OriginValidation.normalize("http://$host", allowLocalHttp = true) }
        }
    }

    @Test fun titleLimitCountsEmojiAsUnicodeCharacters() {
        ApiContract.validateScript(ScriptDocument(title = "🎬".repeat(100)))
        val error = assertThrows(ApiException::class.java) {
            ApiContract.validateScript(ScriptDocument(title = "🎬".repeat(101)))
        }
        assertEquals("validation_error", error.code)
        assertTrue(error.fieldErrors.containsKey("title"))
    }

    @Test fun contentLimitUsesUtf8BytesInsteadOfCharacterCount() {
        ApiContract.validateScript(ScriptDocument(content = "a".repeat(102400)))
        ApiContract.validateScript(ScriptDocument(content = "é".repeat(51200)))
        ApiContract.validateScript(ScriptDocument(content = "🎬".repeat(25600)))
        listOf("a".repeat(102401), "é".repeat(51201), "🎬".repeat(25601)).forEach { content ->
            val error = assertThrows(ApiException::class.java) { ApiContract.validateScript(ScriptDocument(content = content)) }
            assertTrue(error.fieldErrors.containsKey("content"))
        }
    }

    @Test fun settingsRejectNonFiniteNumbersAndValuesOutsideTheServerContract() {
        ApiContract.validateSettings(PromptSettings())
        ApiContract.validateSettings(PromptSettings(speed = 100f, size = 120f, opacity = 70f, color = "#ABC123"))
        listOf(
            PromptSettings(speed = Float.NaN), PromptSettings(speed = Float.POSITIVE_INFINITY),
            PromptSettings(speed = 9f), PromptSettings(size = 121f), PromptSettings(opacity = -1f),
            PromptSettings(color = "red"), PromptSettings(language = "unknown"), PromptSettings(font = "unknown"),
        ).forEach { settings ->
            assertThrows(ApiException::class.java) { ApiContract.validateSettings(settings) }
        }
    }

    @Test fun unpairedUnicodeSurrogatesAreRejectedInsteadOfSilentlyReplacingText() {
        listOf("\uD83C", "\uDFAC", "text\uD83Ctail").forEach { invalid ->
            assertThrows(ApiException::class.java) { ApiContract.validateScript(ScriptDocument(content = invalid)) }
            assertThrows(ApiException::class.java) { ApiContract.validateScript(ScriptDocument(title = invalid)) }
        }
        ApiContract.validateScript(ScriptDocument(title = "", content = "🎬 Olá\n"))
    }

    @Test fun revisionWritesRequireCanonicalRemoteUuidAndPositiveRevision() {
        val id = "335c5728-2b8c-48e7-bfe3-ed7edc43fed1"
        val local = ScriptDocument(content = "Minha edição local", remoteId = id, revision = 3)
        assertEquals(id to 3, ApiContract.remoteIdentity(local))
        listOf(
            local.copy(remoteId = null), local.copy(remoteId = "../another/script"),
            local.copy(remoteId = "1-1-1-1-1"), local.copy(revision = null), local.copy(revision = 0),
        ).forEach { script ->
            assertThrows(ApiException::class.java) { ApiContract.remoteIdentity(script) }
            assertEquals("Minha edição local", script.content)
        }
    }

    @Test fun timestampPreservesOffsetsAndRejectsMalformedDates() {
        assertEquals(ApiContract.timestamp("2026-10-19T12:00:00+00:00"), ApiContract.timestamp("2026-10-19T09:00:00-03:00"))
        assertEquals(123L, ApiContract.timestamp("1970-01-01T00:00:00.123Z"))
        assertThrows(ApiException::class.java) { ApiContract.timestamp("yesterday") }
    }

    @Test fun ownerFoldersDoNotExposeIdentityOrAcceptPathTraversal() {
        val visitor = StorageIdentity.ownerKey(null)
        val first = StorageIdentity.ownerKey("one@example.com")
        val second = StorageIdentity.ownerKey("two@example.com")
        assertNotEquals(visitor, first)
        assertNotEquals(first, second)
        assertNotEquals(visitor, StorageIdentity.ownerKey("visitor"))
        assertEquals(first, StorageIdentity.ownerKey("one@example.com"))
        listOf(visitor, first, StorageIdentity.ownerKey("../../other-account")).forEach {
            assertTrue(it.matches(Regex("[a-f0-9]{64}")))
            assertFalse(it.contains("@"))
        }
    }
}
