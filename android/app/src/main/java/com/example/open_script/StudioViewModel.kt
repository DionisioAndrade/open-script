package com.example.open_script

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.open_script.data.*
import com.example.open_script.media.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StudioState(
    val library: LibrarySnapshot = LibrarySnapshot(),
    val takes: List<RecordedTake> = emptyList(),
    val session: AuthSession? = null,
    val ownerId: String? = null,
    val origin: String = "",
    val busy: Boolean = false,
    val loading: Boolean = true,
    val preparing: Boolean = false,
    val requiresLogin: Boolean = false,
    val message: String? = null,
    val cloudScripts: List<ScriptDocument>? = null,
    val legalDocument: LegalDocument? = null,
    val terms: LegalReceipt? = null,
    val privacy: LegalReceipt? = null,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val local = LocalLibrary(application)
    private val vault = CredentialVault(application)
    private val connection = application.getSharedPreferences("connection", 0)
    private val mutable = MutableStateFlow(StudioState())
    val state = mutable.asStateFlow()
    val camera = NativeCameraController(application)
    val exporter = NativeExporter(application)
    private var saveJob: Job? = null
    private val persistence = Mutex()
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            val session = withContext(Dispatchers.IO) { runCatching { vault.load() }.getOrNull() }
            val savedOrigin = connection.getString("origin", null)
            val origin = ServerConnection.resolveOrigin(savedOrigin, BuildConfig.API_ORIGIN, session != null, BuildConfig.DEBUG)
            try {
                if (session != null && savedOrigin == null && origin.isNotBlank()) {
                    withContext(Dispatchers.IO) { saveConnectionOrigin(origin) }
                }
                val library = withContext(Dispatchers.IO) { local.load(session?.user?.id) }
                val takes = withContext(Dispatchers.IO) { local.takes(session?.user?.id) }
                mutable.value = StudioState(library, takes, session, session?.user?.id, origin, loading = false,
                    requiresLogin = session != null && origin.isBlank())
            } catch (error: Exception) {
                mutable.update { it.copy(loading = false, message = error.message ?: "Não foi possível abrir os dados locais.") }
            }
        }
    }

    fun notify(message: String) { mutable.update { it.copy(message = message) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }

    private fun persistSoon() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            try { persist() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notify(error.message ?: "Não foi possível salvar o roteiro. Confira o espaço livre.") }
        }
    }

    private suspend fun persist() = persistence.withLock {
        val current = mutable.value
        if (current.loading) return@withLock
        withContext(Dispatchers.IO) { local.save(current.ownerId, current.library) }
    }

    fun flushDraft() {
        if (mutable.value.loading) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            try { persist() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notify(error.message ?: "Não foi possível salvar o roteiro.") }
        }
    }

    fun editDraft(content: String) {
        if (mutable.value.loading) return
        if (content.toByteArray(Charsets.UTF_8).size > 102400) {
            notify("Use até 100 KiB no roteiro.")
            return
        }
        mutable.update { it.copy(library = it.library.copy(draft = it.library.draft.copy(content = content, updatedAt = System.currentTimeMillis()))) }
        persistSoon()
    }

    fun setSettings(settings: PromptSettings) {
        if (mutable.value.loading) return
        mutable.update { it.copy(library = it.library.copy(settings = settings)) }
        persistSoon()
    }

    private fun savedLibrary(snapshot: LibrarySnapshot): LibrarySnapshot {
        val draft = snapshot.draft
        val scripts = (snapshot.scripts.filterNot { it.id == draft.id } + draft).sortedByDescending { it.updatedAt }
        return snapshot.copy(scripts = scripts)
    }

    fun openScript(script: ScriptDocument, cloudCopy: Boolean = false) {
        mutable.update {
            val snapshot = if (it.library.draft.content.isNotBlank()) savedLibrary(it.library) else it.library
            val next = if (cloudCopy) script.copy(id = UUID.randomUUID().toString()) else script
            it.copy(library = snapshot.copy(draft = next), cloudScripts = null)
        }
        persistSoon()
    }

    fun startRecording(includeAudio: Boolean) {
        if (mutable.value.preparing || camera.state.value.status != CaptureStatus.IDLE) return
        mutable.update { it.copy(preparing = true) }
        val owner = mutable.value.ownerId
        val title = "Take ${SimpleDateFormat("dd/MM '·' HH:mm", Locale.getDefault()).format(Date())}"
        viewModelScope.launch {
            try {
                persist()
                val file = withContext(Dispatchers.IO) { local.newTakeFile(owner) }
                camera.start(file, includeAudio) { result ->
                    viewModelScope.launch {
                        if (result.durationMs > 0 && result.width > 0 && result.file.length() > 0) {
                            try {
                                val take = RecordedTake(id = result.file.nameWithoutExtension, title = title, path = result.file.absolutePath,
                                    durationMs = result.durationMs, width = result.width, height = result.height)
                                val takes = withContext(Dispatchers.IO) { local.addTake(owner, take); local.takes(owner) }
                                if (mutable.value.ownerId == owner) mutable.update { it.copy(takes = takes) }
                                notify(result.error ?: "Take salvo no aparelho.")
                            } catch (error: Exception) { notify(error.message ?: "O take será recuperado ao reabrir a biblioteca.") }
                        } else notify(result.error ?: "Não foi possível concluir a gravação. Tente novamente.")
                    }
                }
            } catch (error: Exception) { notify(error.message ?: "Não foi possível preparar a gravação.") }
            finally { mutable.update { it.copy(preparing = false) } }
        }
    }

    fun deleteTake(take: RecordedTake) = runOperation {
        val owner = mutable.value.ownerId
        val takes = withContext(Dispatchers.IO) { local.deleteTake(owner, take); local.takes(owner) }
        mutable.update { it.copy(takes = takes) }
        notify("Take excluído do aparelho.")
    }

    fun renameTake(takeId: String, title: String) {
        val current = mutable.value
        if (current.loading || current.busy) return
        val owner = current.ownerId
        runOperation {
            val takes = withContext(Dispatchers.IO) { local.renameTake(owner, takeId, title) }
            mutable.update { if (it.ownerId == owner) it.copy(takes = takes) else it }
            if (mutable.value.ownerId == owner) notify("Nome do take atualizado.")
        }
    }

    fun copyVideo(source: File, destination: android.net.Uri) = runOperation {
        withContext(Dispatchers.IO) {
            source.inputStream().use { input ->
                getApplication<Application>().contentResolver.openOutputStream(destination, "wt").use { output ->
                    requireNotNull(output) { "Não foi possível abrir o destino escolhido." }
                    input.copyTo(output)
                }
            }
        }
        notify("Vídeo salvo no destino escolhido.")
    }

    private fun api(authenticated: Boolean = true): ApiClient {
        val value = mutable.value
        if (value.origin.isBlank()) throw ApiException("O acesso à conta está indisponível nesta versão do aplicativo.")
        if (authenticated && (value.session == null || value.requiresLogin)) throw ApiException("Entre na sua conta para continuar.")
        return ApiClient(value.origin, if (authenticated) value.session?.accessToken else null)
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = if (error is ApiException) {
                    if (error.status == 401) mutable.update { it.copy(requiresLogin = true) }
                    if (error.status == 409) "O roteiro mudou na conta. Sua cópia local foi mantida. Busque a versão da conta para comparar."
                    else error.fieldErrors.values.flatten().firstOrNull() ?: error.message
                } else error.message
                notify(message ?: "Não foi possível concluir. Confira a conexão e tente novamente.")
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun activate(session: AuthSession?) {
        saveJob?.cancelAndJoin()
        persist()
        val previousOrigin = mutable.value.origin
        val origin = if (session == null && previousOrigin.isBlank()) BuildConfig.API_ORIGIN else previousOrigin
        withContext(Dispatchers.IO) {
            if (session == null) {
                vault.clear()
                if (origin != previousOrigin) saveConnectionOrigin(origin)
            } else {
                saveConnectionOrigin(origin)
                vault.save(session)
            }
        }
        val owner = session?.user?.id
        val library = withContext(Dispatchers.IO) { local.load(owner) }
        val takes = withContext(Dispatchers.IO) { local.takes(owner) }
        mutable.update { it.copy(session = session, ownerId = owner, library = library, takes = takes, origin = origin, requiresLogin = false,
            cloudScripts = null, terms = null, privacy = null, legalDocument = null) }
    }

    private fun saveConnectionOrigin(origin: String) {
        check(connection.edit().putString("origin", origin).commit()) { "Não foi possível salvar seu acesso no aparelho." }
    }

    fun login(email: String, password: String) = runOperation {
        val session = api(false).login(email, password)
        activate(session)
        notify("Você entrou na sua conta.")
    }

    fun signup(name: String, email: String, password: String, confirmation: String) = runOperation {
        val value = mutable.value
        val terms = value.terms ?: throw ApiException("Leia e confirme os Termos de Uso.")
        val privacy = value.privacy ?: throw ApiException("Leia e confirme a Política de Privacidade.")
        activate(api(false).signup(name, email, password, confirmation, terms, privacy))
        notify("Conta criada.")
    }

    fun logout() = runOperation {
        var remoteRevoked = true
        try { mutable.value.session?.let { ApiClient(mutable.value.origin, it.accessToken).logout() } }
        catch (error: Exception) { remoteRevoked = error is ApiException && error.status == 401 }
        activate(null)
        notify(if (remoteRevoked) "Você saiu da conta. As cópias locais foram preservadas." else "Saída local concluída. O servidor estava indisponível; o acesso remoto permanece válido até expirar.")
    }

    fun passwordReset(email: String) = runOperation { api(false).requestPasswordReset(email); notify("Se houver uma conta elegível, enviaremos as instruções por e-mail.") }
    fun legal(kind: String) = runOperation { mutable.update { it.copy(legalDocument = null) }; val doc = api(false).document(kind); mutable.update { it.copy(legalDocument = doc) } }
    fun dismissLegal() { mutable.update { it.copy(legalDocument = null) } }
    fun confirmLegal() = runOperation {
        val document = mutable.value.legalDocument ?: return@runOperation
        val receipt = api(false).confirmRead(document)
        mutable.update { if (receipt.kind == "terms") it.copy(terms = receipt, legalDocument = null) else it.copy(privacy = receipt, legalDocument = null) }
    }
    fun fetchScripts() = runOperation { val scripts = api().listScripts(); mutable.update { it.copy(cloudScripts = scripts) } }
    fun dismissCloud() { mutable.update { it.copy(cloudScripts = null) } }
    fun uploadDraft() = runOperation {
        val draft = mutable.value.library.draft
        val remote = if (draft.remoteId == null) api().createScript(draft) else api().updateScript(draft)
        mutable.update { it.copy(library = it.library.afterUpload(draft, remote)) }
        persist()
        notify("Roteiro enviado à conta.")
    }
    fun pullPreferences() = runOperation { val prefs = api().preferences(); setSettings(prefs.settings); persist(); notify("Ajustes da conta aplicados.") }
    fun pushPreferences() = runOperation {
        val client = api()
        val revision = client.preferences().revision
        client.updatePreferences(mutable.value.library.settings, revision)
        notify("Ajustes enviados à conta.")
    }

    override fun onCleared() {
        // A normal Activity exit cancels viewModelScope. Finish its latest text write
        // independently, behind any write already in progress, before releasing this scope.
        val latest = mutable.value
        saveJob?.cancel()
        if (!latest.loading) {
            val finalWrite = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            finalWrite.launch {
                try { persistence.withLock { local.save(latest.ownerId, latest.library) } }
                catch (error: Exception) { android.util.Log.w("OpenScript", "Não foi possível concluir o salvamento do roteiro.", error) }
                finally { finalWrite.cancel() }
            }
        }
        camera.unbind()
        exporter.cancel()
        super.onCleared()
    }
}
