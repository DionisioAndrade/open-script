package com.example.open_script

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.open_script.data.RecordedTake
import com.example.open_script.media.ExportProfile
import com.example.open_script.media.ExportProfiles
import com.example.open_script.media.NativeExporter
import com.example.open_script.media.SoftwareDecoders
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerDialog(take: RecordedTake, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var software by rememberSaveable(take.path) { mutableStateOf(false) }
    var resumePosition by rememberSaveable(take.path) { mutableStateOf(0L) }
    val player = remember(context, take.path, lifecycleOwner, software) {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (software) renderers.setMediaCodecSelector(SoftwareDecoders.selector)
        ExoPlayer.Builder(context, renderers).build()
    }
    var playbackError by remember(take.path) { mutableStateOf<String?>(null) }
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!software && error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED)) {
                    resumePosition = player.currentPosition
                    software = true
                } else playbackError = "Não foi possível reproduzir este vídeo. O arquivo original continua salvo no aparelho."
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                resumePosition = player.currentPosition
                player.pause()
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(take.path))))
        player.seekTo(resumePosition)
        player.prepare()
        player.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    val availableHeight = exportDialogHeight()
    ExportModal(onClose = onClose) {
        Text("Assistir gravação", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(take.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(takeDescription(take), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    contentDescription = "Reprodução de ${take.title}"
                }
            },
            modifier = Modifier.fillMaxWidth().height((availableHeight * 0.54f).coerceIn(96.dp, 460.dp)).background(Color.Black),
            onRelease = { it.player = null },
            update = { it.player = player },
        )
        playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("Fechar") }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ExportDialog(
    take: RecordedTake,
    exporter: NativeExporter,
    onSave: (File) -> Unit,
    onShare: (File) -> Unit,
    onClose: () -> Unit,
    saving: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val exportState by exporter.state.collectAsStateWithLifecycle()
    val active = exportState.running || saving
    val availableHeight = exportDialogHeight()
    var loadAttempt by remember { mutableStateOf(0) }
    val loadedProfiles by produceState<Result<List<ExportProfile>>?>(null, context, loadAttempt) {
        value = withContext(Dispatchers.IO) { runCatching { ExportProfiles.load(context) } }
    }
    val profiles = loadedProfiles?.getOrNull().orEmpty()
    var selectedId by rememberSaveable(take.id) { mutableStateOf("custom") }
    var aspect by rememberSaveable(take.id) { mutableStateOf("9:16") }
    var resolution by rememberSaveable(take.id) { mutableStateOf(1080) }
    var quality by rememberSaveable(take.id) { mutableStateOf("balanced") }
    var frameRate by rememberSaveable(take.id) { mutableStateOf(30) }
    var profileMenu by remember { mutableStateOf(false) }
    val preset = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    val selectedProfile = if (preset == null || preset.id == "custom") {
        (preset ?: ExportProfile("custom", "Personalizado")).copy(
            aspect = aspect, resolution = resolution, quality = quality, frameRate = frameRate,
        )
    } else preset
    val original = remember(take.path) { File(take.path) }
    val originalAvailable = original.isFile && original.length() > 0
    fun changeSelection(change: () -> Unit) {
        if (!active) {
            // A result belongs to the choices used to create it; never offer a stale result for new choices.
            exporter.clear()
            change()
        }
    }

    ExportModal(onClose = onClose, locked = active) {
        Text("Salvar e compartilhar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(take.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(takeDescription(take), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Original", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Mantém a qualidade da gravação. O vídeo permanece salvo no aparelho.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onSave(original) }, enabled = originalAvailable && !active, modifier = Modifier.fillMaxWidth()) {
            Text("Salvar original")
        }
        OutlinedButton(onClick = { onShare(original) }, enabled = originalAvailable && !active, modifier = Modifier.fillMaxWidth()) {
            Text("Compartilhar original")
        }
        if (!originalAvailable) Text("O arquivo original não está disponível neste aparelho.", color = MaterialTheme.colorScheme.error)

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Preparar uma cópia em MP4", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Ajuste o formato para publicar. O enquadramento completo e a duração são preservados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            loadedProfiles == null -> {
                CircularProgressIndicator()
                Text("Carregando formatos…", style = MaterialTheme.typography.bodySmall)
            }
            loadedProfiles?.isFailure == true || profiles.isEmpty() -> {
                Text("Não foi possível carregar os formatos de exportação. Você ainda pode salvar ou compartilhar o original.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { loadAttempt++ }, enabled = !active) { Text("Tentar carregar formatos") }
            }
            else -> {
                Text("Perfil de exportação", style = MaterialTheme.typography.labelLarge)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { profileMenu = true },
                        enabled = !active,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(selectedProfile.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(" ▾")
                        }
                    }
                    DropdownMenu(expanded = profileMenu && !active, onDismissRequest = { profileMenu = false },
                        modifier = Modifier.heightIn(max = (availableHeight * 0.72f).coerceAtMost(360.dp))) {
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.label) },
                                onClick = {
                                    profileMenu = false
                                    if (selectedId != profile.id) changeSelection { selectedId = profile.id }
                                },
                            )
                        }
                    }
                }
                if (selectedProfile.note.isNotBlank()) Text(selectedProfile.note, style = MaterialTheme.typography.bodySmall)
                if (selectedProfile.id == "custom") {
                    ExportChoiceRow("Proporção", ExportProfiles.aspects.map { it to it }, aspect, !active) { value ->
                        changeSelection { aspect = value }
                    }
                    ExportChoiceRow("Resolução", ExportProfiles.resolutions.map { it to "${it}p" }, resolution, !active) { value ->
                        changeSelection { resolution = value }
                    }
                    ExportChoiceRow("Qualidade", listOf("compact" to "Compacta", "balanced" to "Equilibrada", "high" to "Alta"), quality, !active) { value ->
                        changeSelection { quality = value }
                    }
                    ExportChoiceRow("Quadros por segundo", listOf(24 to "24 FPS", 30 to "30 FPS"), frameRate, !active) { value ->
                        changeSelection { frameRate = value }
                    }
                }
                val settings = remember(selectedProfile, take.width, take.height) {
                    runCatching { ExportProfiles.settings(selectedProfile, take.width, take.height) }.getOrNull()
                }
                settings?.let {
                    Text(
                        "Exportação prevista: ${it.dimensions.width} × ${it.dimensions.height} · até ${it.frameRate} FPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (it.limitedBySource) Text(
                        "A resolução será limitada pela gravação original para manter a imagem sem ampliação.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectedProfile.advice.isNotBlank()) {
                    Text(selectedProfile.advice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!active) {
                    Button(
                        onClick = { exporter.export(original, selectedProfile) { } },
                        enabled = originalAvailable && settings != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (exportState.output != null) "Processar novamente" else "Processar MP4") }
                }
            }
        }
        if (exportState.running) {
            LinearProgressIndicator(progress = { exportState.progress }, modifier = Modifier.fillMaxWidth())
            Text("Processando MP4 · ${(exportState.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Text("Mantenha o aplicativo aberto até terminar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { exporter.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancelar processamento") }
        }
        if (saving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Salvando no destino escolhido…", style = MaterialTheme.typography.bodyMedium)
        }
        exportState.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        exportState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        exportState.output?.takeIf { !exportState.running && it.isFile }?.let { output ->
            Text("Cópia pronta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = { onSave(output) }, enabled = !active, modifier = Modifier.fillMaxWidth()) { Text("Salvar MP4 preparado") }
            OutlinedButton(onClick = { onShare(output) }, enabled = !active, modifier = Modifier.fillMaxWidth()) { Text("Compartilhar MP4 preparado") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose, enabled = !active) { Text("Fechar") }
        }
    }
}

@Composable
private fun <T> ExportChoiceRow(
    label: String,
    choices: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, title) ->
                FilterChip(selected = selected == value, onClick = { if (selected != value) onSelected(value) }, enabled = enabled, label = { Text(title) })
            }
        }
    }
}

@Composable
private fun ExportModal(onClose: () -> Unit, locked: Boolean = false, content: @Composable () -> Unit) {
    val maxHeight = exportDialogHeight()
    Dialog(
        onDismissRequest = { if (!locked) onClose() },
        properties = DialogProperties(
            dismissOnBackPress = !locked,
            dismissOnClickOutside = !locked,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 620.dp).fillMaxWidth().heightIn(max = maxHeight),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun exportDialogHeight(): Dp {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    return (with(density) { window.height.toDp() } - insets.calculateTopPadding() -
        insets.calculateBottomPadding() - 16.dp).coerceAtLeast(120.dp)
}

private fun takeDescription(take: RecordedTake): String {
    val seconds = (take.durationMs / 1000).coerceAtLeast(0)
    val duration = if (seconds >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    return "$duration · ${take.width} × ${take.height}"
}
