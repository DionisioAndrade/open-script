package com.example.open_script.data

import java.util.UUID

data class PromptSettings(
    val language: String = "pt-BR",
    val speed: Float = 40f,
    val font: String = "sans",
    val size: Float = 36f,
    val autoSize: Boolean = true,
    val opacity: Float = 0f,
    val color: String = "#ffffff",
    val showPrompt: Boolean = true,
    val mirror: Boolean = true,
)

data class ScriptDocument(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val remoteId: String? = null,
    val revision: Int? = null,
)

data class RecordedTake(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val path: String,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val width: Int = 1080,
    val height: Int = 1920,
    val sizeBytes: Long = 0L,
)

/** Titles are display metadata; a rename never changes the recording's filename or identity. */
object TakeNames {
    const val MAX_LENGTH = 120

    fun normalize(value: String): String {
        val title = value.trim()
        require(title.isNotEmpty()) { "Digite um nome para o take." }
        require(title.codePointCount(0, title.length) <= MAX_LENGTH) { "Use até $MAX_LENGTH caracteres no nome do take." }
        return title
    }
}

data class UserProfile(val id: String, val displayName: String, val email: String)
data class AuthSession(val accessToken: String, val expiresAt: String, val user: UserProfile) {
    override fun toString(): String = "AuthSession(userId=${user.id}, expiresAt=$expiresAt)"
}
data class LibrarySnapshot(
    val draft: ScriptDocument = ScriptDocument(),
    val scripts: List<ScriptDocument> = emptyList(),
    val settings: PromptSettings = PromptSettings(),
)

data class RemotePreferences(val settings: PromptSettings, val revision: Int)
data class LegalSection(val title: String, val paragraphs: List<String>)
data class LegalDocument(
    val kind: String,
    val title: String,
    val version: String,
    val sections: List<LegalSection>,
    val openedReceipt: String,
)
data class LegalReceipt(val kind: String, val version: String, val receipt: String)

class ApiException(
    message: String,
    val status: Int = 0,
    val code: String = "",
    val fieldErrors: Map<String, List<String>> = emptyMap(),
) : Exception(message)
