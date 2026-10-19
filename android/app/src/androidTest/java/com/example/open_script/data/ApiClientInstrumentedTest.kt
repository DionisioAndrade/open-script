package com.example.open_script.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** All HTTP stays on an ephemeral loopback port. No real server, account, email or secret is used. */
@RunWith(AndroidJUnit4::class)
class ApiClientInstrumentedTest {
    @Test fun loginPostsJsonAndDecodesSessionWithoutSendingBearerOrCookies() = runBlocking {
        LocalApiServer(listOf(Reply(200, authResponse()))).use { server ->
            val session = ApiClient(server.origin, TOKEN).login(EMAIL, PASSWORD)
            server.assertComplete()

            assertEquals(TOKEN, session.accessToken)
            assertEquals("2026-11-19T12:00:00+00:00", session.expiresAt)
            assertEquals(UserProfile(USER_ID, "Ana de teste", EMAIL), session.user)
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/login/", request.path)
            assertTrue(request.headers.getValue("content-type").startsWith("application/json"))
            assertFalse(request.headers.containsKey("authorization"))
            assertFalse(request.headers.containsKey("cookie"))
            assertEquals(EMAIL, request.json().getString("email"))
            assertEquals(PASSWORD, request.json().getString("password"))
            assertEquals(setOf("email", "password"), request.json().keys().asSequence().toSet())
        }
    }

    @Test fun scriptCreateUpdateAndDeletePreserveLocalIdAndSendBearerWithRevision() = runBlocking {
        val initial = ScriptDocument(title = "Roteiro local", content = "Texto original")
        LocalApiServer(listOf(
            Reply(201, scriptResponse(initial.title, initial.content, 1)),
            Reply(200, scriptResponse(initial.title, "Texto revisado", 2)),
            Reply(204),
        )).use { server ->
            val client = ApiClient(server.origin, TOKEN)
            val created = client.createScript(initial)
            val updated = client.updateScript(created.copy(content = "Texto revisado"))
            client.deleteScript(updated)
            server.assertComplete()

            assertEquals(initial.id, created.id)
            assertEquals(initial.id, updated.id)
            assertEquals(SCRIPT_ID, created.remoteId)
            assertEquals(1, created.revision)
            assertEquals(2, updated.revision)
            assertEquals("Texto revisado", updated.content)
            val requests = server.requests
            assertEquals(listOf("POST", "PATCH", "DELETE"), requests.map { it.method })
            assertEquals(listOf("/api/v1/scripts/", "/api/v1/scripts/$SCRIPT_ID/", "/api/v1/scripts/$SCRIPT_ID/"), requests.map { it.path })
            requests.forEach {
                assertEquals("Bearer $TOKEN", it.headers["authorization"])
                assertFalse(it.headers.containsKey("cookie"))
                assertTrue(it.headers.getValue("content-type").startsWith("application/json"))
            }
            assertEquals(setOf("title", "content"), requests[0].json().keys().asSequence().toSet())
            assertEquals(1, requests[1].json().getInt("revision"))
            assertEquals("Texto revisado", requests[1].json().getString("content"))
            assertEquals(2, requests[2].json().getInt("revision"))
            assertEquals(setOf("revision"), requests[2].json().keys().asSequence().toSet())
        }
    }

    @Test fun unauthorizedConflictAndFieldErrorsKeepTheirHttpStatusAndCode() = runBlocking {
        LocalApiServer(listOf(
            Reply(401, errorResponse("unauthorized", "Entre novamente.")),
            Reply(409, JSONObject().put("error", JSONObject()
                .put("code", "revision_conflict").put("message", "Este recurso foi alterado.")
                .put("details", JSONObject().put("current_revision", 3)))),
            Reply(400, JSONObject().put("error", JSONObject()
                .put("code", "validation_error").put("message", "Revise os campos informados.")
                .put("fields", JSONObject().put("email", JSONArray().put(JSONObject()
                    .put("message", "Informe um e-mail válido.").put("code", "invalid")))))),
        )).use { server ->
            val client = ApiClient(server.origin, TOKEN)
            val unauthorized = failure { client.me() }
            val conflict = failure { client.updateScript(ScriptDocument(remoteId = SCRIPT_ID, revision = 1)) }
            val validation = failure { client.login("inválido", PASSWORD) }
            server.assertComplete()

            assertEquals(401, unauthorized.status)
            assertEquals("unauthorized", unauthorized.code)
            assertEquals(409, conflict.status)
            assertEquals("revision_conflict", conflict.code)
            assertEquals(400, validation.status)
            assertEquals("validation_error", validation.code)
            assertEquals(listOf("Informe um e-mail válido."), validation.fieldErrors["email"])
        }
    }

    @Test fun legalReadReceiptsAreMappedToIndependentExplicitSignupAcceptances() = runBlocking {
        val signupEmail = "nova.pessoa@example.test"
        LocalApiServer(listOf(
            Reply(200, legalResponse("terms", "Termos de Uso", "abertura-termos-teste")),
            Reply(200, readResponse("terms", "leitura-termos-teste")),
            Reply(200, legalResponse("privacy", "Política de Privacidade", "abertura-privacidade-teste")),
            Reply(200, readResponse("privacy", "leitura-privacidade-teste")),
            Reply(201, authResponse("Nova pessoa", signupEmail)),
        )).use { server ->
            val client = ApiClient(server.origin)
            val terms = client.document("terms")
            val termsReceipt = client.confirmRead(terms)
            val privacy = client.document("privacy")
            val privacyReceipt = client.confirmRead(privacy)
            val session = client.signup("Nova pessoa", signupEmail, PASSWORD, PASSWORD, termsReceipt, privacyReceipt)
            server.assertComplete()

            assertEquals("Termos de Uso", terms.title)
            assertEquals(listOf("Parágrafo usado apenas no teste local."), terms.sections.single().paragraphs)
            assertEquals(LegalReceipt("terms", VERSION, "leitura-termos-teste"), termsReceipt)
            assertEquals(LegalReceipt("privacy", VERSION, "leitura-privacidade-teste"), privacyReceipt)
            assertEquals(signupEmail, session.user.email)
            assertEquals(listOf("/api/v1/legal/terms/?lang=pt-BR", "/api/v1/legal/read/",
                "/api/v1/legal/privacy/?lang=pt-BR", "/api/v1/legal/read/", "/api/v1/auth/signup/"), server.requests.map { it.path })
            val termsRead = server.requests[1].json()
            assertEquals("terms", termsRead.getString("kind"))
            assertEquals(VERSION, termsRead.getString("version"))
            assertEquals("abertura-termos-teste", termsRead.getString("opened_receipt"))
            assertEquals(true, termsRead.get("confirmed"))
            assertEquals("abertura-privacidade-teste", server.requests[3].json().getString("opened_receipt"))
            val signup = server.requests[4].json()
            assertEquals(true, signup.get("accept_terms"))
            assertEquals(true, signup.get("accept_privacy"))
            assertEquals(VERSION, signup.getString("terms_version"))
            assertEquals(VERSION, signup.getString("privacy_version"))
            assertEquals("leitura-termos-teste", signup.getString("terms_receipt"))
            assertEquals("leitura-privacidade-teste", signup.getString("privacy_receipt"))
            server.requests.forEach {
                assertFalse(it.headers.containsKey("authorization"))
                assertFalse(it.headers.containsKey("cookie"))
            }
        }
    }

    private suspend fun failure(block: suspend () -> Any?): ApiException {
        try { block() } catch (error: ApiException) { return error }
        throw AssertionError("A chamada deveria retornar ApiException.")
    }

    private fun authResponse(name: String = "Ana de teste", email: String = EMAIL): JSONObject = JSONObject()
        .put("access_token", TOKEN).put("token_type", "Bearer").put("expires_at", "2026-11-19T12:00:00+00:00")
        .put("user", JSONObject().put("id", USER_ID).put("display_name", name).put("email", email))

    private fun scriptResponse(title: String, content: String, revision: Int): JSONObject = JSONObject()
        .put("id", SCRIPT_ID).put("title", title).put("content", content).put("revision", revision)
        .put("created_at", "2026-10-19T12:00:00+00:00").put("updated_at", "2026-10-19T12:00:01+00:00")

    private fun errorResponse(code: String, message: String): JSONObject = JSONObject()
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun legalResponse(kind: String, title: String, receipt: String): JSONObject = JSONObject()
        .put("document", JSONObject().put("kind", kind).put("title", title).put("version", VERSION)
            .put("updated_at", "12 de outubro de 2026").put("language", "pt-BR").put("contact_pending", true)
            .put("references", JSONArray()).put("sections", JSONArray().put(JSONObject()
                .put("title", "Documento de teste").put("paragraphs", JSONArray().put("Parágrafo usado apenas no teste local.")))))
        .put("opened_receipt", receipt).put("receipt_expires_in", 1800)

    private fun readResponse(kind: String, receipt: String): JSONObject = JSONObject()
        .put("kind", kind).put("version", VERSION).put("read_receipt", receipt).put("receipt_expires_in", 1800)

    private data class Reply(val status: Int, val body: JSONObject? = null)
    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
        fun json(): JSONObject = JSONObject(body)
    }

    /** Fixed responses, one worker, bounded reads and explicit close; never follows external hosts. */
    private class LocalApiServer(private val replies: List<Reply>) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val origin = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<Request>()
        private val completed = CountDownLatch(1)
        private val error = AtomicReference<Throwable?>(null)
        @Volatile private var active: Socket? = null
        private val worker = Thread({
            try {
                replies.forEach { reply ->
                    server.accept().use { socket ->
                        active = socket
                        socket.soTimeout = 5_000
                        requests.add(readRequest(BufferedInputStream(socket.getInputStream())))
                        val body = reply.body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                        val reason = when (reply.status) { 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; else -> "Error" }
                        val headers = "HTTP/1.1 ${reply.status} $reason\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush()
                        }
                        active = null
                    }
                }
            } catch (failure: Throwable) {
                if (!server.isClosed) error.set(failure)
            } finally { completed.countDown() }
        }, "openscript-loopback-api-test").apply { isDaemon = true; start() }

        fun assertComplete() {
            assertTrue("O servidor local não concluiu as chamadas esperadas.", completed.await(3, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("Falha no servidor HTTP local do teste.", it) }
            assertEquals(replies.size, requests.size)
        }

        override fun close() {
            runCatching { active?.close() }
            runCatching { server.close() }
            worker.join(1_000)
        }

        private fun readRequest(input: BufferedInputStream): Request {
            val start = readLine(input).split(' ')
            require(start.size == 3)
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                require(separator > 0)
                headers[line.substring(0, separator).lowercase(Locale.ROOT)] = line.substring(separator + 1).trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            require(length in 0..ApiContract.MAX_REQUEST_BYTES)
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                if (count < 0) throw EOFException("Corpo HTTP incompleto no teste local.")
                offset += count
            }
            return Request(start[0], start[1], headers, body.toString(Charsets.UTF_8))
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) throw EOFException("Cabeçalho HTTP incompleto no teste local.")
                if (next == 10) return bytes.toString(Charsets.US_ASCII.name())
                if (next != 13) bytes.write(next)
                require(bytes.size() <= 8192)
            }
        }
    }

    private companion object {
        val TOKEN = "a".repeat(43)
        const val USER_ID = "335c5728-2b8c-48e7-bfe3-ed7edc43fed1"
        const val SCRIPT_ID = "39d4e049-b5a7-42b5-aaef-a8ea6cb92d93"
        const val EMAIL = "ana@example.test"
        const val PASSWORD = "Dummy-local-test-password-4926!"
        const val VERSION = "2026-10-12.3"
    }
}
