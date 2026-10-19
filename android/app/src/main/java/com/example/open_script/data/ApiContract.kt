package com.example.open_script.data

import java.net.URI
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

/** Pure rules shared by the client and JVM tests; never resolves an untrusted hostname. */
object OriginValidation {
    private val localHttpHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun normalize(value: String, allowLocalHttp: Boolean = false): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("Informe uma origem válida, como https://seu-dominio.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        require(scheme in setOf("https", "http") && !host.isNullOrBlank()) {
            "Informe uma origem HTTPS válida."
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "A origem não pode conter usuário, senha, consulta ou fragmento."
        }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
            "Informe apenas a origem, sem /api ou outro caminho."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "A porta da origem é inválida." }
        require(scheme == "https" || (allowLocalHttp && host in localHttpHosts)) {
            "Use HTTPS. HTTP é permitido somente no desenvolvimento local."
        }
        return URI(scheme, null, host, uri.port, null, null, null).toASCIIString()
    }
}

object ApiContract {
    const val MAX_CONTENT_BYTES = 100 * 1024
    const val MAX_REQUEST_BYTES = 256 * 1024

    fun validateSettings(settings: PromptSettings) {
        if (settings.language !in setOf("pt-BR", "en", "es") || settings.font !in setOf("sans", "serif", "mono")
            || !settings.speed.isFinite() || settings.speed !in 10f..100f
            || !settings.size.isFinite() || settings.size !in 20f..120f
            || !settings.opacity.isFinite() || settings.opacity !in 0f..70f
            || !settings.color.matches(Regex("#[0-9a-fA-F]{6}"))) {
            throw ApiException("Revise os ajustes do teleprompter antes de enviá-los.", code = "validation_error")
        }
    }

    fun validateScript(document: ScriptDocument) {
        requireValidUnicode(document.title)
        requireValidUnicode(document.content)
        if (document.title.codePointCount(0, document.title.length) > 100) {
            throw ApiException("Use no máximo 100 caracteres no título.", code = "validation_error",
                fieldErrors = mapOf("title" to listOf("Use no máximo 100 caracteres.")))
        }
        if (document.content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) {
            throw ApiException("O roteiro ultrapassa 100 KiB de texto.", code = "validation_error",
                fieldErrors = mapOf("content" to listOf("Use no máximo 100 KiB em UTF-8.")))
        }
    }

    fun remoteIdentity(document: ScriptDocument): Pair<String, Int> {
        val id = document.remoteId
        if (id == null || !isUuid(id) || document.revision == null || document.revision <= 0) {
            throw ApiException("Este roteiro ainda não tem uma revisão salva no servidor.", code = "missing_revision")
        }
        return id to document.revision
    }

    fun isUuid(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: IllegalArgumentException) { false }

    fun timestamp(value: String): Long = try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: Exception) {
        throw ApiException("O servidor retornou uma data inválida.", code = "invalid_response")
    }

    private fun requireValidUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isHighSurrogate()) {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) invalidUnicode()
                index += 2
            } else {
                if (char.isLowSurrogate()) invalidUnicode()
                index++
            }
        }
    }

    private fun invalidUnicode(): Nothing = throw ApiException(
        "O texto contém um caractere Unicode inválido.", code = "validation_error",
    )
}

/** Never interpolates a display name, email or caller-supplied path into the filesystem. */
object StorageIdentity {
    fun ownerKey(ownerId: String?): String = MessageDigest.getInstance("SHA-256")
        .digest((if (ownerId == null) "visitor" else "account:$ownerId").toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
