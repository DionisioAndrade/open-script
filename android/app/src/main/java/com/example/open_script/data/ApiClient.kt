package com.example.open_script.data

import com.example.open_script.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** An explicit, one-origin client. No cookies, redirects, retries, video upload or background sync. */
class ApiClient(origin: String, private val token: String? = null) {
    val origin: String = OriginValidation.normalize(origin, allowLocalHttp = BuildConfig.DEBUG)

    suspend fun login(email: String, password: String): AuthSession = io {
        JsonCodec.session(request("POST", "auth/login/", JSONObject().put("email", email).put("password", password)))
    }

    suspend fun signup(
        displayName: String, email: String, password: String, passwordConfirm: String,
        terms: LegalReceipt, privacy: LegalReceipt,
    ): AuthSession = io {
        require(terms.kind == "terms" && privacy.kind == "privacy") { "Os dois documentos precisam ser confirmados." }
        val body = JSONObject().put("display_name", displayName).put("email", email)
            .put("password", password).put("password_confirm", passwordConfirm)
            .put("accept_terms", true).put("accept_privacy", true)
            .put("terms_version", terms.version).put("privacy_version", privacy.version)
            .put("terms_receipt", terms.receipt).put("privacy_receipt", privacy.receipt)
        JsonCodec.session(request("POST", "auth/signup/", body))
    }

    suspend fun logout(): Unit = io { request("POST", "auth/logout/", JSONObject(), authenticated = true); Unit }

    suspend fun me(): UserProfile = io { JsonCodec.user(request("GET", "me/", authenticated = true).getJSONObject("user")) }

    suspend fun requestPasswordReset(email: String): Unit = io {
        request("POST", "auth/password-reset/", JSONObject().put("email", email).put("lang", "pt-BR")); Unit
    }

    suspend fun listScripts(): List<ScriptDocument> = io {
        val scripts = linkedMapOf<String, ScriptDocument>()
        var offset = 0
        do {
            currentCoroutineContext().ensureActive()
            val page = request("GET", "scripts/?limit=20&offset=$offset", authenticated = true)
            val items = page.getJSONArray("items")
            val total = page.getInt("total")
            if (total !in 0..1000 || items.length() > 20 || page.getInt("offset") != offset) invalidResponse()
            for (index in 0 until items.length()) {
                val script = JsonCodec.remoteScript(items.getJSONObject(index))
                scripts[script.id] = script
            }
            if (scripts.size > 1000) invalidResponse()
            offset += items.length()
            if (items.length() == 0 || offset >= total) break
        } while (offset < 1000)
        scripts.values.toList()
    }

    suspend fun createScript(document: ScriptDocument): ScriptDocument = io {
        ApiContract.validateScript(document)
        val body = JSONObject().put("title", document.title).put("content", document.content)
        JsonCodec.remoteScript(request("POST", "scripts/", body, authenticated = true), localId = document.id)
    }

    suspend fun updateScript(document: ScriptDocument): ScriptDocument = io {
        ApiContract.validateScript(document)
        val (id, revision) = ApiContract.remoteIdentity(document)
        val body = JSONObject().put("revision", revision).put("title", document.title).put("content", document.content)
        JsonCodec.remoteScript(request("PATCH", "scripts/$id/", body, authenticated = true), localId = document.id)
    }

    suspend fun deleteScript(document: ScriptDocument): Unit = io {
        val (id, revision) = ApiContract.remoteIdentity(document)
        request("DELETE", "scripts/$id/", JSONObject().put("revision", revision), authenticated = true); Unit
    }

    suspend fun preferences(): RemotePreferences = io {
        remotePreferences(request("GET", "preferences/", authenticated = true))
    }

    suspend fun updatePreferences(settings: PromptSettings, revision: Int): RemotePreferences = io {
        ApiContract.validateSettings(settings)
        if (revision < 1) throw ApiException("Busque as preferências antes de enviá-las.", code = "missing_revision")
        remotePreferences(request("PATCH", "preferences/", JsonCodec.settings(settings).put("revision", revision), authenticated = true))
    }

    suspend fun document(kind: String): LegalDocument = io {
        require(kind in setOf("terms", "privacy")) { "Documento inválido." }
        val result = request("GET", "legal/$kind/?lang=pt-BR")
        val document = result.getJSONObject("document")
        val sections = document.getJSONArray("sections")
        LegalDocument(
            kind = document.getString("kind"), title = document.getString("title"), version = document.getString("version"),
            sections = (0 until sections.length()).map { index ->
                val section = sections.getJSONObject(index)
                val paragraphs = section.getJSONArray("paragraphs")
                LegalSection(section.getString("title"), (0 until paragraphs.length()).map { paragraphs.getString(it) })
            },
            openedReceipt = result.getString("opened_receipt"),
        ).also { if (it.kind != kind) invalidResponse() }
    }

    suspend fun confirmRead(document: LegalDocument): LegalReceipt = io {
        require(document.kind in setOf("terms", "privacy")) { "Documento inválido." }
        val result = request("POST", "legal/read/", JSONObject()
            .put("kind", document.kind).put("version", document.version)
            .put("opened_receipt", document.openedReceipt).put("confirmed", true))
        if (result.getString("kind") != document.kind || result.getString("version") != document.version) invalidResponse()
        LegalReceipt(document.kind, document.version, result.getString("read_receipt"))
    }

    private fun remotePreferences(result: JSONObject): RemotePreferences {
        val revision = result.getInt("revision")
        if (revision < 1) invalidResponse()
        return RemotePreferences(JsonCodec.settings(result), revision)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            throw error
        } catch (_: JSONException) {
            invalidResponse()
        } catch (_: IOException) {
            throw ApiException("Não foi possível acessar sua conta. Confira sua conexão e tente novamente.", code = "connection_error")
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null, authenticated: Boolean = false): JSONObject {
        val bearer = if (authenticated) token?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
            ?: throw ApiException("Entre novamente para acessar sua conta.", status = 401, code = "unauthorized") else null
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (bytes != null && bytes.size > ApiContract.MAX_REQUEST_BYTES) {
            throw ApiException("A requisição ultrapassa o limite de 256 KiB.", status = 413, code = "payload_too_large")
        }
        val connection = URL("$origin/api/v1/$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (bytes != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 300..399) throw ApiException(
                "O serviço de conta está indisponível no momento. Tente novamente mais tarde.", status, "redirect_refused",
            )
            if (status == 204) return JSONObject()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) invalidResponse()
                    output.write(buffer, 0, count)
                }
            }
            val text = output.toString(Charsets.UTF_8.name())
            val result = try { JSONObject(text) } catch (_: JSONException) {
                if (status !in 200..299) throw ApiException(defaultMessage(status), status, "http_error")
                invalidResponse()
            }
            if (status !in 200..299) throw apiError(result, status)
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(result: JSONObject, status: Int): ApiException {
        val error = result.optJSONObject("error") ?: return ApiException(defaultMessage(status), status, "http_error")
        val fields = error.optJSONObject("fields")
        val messages = mutableMapOf<String, List<String>>()
        fields?.keys()?.forEach { key ->
            val values = fields.optJSONArray(key)
            if (values != null) messages[key] = (0 until values.length()).mapNotNull {
                values.optJSONObject(it)?.optString("message")?.takeIf(String::isNotBlank)
            }
        }
        return ApiException(error.optString("message", defaultMessage(status)), status, error.optString("code", "http_error"), messages)
    }

    private fun defaultMessage(status: Int): String = when (status) {
        401 -> "Seu acesso expirou. Entre novamente."
        409 -> "Há uma versão mais recente no servidor. Seu conteúdo local foi preservado."
        429 -> "Muitas tentativas. Aguarde alguns minutos."
        else -> "Não foi possível concluir a solicitação ao servidor."
    }

    private fun invalidResponse(): Nothing = throw ApiException("O servidor retornou uma resposta inválida.", code = "invalid_response")

    private companion object { const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024 }
}
