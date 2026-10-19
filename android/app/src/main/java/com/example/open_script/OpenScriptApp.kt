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
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
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
    var savePath by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val scroll = rememberScrollState()
    var requested by rememberSaveable { mutableStateOf(false) }
    fun permitted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var cameraAllowed by remember { mutableStateOf(permitted(Manifest.permission.CAMERA)) }
    var audioAllowed by remember { mutableStateOf(permitted(Manifest.permission.RECORD_AUDIO)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraAllowed = permitted(Manifest.permission.CAMERA)
        audioAllowed = permitted(Manifest.permission.RECORD_AUDIO)
    }
    val fileDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        savePath?.let { path -> if (uri != null) model.copyVideo(File(path), uri) }
        savePath = null
    }
    fun save(file: File, title: String) {
        savePath = file.absolutePath
        fileDestination.launch(title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(80).ifBlank { "OpenScript" } + ".mp4")
    }
    fun share(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Compartilhar vídeo"))
        }.onFailure { model.notify("Não foi possível compartilhar. Tente salvar uma cópia.") }
    }
    val recording = capture.status != CaptureStatus.IDLE
    val locked = recording || state.preparing || countdown > 0 || export.running
    val promptRunning = rehearsal || capture.status == CaptureStatus.RECORDING
    val density = LocalDensity.current.density
    val preferences = state.library.settings

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

    Scaffold(containerColor = Navy, snackbarHost = { SnackbarHost(snackbars) },
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!landscape) StudioNavigation(page, !locked && !state.loading && !state.busy, false) { rehearsal = false; page = it }
        },
    ) { padding ->
        if (state.loading) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (landscape) StudioNavigation(page, !locked && !state.loading && !state.busy, true) { rehearsal = false; page = it }
            Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (landscape) 0.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    StudioGlyph(0, Red, Modifier.size(27.dp)); Spacer(Modifier.width(8.dp))
                    Text("open", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                    Text("script", fontSize = 24.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
                }
                TextButton(onClick = { settings = true }, enabled = !locked && !state.busy) { Text("Ajustes", color = Muted) }
            }
            when (page) {
                0 -> Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (cameraAllowed) CameraPreview(model.camera, front, preferences.mirror)
                    else CameraPermissionPanel({ permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) },
                        { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) })
                    if (cameraAllowed) StudioOverlay(
                        state, capture, scroll, landscape, locked, rehearsal, audioAllowed,
                        onEdit = { editor = true }, onSettings = { settings = true },
                        onRehearse = { if (scroll.value >= scroll.maxValue) scope.launch { scroll.scrollTo(0) }; rehearsal = !rehearsal },
                        onRecord = { if (audioAllowed) beginCountdown() else silentConfirm = true },
                        onSwap = { front = !front; rehearsal = false },
                        onPauseResume = { if (capture.status == CaptureStatus.PAUSED) model.camera.resume() else model.camera.pause() },
                        onStop = model.camera::stop,
                    )
                    if (countdown > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(countdown.toString(), fontSize = 112.sp, fontWeight = FontWeight.Light); Text("Respire. Encontre seu ritmo.", color = Muted)
                            TextButton(onClick = { countdown = 0 }) { Text("Cancelar", color = Color.White) }
                        }
                    }
                }
                1 -> ScriptsPage(state, { model.newDraft(); editor = true }, { model.openScript(it); editor = true }, model::deleteScript, model::fetchScripts)
                2 -> TakesPage(state.takes, { playingTakeId = it.id }, { chosenTakeId = it.id }, { share(File(it.path)) }, { deletingTake = it })
                3 -> AccountPage(state, model)
            }
            }
        }
    }
    if (editor) ScriptEditor(state.library.draft, model::editDraft, { model.saveDraft(); editor = false }, { editor = false }, { editor = false; page = 0 },
        if (state.session != null && !state.requiresLogin) model::uploadDraft else null, state.busy)
    if (settings) PromptSettingsSheet(preferences, model::setSettings, { settings = false })
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
    chosenTake?.let { take -> ExportDialog(take, model.exporter, { save(it, take.title) }, ::share, { if (!export.running && !state.busy) { chosenTakeId = null; model.exporter.clear() } }, saving = state.busy) }
    state.legalDocument?.let { LegalDialog(it, state.busy, model::confirmLegal, model::dismissLegal) }
    state.cloudScripts?.let { CloudScriptsDialog(it, { model.openScript(it, cloudCopy = true); editor = true }, model::dismissCloud) }
}

@Composable
private fun CameraPreview(controller: NativeCameraController, front: Boolean, mirror: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val latestMirror by rememberUpdatedState(mirror)
    LaunchedEffect(owner, front) {
        controller.bind(owner, view, front)
        view.scaleX = if (front && !latestMirror) -1f else 1f
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().onSizeChanged {
        view.post { view.display?.let { controller.updateRotation(it.rotation) } }
    }, update = { it.scaleX = if (front && !mirror) -1f else 1f })
    DisposableEffect(controller) { onDispose { controller.unbind() } }
}

@Composable
private fun CameraPermissionPanel(onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(30.dp), verticalArrangement = Arrangement.Center) {
        StudioGlyph(0, Red, Modifier.size(54.dp)); Spacer(Modifier.height(26.dp))
        Text("Encontre seu ritmo.\nGrave sua ideia.", fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        Text("Seu roteiro junto à câmera. Sua voz no vídeo.", Modifier.padding(top = 18.dp, bottom = 30.dp), color = Muted, fontSize = 16.sp)
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Ativar câmera e microfone") }
        TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Abrir permissões do aparelho", color = Muted) }
        Text("Você também pode preparar roteiros sem usar a câmera.", Modifier.padding(top = 24.dp), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun RecordControl(enabled: Boolean, stop: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = if (enabled) .95f else .35f), CircleShape)
            .clickable(enabled = enabled, onClickLabel = if (stop) "Encerrar gravação" else "Gravar vídeo", onClick = onClick)
            .semantics { contentDescription = if (stop) "Encerrar gravação" else "Gravar vídeo" }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (stop) 28.dp else 60.dp).background(Red.copy(alpha = if (enabled) 1f else .35f), if (stop) RoundedCornerShape(6.dp) else CircleShape))
        }
        Text(if (stop) "Encerrar" else "Gravar", Modifier.padding(top = 6.dp), fontSize = 10.sp, color = Muted)
    }
}

@Composable
internal fun StudioGlyph(type: Int, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val scale = size.width / 24f
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(color, Offset(a * scale, b * scale), Offset(c * scale, d * scale), 1.7f * scale)
        when (type) {
            0 -> { line(3f, 8f, 3f, 3f); line(3f, 3f, 8f, 3f); line(16f, 3f, 21f, 3f); line(21f, 3f, 21f, 8f); line(3f, 16f, 3f, 21f); line(3f, 21f, 8f, 21f); line(16f, 21f, 21f, 21f); line(21f, 16f, 21f, 21f); line(7f, 9f, 17f, 9f); line(7f, 12f, 15f, 12f); line(7f, 15f, 17f, 15f) }
            1 -> { drawRoundRect(color, Offset(5 * scale, 2 * scale), Size(14 * scale, 20 * scale), androidx.compose.ui.geometry.CornerRadius(2 * scale), style = Stroke(1.6f * scale)); line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f) }
            2 -> { drawRoundRect(color, Offset(2 * scale, 5 * scale), Size(20 * scale, 15 * scale), androidx.compose.ui.geometry.CornerRadius(2 * scale), style = Stroke(1.6f * scale)); val path = Path().apply { moveTo(10 * scale, 9 * scale); lineTo(16 * scale, 12.5f * scale); lineTo(10 * scale, 16 * scale); close() }; drawPath(path, color) }
            else -> { drawCircle(color, 4 * scale, Offset(12 * scale, 7 * scale), style = Stroke(1.6f * scale)); drawArc(color, 180f, 180f, false, Offset(4 * scale, 14 * scale), Size(16 * scale, 13 * scale), style = Stroke(1.6f * scale)) }
        }
    }
}

@Composable
private fun StudioNavigation(page: Int, enabled: Boolean, landscape: Boolean, onSelect: (Int) -> Unit) {
    val items = listOf("Estúdio", "Roteiros", "Takes", "Conta")
    if (landscape) NavigationRail(containerColor = Navy, modifier = Modifier.fillMaxHeight().width(76.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
        Spacer(Modifier.weight(1f))
        items.forEachIndexed { index, title ->
            NavigationRailItem(selected = page == index, onClick = { onSelect(index) }, enabled = enabled,
                modifier = Modifier.height(66.dp), icon = { StudioGlyph(index, if (page == index) Red else Muted) },
                label = { Text(title, fontSize = 10.sp) },
                colors = NavigationRailItemDefaults.colors(indicatorColor = Color(0xFF252E44), selectedTextColor = Color.White, unselectedTextColor = Muted))
        }
        Spacer(Modifier.weight(1f))
    } else NavigationBar(containerColor = Navy, tonalElevation = 0.dp) {
        items.forEachIndexed { index, title ->
            NavigationBarItem(selected = page == index, onClick = { onSelect(index) }, enabled = enabled,
                icon = { StudioGlyph(index, if (page == index) Red else Muted) }, label = { Text(title, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF252E44), selectedTextColor = Color.White, unselectedTextColor = Muted))
        }
    }
}

@Composable
private fun StudioOverlay(
    state: StudioState, capture: CaptureState, scroll: ScrollState, landscape: Boolean,
    locked: Boolean, rehearsal: Boolean, audioAllowed: Boolean,
    onEdit: () -> Unit, onSettings: () -> Unit, onRehearse: () -> Unit, onRecord: () -> Unit,
    onSwap: () -> Unit, onPauseResume: () -> Unit, onStop: () -> Unit,
) {
    val recording = capture.status != CaptureStatus.IDLE
    val preferences = state.library.settings
    val indicators: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(if (landscape) 10.dp else 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Surface(color = Navy.copy(alpha = .76f), shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (recording) Red else Color(0xFFA7C9A8), CircleShape)); Spacer(Modifier.width(7.dp))
                    Text(if (recording) elapsed(capture.durationMs) else if (capture.ready) "Câmera pronta" else "Abrindo câmera…", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            Surface(color = Navy.copy(alpha = .76f), shape = RoundedCornerShape(24.dp)) {
                Text(if (audioAllowed) "Vídeo + áudio" else "Sem áudio", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 11.sp,
                    color = if (audioAllowed) Color.White else Color(0xFFFFD19A))
            }
        }
    }
    val editing: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().background(Navy.copy(alpha = .8f)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEdit, enabled = !locked && !state.busy) { Text("Editar roteiro", color = Color.White) }
            Spacer(Modifier.weight(1f)); Text("${preferences.speed.toInt()} px/s", fontSize = 11.sp, color = Muted)
            TextButton(onClick = onSettings, enabled = !locked && !state.busy) { Text("Velocidade", color = Muted) }
        }
    }
    val controls: @Composable () -> Unit = {
        if (!recording) {
            TextButton(onClick = onRehearse, enabled = !locked && state.library.draft.content.isNotBlank()) {
                Text(if (rehearsal) "Pausar leitura" else "Ensaiar", color = Color.White, fontSize = 12.sp)
            }
            RecordControl(capture.ready && !state.busy && !locked, onClick = onRecord)
            TextButton(onClick = onSwap, enabled = !locked) { Text(if (landscape) "Trocar câmera" else "Trocar\ncâmera", color = Color.White, fontSize = 12.sp) }
        } else {
            TextButton(onClick = onPauseResume, enabled = capture.status in listOf(CaptureStatus.RECORDING, CaptureStatus.PAUSED)) {
                Text(if (capture.status == CaptureStatus.PAUSED) "Retomar" else "Pausar", color = Color.White)
            }
            RecordControl(capture.status != CaptureStatus.STOPPING, stop = true, onClick = onStop)
            Text("${if (capture.status == CaptureStatus.PAUSED) "Em pausa" else "Gravando"}\n${elapsed(capture.durationMs)}", fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Monospace)
        }
    }
    // Only overlays change their arrangement. The CameraPreview stays bound during rotation.
    if (landscape) Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            indicators()
            PromptText(state.library.draft, preferences, scroll, Modifier.weight(1f))
            editing()
        }
        Column(Modifier.width(140.dp).fillMaxHeight().background(Navy.copy(alpha = .94f)).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) { controls() }
    } else Column(Modifier.fillMaxSize()) {
        indicators()
        PromptText(state.library.draft, preferences, scroll, Modifier.weight(1f))
        editing()
        Row(Modifier.fillMaxWidth().background(Navy.copy(alpha = .94f)).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) { controls() }
    }
}

@Composable
private fun PromptText(draft: ScriptDocument, preferences: PromptSettings, scroll: ScrollState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        val readingHeight = maxHeight
        if (preferences.showPrompt) {
            val fontSize = if (preferences.autoSize) minOf(preferences.size, maxOf(24f, maxWidth.value / 9f)) else preferences.size
            val font = when (preferences.font) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.SansSerif }
            val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .8f), Offset(0f, 2f), 6f))
            Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = preferences.opacity / 100f), RoundedCornerShape(12.dp)).verticalScroll(scroll).padding(10.dp)) {
                Spacer(Modifier.height(readingHeight * .20f))
                if (draft.content.isBlank()) {
                    Text("Sua próxima ideia\ncomeça aqui.", fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .9f), style = shadow)
                    Text("Toque em Editar roteiro para preparar seu vídeo.", Modifier.padding(top = 16.dp), color = Muted, fontSize = 15.sp, style = shadow)
                } else Text(draft.content, fontSize = fontSize.sp, lineHeight = (fontSize * 1.45f).sp, fontFamily = font,
                    color = runCatching { Color(android.graphics.Color.parseColor(preferences.color)) }.getOrDefault(Color.White), style = shadow)
                Spacer(Modifier.height(readingHeight * .65f))
            }
        }
    }
}

internal fun elapsed(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60)
}
