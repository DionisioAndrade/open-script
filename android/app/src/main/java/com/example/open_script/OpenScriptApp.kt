@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.open_script

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.open_script.data.*
import com.example.open_script.media.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Navy = Color(0xFF0F172A)
private val Red = Color(0xFFF34D4D)
private val Muted = Color(0xFFB6C2D5)

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun OpenScriptApp(model: StudioViewModel = viewModel()) {
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    val state by model.state.collectAsStateWithLifecycle()
    val capture by model.camera.state.collectAsStateWithLifecycle()
    val export by model.exporter.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var quickSpeed by rememberSaveable { mutableStateOf(false) }
    var front by rememberSaveable { mutableStateOf(true) }
    var rehearsal by rememberSaveable { mutableStateOf(false) }
    var countdown by rememberSaveable { mutableIntStateOf(0) }
    var silentConfirm by remember { mutableStateOf(false) }
    var stopConfirm by remember { mutableStateOf(false) }
    var chosenTakeId by rememberSaveable { mutableStateOf<String?>(null) }
    var playingTakeId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosenTake = state.takes.firstOrNull { it.id == chosenTakeId }
    val playingTake = state.takes.firstOrNull { it.id == playingTakeId }
    var deletingTake by remember { mutableStateOf<RecordedTake?>(null) }
    var renamingTakeId by rememberSaveable { mutableStateOf<String?>(null) }
    val renamingTake = state.takes.firstOrNull { it.id == renamingTakeId }
    var savePath by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val scroll = rememberScrollState()
    val takesPageState = rememberSaveableStateHolder()
    var requested by rememberSaveable { mutableStateOf(false) }
    fun permitted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var cameraAllowed by remember { mutableStateOf(permitted(Manifest.permission.CAMERA)) }
    var audioAllowed by remember { mutableStateOf(permitted(Manifest.permission.RECORD_AUDIO)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraAllowed = permitted(Manifest.permission.CAMERA)
        audioAllowed = permitted(Manifest.permission.RECORD_AUDIO)
    }
    val saveContract = remember {
        object : ActivityResultContracts.CreateDocument("video/mp4") {
            override fun createIntent(context: android.content.Context, input: String): Intent =
                super.createIntent(context, input).setType(ExportProfiles.formatForFileName(input)?.mimeType ?: "video/*")
        }
    }
    val fileDestination = rememberLauncherForActivityResult(saveContract) { uri ->
        savePath?.let { path -> if (uri != null) model.copyVideo(File(path), uri) }
        savePath = null
    }
    fun save(file: File, title: String) {
        val format = ExportProfiles.formatForFileName(file.name) ?: run { model.notify("Não foi possível identificar o formato do vídeo."); return }
        savePath = file.absolutePath
        fileDestination.launch(title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(80).ifBlank { "OpenScript" } + ".${format.extension}")
    }
    fun share(file: File) {
        runCatching {
            val format = requireNotNull(ExportProfiles.formatForFileName(file.name))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).setType(format.mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Compartilhar vídeo"))
        }.onFailure { model.notify("Não foi possível compartilhar. Tente salvar uma cópia.") }
    }
    val recording = capture.status != CaptureStatus.IDLE
    val locked = recording || state.preparing || countdown > 0 || export.running
    val promptRunning = rehearsal || capture.status == CaptureStatus.RECORDING
    val density = LocalDensity.current.density
    val preferences = state.library.settings
    LaunchedEffect(locked, page) { if (locked || page != 0) quickSpeed = false }

    LaunchedEffect(state.loading, page) {
        if (!state.loading && page == 0 && !requested) {
            requested = true
            permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { countdown = 0; rehearsal = false; model.flushDraft() }
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraAllowed = permitted(Manifest.permission.CAMERA)
                audioAllowed = permitted(Manifest.permission.RECORD_AUDIO)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(locked, rehearsal) {
        val window = (context as? Activity)?.window
        if (locked || rehearsal) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbars.showSnackbar(it); model.clearMessage() } }
    LaunchedEffect(capture.error) { capture.error?.let { model.notify(it) } }
    LaunchedEffect(state.library.draft.id) { scroll.scrollTo(0); rehearsal = false }
    LaunchedEffect(promptRunning, capture.status, preferences.speed, state.library.draft.id) {
        if (promptRunning && capture.status != CaptureStatus.PAUSED) {
            var lastFrame = 0L
            while (true) {
                withFrameNanos { frame ->
                    if (lastFrame != 0L && scroll.value < scroll.maxValue) scroll.dispatchRawDelta((frame - lastFrame) / 1_000_000_000f * preferences.speed * density)
                    lastFrame = frame
                }
                if (scroll.maxValue > 0 && scroll.value >= scroll.maxValue) { rehearsal = false; break }
            }
        }
    }
    BackHandler(locked) { if (countdown > 0) countdown = 0 else stopConfirm = true }
    fun beginCountdown() {
        if (!capture.ready || locked || state.busy) { model.notify("Aguarde a câmera ficar pronta."); return }
        rehearsal = false
        scope.launch { scroll.scrollTo(0) }
        countdown = 5
    }
    LaunchedEffect(countdown > 0) {
        if (countdown > 0) {
            repeat(5) { delay(1000); countdown-- }
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && capture.ready) model.startRecording(audioAllowed)
        }
    }

    chosenTake?.let { take ->
        ExportScreen(take, model.exporter, { save(it, take.title) }, ::share,
            onClose = { if (!export.running && !state.busy) { chosenTakeId = null; model.exporter.clear() } },
            saving = state.busy, snackbarHost = { SnackbarHost(snackbars) })
        return
    }

    val studioCameraVisible = !state.loading && page == 0 && cameraAllowed
    Box(Modifier.fillMaxSize().background(Navy)) {
    // One full-window camera remains behind the controls. Recording only fades the UI,
    // revealing the existing preview instead of changing its bounds or rebinding it.
    if (studioCameraVisible) CameraPreview(model.camera, front, preferences.mirror)
    Scaffold(containerColor = if (studioCameraVisible) Color.Transparent else Navy,
        contentColor = MaterialTheme.colorScheme.onBackground, snackbarHost = { SnackbarHost(snackbars) },
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!landscape) StudioChrome(visible = !recording) {
                StudioNavigation(page, !locked && !state.loading && !state.busy, false) { model.flushDraft(); rehearsal = false; page = it }
            }
        },
    ) { padding ->
        if (state.loading) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (landscape) StudioChrome(visible = !recording) {
                StudioNavigation(page, !locked && !state.loading && !state.busy, true) { model.flushDraft(); rehearsal = false; page = it }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
            when (page) {
                0 -> Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (!cameraAllowed) CameraPermissionPanel({ permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) },
                        { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) },
                        { settings = true })
                    if (cameraAllowed) StudioOverlay(
                        state, capture, scroll, landscape, locked, rehearsal,
                        onEdit = { editor = true }, onSettings = { settings = true }, onSpeed = { quickSpeed = true },
                        onRehearse = { if (scroll.value >= scroll.maxValue) scope.launch { scroll.scrollTo(0) }; rehearsal = !rehearsal },
                        onRecord = { if (audioAllowed) beginCountdown() else silentConfirm = true },
                        onSwap = { front = !front; rehearsal = false },
                        onStop = model.camera::stop,
                    )
                    if (countdown > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(countdown.toString(), fontSize = 112.sp, fontWeight = FontWeight.Light); Text("Respire. Encontre seu ritmo.", color = Muted)
                            TextButton(onClick = { countdown = 0 }) { Text("Cancelar", color = Color.White) }
                        }
                    }
                }
                1 -> ScriptPage(state.library.draft, model::editDraft, { model.flushDraft(); page = 0 },
                    if (state.session != null && !state.requiresLogin) model::uploadDraft else null, state.busy)
                2 -> takesPageState.SaveableStateProvider("takes:${state.ownerId ?: "visitor"}") {
                    TakesPage(state.takes, { playingTakeId = it.id }, { chosenTakeId = it.id }, { share(File(it.path)) }, { deletingTake = it },
                        onOpenStudio = { page = 0 }, onRename = { renamingTakeId = it.id })
                }
                3 -> AccountPage(state, model)
            }
            }
        }
    }
    }
    if (!state.loading && editor) ScriptEditor(state.library.draft, model::editDraft,
        onClose = { model.flushDraft(); editor = false }, onRecord = { model.flushDraft(); editor = false; page = 0 },
        onUpload = if (state.session != null && !state.requiresLogin) model::uploadDraft else null, busy = state.busy)
    if (!state.loading && settings) PromptSettingsSheet(preferences, model::setSettings, { settings = false })
    if (!state.loading && quickSpeed && !locked && page == 0) QuickSpeedSheet(preferences.speed,
        { model.setSettings(preferences.copy(speed = it)) }, { quickSpeed = false })
    if (silentConfirm) AlertDialog(onDismissRequest = { silentConfirm = false }, title = { Text("Gravar sem áudio?") },
        text = { Text("O microfone está desativado. Permita o acesso ou continue com um vídeo sem som.") },
        confirmButton = { TextButton(onClick = { silentConfirm = false; beginCountdown() }) { Text("Gravar sem áudio") } },
        dismissButton = { TextButton(onClick = { silentConfirm = false; permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }) { Text("Permitir microfone") } })
    if (stopConfirm) AlertDialog(onDismissRequest = { stopConfirm = false }, title = { Text(if (export.running) "Cancelar exportação?" else "Encerrar gravação?") },
        text = { Text(if (export.running) "O take original continuará salvo." else "O take será salvo no aparelho.") },
        confirmButton = { TextButton(onClick = { if (export.running) model.exporter.cancel() else model.camera.stop(); stopConfirm = false }) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text("Continuar") } })
    deletingTake?.let { take -> AlertDialog(onDismissRequest = { deletingTake = null }, title = { Text("Excluir take?") }, text = { Text("Esta gravação será removida do aparelho. Arquivos já exportados permanecem no destino escolhido.") },
        confirmButton = { TextButton(onClick = { model.deleteTake(take); deletingTake = null }) { Text("Excluir") } }, dismissButton = { TextButton(onClick = { deletingTake = null }) { Text("Cancelar") } }) }
    playingTake?.let { VideoPlayerDialog(it, { playingTakeId = null }) }
    renamingTake?.let { take -> RenameTakeDialog(take, state.busy,
        onRename = { model.renameTake(take.id, it) }, onClose = { renamingTakeId = null }) }
    state.legalDocument?.let { LegalDialog(it, state.busy, model::confirmLegal, model::dismissLegal) }
    state.cloudScripts?.let { CloudScriptsDialog(it, { model.openScript(it, cloudCopy = true); editor = true }, model::dismissCloud) }
}

/** Fade in place, retaining every measurement so the camera and prompt never move. */
@Composable
internal fun StudioChrome(
    visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit,
) {
    val opacity by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "studioControlsOpacity")
    Box(modifier.graphicsLayer { alpha = opacity }
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})) { content() }
}

@Composable
private fun CameraPreview(controller: NativeCameraController, front: Boolean, mirror: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val latestMirror by rememberUpdatedState(mirror)
    LaunchedEffect(owner, front) {
        controller.bind(owner, view, front)
        view.scaleX = if (front && !latestMirror) -1f else 1f
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged {
        view.post { view.display?.let { controller.updateRotation(it.rotation) } }
    }, update = { it.scaleX = if (front && !mirror) -1f else 1f })
    DisposableEffect(controller) { onDispose { controller.unbind() } }
}

@Composable
private fun CameraPermissionPanel(onRequest: () -> Unit, onPermissions: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(30.dp), verticalArrangement = Arrangement.Center) {
        StudioGlyph(0, Red, Modifier.size(54.dp)); Spacer(Modifier.height(26.dp))
        Text("Encontre seu ritmo.\nGrave sua ideia.", fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        Text("Seu roteiro junto à câmera. Sua voz no vídeo.", Modifier.padding(top = 18.dp, bottom = 30.dp), color = Muted, fontSize = 16.sp)
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Ativar câmera e microfone") }
        TextButton(onClick = onPermissions, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Abrir permissões do aparelho", color = Muted) }
        TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Ajustes", color = Muted) }
        Text("Você também pode preparar roteiros sem usar a câmera.", Modifier.padding(top = 24.dp), color = Muted, fontSize = 12.sp)
    }
}

@Composable
internal fun StudioGlyph(type: Int, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val scale = size.width / 24f
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(color, Offset(a * scale, b * scale), Offset(c * scale, d * scale), 1.7f * scale, cap = StrokeCap.Round)
        when (type) {
            0 -> { line(3f, 8f, 3f, 3f); line(3f, 3f, 8f, 3f); line(16f, 3f, 21f, 3f); line(21f, 3f, 21f, 8f); line(3f, 16f, 3f, 21f); line(3f, 21f, 8f, 21f); line(16f, 21f, 21f, 21f); line(21f, 16f, 21f, 21f); line(7f, 9f, 17f, 9f); line(7f, 12f, 15f, 12f); line(7f, 15f, 17f, 15f) }
            1 -> { drawRoundRect(color, Offset(5 * scale, 2 * scale), Size(14 * scale, 20 * scale), androidx.compose.ui.geometry.CornerRadius(2 * scale), style = Stroke(1.6f * scale)); line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f) }
            2 -> { drawRoundRect(color, Offset(2 * scale, 5 * scale), Size(20 * scale, 15 * scale), androidx.compose.ui.geometry.CornerRadius(2 * scale), style = Stroke(1.6f * scale)); val path = Path().apply { moveTo(10 * scale, 9 * scale); lineTo(16 * scale, 12.5f * scale); lineTo(10 * scale, 16 * scale); close() }; drawPath(path, color) }
            else -> { drawCircle(color, 4 * scale, Offset(12 * scale, 7 * scale), style = Stroke(1.6f * scale)); drawArc(color, 180f, 180f, false, Offset(4 * scale, 14 * scale), Size(16 * scale, 13 * scale), style = Stroke(1.6f * scale)) }
        }
    }
}

@Composable
private fun StudioOverlay(
    state: StudioState, capture: CaptureState, scroll: ScrollState, landscape: Boolean,
    locked: Boolean, rehearsal: Boolean,
    onEdit: () -> Unit, onSettings: () -> Unit, onSpeed: () -> Unit, onRehearse: () -> Unit, onRecord: () -> Unit,
    onSwap: () -> Unit, onStop: () -> Unit,
) {
    val recording = capture.status != CaptureStatus.IDLE
    val preferences = state.library.settings
    val controlsOpacity by animateFloatAsState(if (recording) 0f else 1f, tween(220), label = "studioPanelOpacity")
    val panel: @Composable (Modifier) -> Unit = { modifier ->
        StudioCapturePanel(landscape, recording, controlsOpacity, rehearsal, preferences.speed.toInt(),
            editingEnabled = !locked && !state.busy,
            rehearsalEnabled = !locked && state.library.draft.content.isNotBlank(),
            swapEnabled = !locked,
            recordEnabled = if (recording) capture.status != CaptureStatus.STOPPING else capture.ready && !state.busy && !locked,
            onEdit = onEdit, onSettings = onSettings, onRehearse = onRehearse, onSwap = onSwap,
            onRecord = if (recording) onStop else onRecord, modifier = modifier, onSpeed = onSpeed)
    }
    // Only overlays change their arrangement. The CameraPreview stays bound during rotation.
    if (landscape) Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ReadingPrompt(state.library.draft, preferences, scroll, Modifier.weight(1f), showPlaceholder = !recording)
            StudioChrome(visible = !recording) {
                StudioScriptToolbar(preferences.speed.toInt(), !locked && !state.busy, onEdit, onSettings,
                    Modifier.background(Navy.copy(alpha = .94f)), onSpeed = onSpeed)
            }
        }
        panel(Modifier.width(132.dp).fillMaxHeight())
    } else Column(Modifier.fillMaxSize()) {
        ReadingPrompt(state.library.draft, preferences, scroll, Modifier.weight(1f), showPlaceholder = !recording)
        panel(Modifier.fillMaxWidth())
    }
}

internal fun elapsed(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60)
}
