package com.example.open_script

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.open_script.data.*
import com.example.open_script.media.*
import java.io.File
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
            val origin = connection.getString("origin", if (BuildConfig.DEBUG) "http://10.0.2.2:8000" else "").orEmpty()
            try {
                val library = withContext(Dispatchers.IO) { local.load(session?.user?.id) }
                val takes = withContext(Dispatchers.IO) { local.takes(session?.user?.id) }
                mutable.value = StudioState(library, takes, session, session?.user?.id, origin, loading = false)
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
            try { persist() } catch (error: Exception) { notify(error.message ?: "Não foi possível salvar o roteiro. Confira o espaço livre.") }
        }
    }

    private suspend fun persist() = persistence.withLock {
        val current = mutable.value
        withContext(Dispatchers.IO) { local.save(current.ownerId, current.library) }
    }

    fun flushDraft() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { try { persist() } catch (error: Exception) { notify(error.message ?: "Não foi possível salvar o roteiro.") } }
    }

    fun editDraft(title: String, content: String) {
        if (title.length > 100 || content.toByteArray(Charsets.UTF_8).size > 102400) {
            notify("Use até 100 caracteres no título e 100 KiB no roteiro.")
            return
        }
        mutable.update { it.copy(library = it.library.copy(draft = it.library.draft.copy(title = title, content = content, updatedAt = System.currentTimeMillis()))) }
        persistSoon()
    }

    fun setSettings(settings: PromptSettings) {
        mutable.update { it.copy(library = it.library.copy(settings = settings)) }
        persistSoon()
    }

    private fun savedLibrary(snapshot: LibrarySnapshot): LibrarySnapshot {
        val draft = snapshot.draft
        val scripts = (snapshot.scripts.filterNot { it.id == draft.id } + draft).sortedByDescending { it.updatedAt }
        return snapshot.copy(scripts = scripts)
    }

    fun saveDraft() {
        mutable.update { it.copy(library = savedLibrary(it.library)) }
        persistSoon()
        notify("Roteiro salvo no aparelho.")
    }

    fun newDraft() {
        mutable.update {
            val snapshot = if (it.library.draft.content.isNotBlank()) savedLibrary(it.library) else it.library
            it.copy(library = snapshot.copy(draft = ScriptDocument()))
        }
        persistSoon()
    }

    fun openScript(script: ScriptDocument, cloudCopy: Boolean = false) {
        mutable.update {
            val snapshot = if (it.library.draft.content.isNotBlank()) savedLibrary(it.library) else it.library
            val next = if (cloudCopy) script.copy(id = UUID.randomUUID().toString()) else script
            it.copy(library = snapshot.copy(draft = next), cloudScripts = null)
        }
        persistSoon()
    }

    fun deleteScript(script: ScriptDocument) {
        mutable.update {
            it.copy(library = it.library.copy(
                scripts = it.library.scripts.filterNot { document -> document.id == script.id },
                draft = if (it.library.draft.id == script.id) ScriptDocument() else it.library.draft,
            ))
        }
        persistSoon()
        notify("Cópia local excluída.")
    }

    fun startRecording(includeAudio: Boolean) {
        if (mutable.value.preparing || camera.state.value.status != CaptureStatus.IDLE) return
        mutable.update { it.copy(preparing = true) }
        val owner = mutable.value.ownerId
        val title = mutable.value.library.draft.title.ifBlank { "Meu vídeo" }
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
        if (value.origin.isBlank()) throw ApiException("Configure o endereço do servidor para usar sua conta.")
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
        withContext(Dispatchers.IO) { if (session == null) vault.clear() else vault.save(session) }
        val owner = session?.user?.id
        val library = withContext(Dispatchers.IO) { local.load(owner) }
        val takes = withContext(Dispatchers.IO) { local.takes(owner) }
        mutable.update { it.copy(session = session, ownerId = owner, library = library, takes = takes, requiresLogin = false,
            cloudScripts = null, terms = null, privacy = null, legalDocument = null) }
    }

    fun setOrigin(origin: String) = runOperation {
        val normalized = OriginValidation.normalize(origin, allowLocalHttp = BuildConfig.DEBUG)
        if (normalized != mutable.value.origin) {
            if (mutable.value.session != null) activate(null)
            connection.edit().putString("origin", normalized).apply()
            mutable.update { it.copy(origin = normalized, terms = null, privacy = null, legalDocument = null) }
        }
        notify("Servidor atualizado.")
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
        camera.unbind()
        exporter.cancel()
        super.onCleared()
    }
}
