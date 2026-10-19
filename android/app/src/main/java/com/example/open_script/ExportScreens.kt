package com.example.open_script

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import com.example.open_script.ui.theme.Open_scriptTheme
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
fun ExportScreen(
    take: RecordedTake,
    exporter: NativeExporter,
    onSave: (File) -> Unit,
    onShare: (File) -> Unit,
    onClose: () -> Unit,
    saving: Boolean = false,
    snackbarHost: @Composable () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val exportState by exporter.state.collectAsStateWithLifecycle()
    val active = exportState.running || saving
    var loadAttempt by remember { mutableStateOf(0) }
    val loadedProfiles by produceState<Result<List<ExportProfile>>?>(null, context, loadAttempt) {
        value = withContext(Dispatchers.IO) { runCatching { ExportProfiles.load(context) } }
    }
    val profiles = loadedProfiles?.getOrNull().orEmpty()
    var selectedId by rememberSaveable(take.id) { mutableStateOf("") }
    var aspect by rememberSaveable(take.id) { mutableStateOf(if (take.width > take.height) "16:9" else if (take.width == take.height) "1:1" else "9:16") }
    var resolution by rememberSaveable(take.id) { mutableStateOf(1080) }
    var quality by rememberSaveable(take.id) { mutableStateOf("balanced") }
    var frameRate by rememberSaveable(take.id) { mutableStateOf(30) }
    var fileFormat by rememberSaveable(take.id) { mutableStateOf("mp4") }
    var cancelConfirm by remember { mutableStateOf(false) }
    val preset = profiles.firstOrNull { it.id == selectedId }
    val selectedProfile = if (preset?.id == "custom") {
        preset.copy(
            label = "Personalizada", aspect = aspect, resolution = resolution, quality = quality, frameRate = frameRate, format = fileFormat,
        )
    } else preset
    val settings = remember(selectedProfile, take.width, take.height) {
        selectedProfile?.let { runCatching { ExportProfiles.settings(it, take.width, take.height) }.getOrNull() }
    }
    val original = remember(take.path) { File(take.path) }
    val originalAvailable = original.isFile && original.length() > 0
    fun changeSelection(change: () -> Unit) {
        if (!active) {
            // A result belongs to the choices used to create it; never offer a stale result for new choices.
            exporter.clear()
            change()
        }
    }
    fun goBack() {
        when {
            exportState.running -> cancelConfirm = true
            saving -> Unit
            selectedId.isNotEmpty() -> changeSelection { selectedId = "" }
            else -> onClose()
        }
    }
    BackHandler { goBack() }

    ExportPageFrame(
        title = selectedProfile?.label ?: "Exportar vídeo",
        backLabel = if (selectedId.isEmpty()) "Voltar aos takes" else "Voltar aos destinos",
        onBack = ::goBack,
        snackbarHost = snackbarHost,
        contentKey = selectedId,
        footer = {
            val output = exportState.output?.takeIf { !exportState.running && it.isFile }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectedProfile != null) {
                    settings?.let {
                        ExportSummary("${it.dimensions.width} × ${it.dimensions.height} · até ${it.frameRate} FPS", it.format.label)
                    }
                }
                when {
                    saving -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Salvando no destino escolhido…", style = MaterialTheme.typography.bodySmall)
                    }
                    exportState.running -> {
                        LinearProgressIndicator(progress = { exportState.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Exportando vídeo · ${(exportState.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { exporter.cancel() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Cancelar exportação")
                        }
                    }
                    output != null -> {
                        Text("Cópia pronta", style = MaterialTheme.typography.labelLarge)
                        ExportFileActions("Salvar vídeo", "Compartilhar vídeo", true, { onSave(output) }, { onShare(output) })
                    }
                    selectedProfile != null -> {
                        exportState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = { exporter.export(original, selectedProfile) { } }, enabled = originalAvailable && settings != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("Exportar vídeo") }
                    }
                    else -> ExportFileActions("Salvar original", "Compartilhar original", originalAvailable && !active,
                        { onSave(original) }, { onShare(original) }, originalActions = true)
                }
            }
        },
    ) {
        ExportTakeHeading(take)
        if (!originalAvailable) Text("O arquivo original não está disponível neste aparelho.", color = MaterialTheme.colorScheme.error)
        if (selectedProfile == null) {
            when {
                loadedProfiles == null -> {
                    CircularProgressIndicator()
                    Text("Carregando destinos…", style = MaterialTheme.typography.bodySmall)
                }
                loadedProfiles?.isFailure == true || profiles.isEmpty() -> {
                    Text("Não foi possível carregar os destinos. Você ainda pode salvar ou compartilhar o original.", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { loadAttempt++ }, enabled = !active) { Text("Tentar novamente") }
                }
                else -> ExportDestinations(profiles, !active) { profile -> changeSelection { selectedId = profile.id } }
            }
        } else {
            if (selectedProfile.id == "custom") {
                ExportCustomOptions(selectedProfile, !active) { updated ->
                    changeSelection {
                        aspect = updated.aspect
                        resolution = updated.resolution
                        quality = updated.quality
                        frameRate = updated.frameRate
                        fileFormat = updated.format
                    }
                }
            } else if (selectedProfile.note.isNotBlank()) Text(selectedProfile.note, style = MaterialTheme.typography.bodyMedium)
            if (settings?.limitedBySource == true) Text("A resolução será limitada pela gravação original para manter a imagem sem ampliação.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("O vídeo será exportado inteiro, mantendo o enquadramento completo. O original continua salvo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selectedProfile.advice.isNotBlank()) Text(selectedProfile.advice,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (exportState.running) {
            Text("Mantenha o aplicativo aberto até terminar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        exportState.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (cancelConfirm) AlertDialog(
        onDismissRequest = { cancelConfirm = false }, title = { Text("Cancelar exportação?") },
        text = { Text("O take original continuará salvo no aparelho.") },
        confirmButton = { TextButton(onClick = { exporter.cancel(); cancelConfirm = false }) { Text("Cancelar exportação") } },
        dismissButton = { TextButton(onClick = { cancelConfirm = false }) { Text("Continuar exportando") } },
    )
}

/** A route in the same Activity: only its body scrolls; the summary and actions stay visible. */
@Composable
private fun ExportPageFrame(
    title: String, backLabel: String, onBack: () -> Unit,
    snackbarHost: @Composable () -> Unit = {}, contentKey: Any? = null,
    footer: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(contentKey) { scroll.scrollTo(0) }
    Scaffold(
        modifier = Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing, snackbarHost = snackbarHost,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                    contentAlignment = Alignment.Center) {
                    Row(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                            val color = MaterialTheme.colorScheme.onSurface
                            Canvas(Modifier.height(24.dp).fillMaxWidth(.5f)) {
                                val y = size.height / 2
                                drawLine(color, Offset(size.width * .8f, y), Offset(size.width * .2f, y), 2.dp.toPx(), StrokeCap.Round)
                                drawLine(color, Offset(size.width * .2f, y), Offset(size.width * .5f, y - 6.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                                drawLine(color, Offset(size.width * .2f, y), Offset(size.width * .5f, y + 6.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                            }
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
                    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                        contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) { footer() }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        }
    }
}

@Composable
private fun ExportTakeHeading(take: RecordedTake) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(takeDisplayTitle(take), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(takeDescription(take), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExportSummary(dimensions: String, format: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(dimensions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(format, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExportFileActions(
    saveLabel: String, shareLabel: String, enabled: Boolean, onSave: () -> Unit, onShare: () -> Unit,
    originalActions: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        val save: @Composable (Modifier) -> Unit = { modifier ->
            if (originalActions) OutlinedButton(onClick = onSave, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) { Text(saveLabel) }
            else Button(onClick = onSave, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) { Text(saveLabel) }
        }
        val share: @Composable (Modifier) -> Unit = { modifier ->
            TextButton(onClick = onShare, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) { Text(shareLabel) }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { save(Modifier.fillMaxWidth()); share(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { save(Modifier.weight(1f)); share(Modifier.weight(1f)) }
    }
}

@Composable
private fun ExportCustomOptions(profile: ExportProfile, enabled: Boolean, onChange: (ExportProfile) -> Unit) {
    ExportChoiceRow("Formato do arquivo", ExportProfiles.formats.map { it.id to it.label }, profile.format, enabled) {
        onChange(profile.copy(format = it))
    }
    ExportChoiceRow("Proporção", ExportProfiles.aspects.map { it to it }, profile.aspect, enabled) {
        onChange(profile.copy(aspect = it))
    }
    ExportChoiceRow("Resolução", ExportProfiles.resolutions.map { it to "${it}p" }, profile.resolution, enabled) {
        onChange(profile.copy(resolution = it))
    }
    ExportChoiceRow("Qualidade", listOf("compact" to "Compacta", "balanced" to "Equilibrada", "high" to "Alta"), profile.quality, enabled) {
        onChange(profile.copy(quality = it))
    }
    ExportChoiceRow("FPS", ExportProfiles.frameRates.map { it to "$it FPS" }, profile.frameRate, enabled) {
        onChange(profile.copy(frameRate = it))
    }
    Text("Os FPS são limitados pela gravação original. A exportação não cria quadros adicionais.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun ExportModal(
    onClose: () -> Unit, content: @Composable () -> Unit,
) {
    val maxHeight = exportDialogHeight()
    val scroll = rememberScrollState()
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
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
                modifier = Modifier.verticalScroll(scroll).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Preview(name = "Exportação personalizada", widthDp = 390, heightDp = 700, showBackground = true)
@Preview(name = "Personalizada · fonte ampliada", widthDp = 320, heightDp = 760, fontScale = 1.5f, showBackground = true)
@Preview(name = "Personalizada · paisagem", widthDp = 760, heightDp = 360, showBackground = true)
@Composable
private fun CustomExportPreview() {
    var profile by remember { mutableStateOf(ExportProfile("custom", "Personalizada")) }
    Open_scriptTheme {
        ExportPageFrame("Personalizada", "Voltar aos destinos", {}, footer = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExportSummary("720 × 1280 · até ${profile.frameRate} FPS", profile.format.uppercase(Locale.ROOT))
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Exportar vídeo") }
            }
        }) {
            ExportTakeHeading(RecordedTake(title = "Apresentação do projeto", path = "", durationMs = 84_000))
            ExportCustomOptions(profile, true) { profile = it }
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
