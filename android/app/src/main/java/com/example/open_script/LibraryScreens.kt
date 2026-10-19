package com.example.open_script

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.open_script.data.RecordedTake
import com.example.open_script.data.ScriptDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val LibraryNavy = Color(0xFF0F172A)
private val LibraryPanel = Color(0xFF162036)
private val LibraryText = Color(0xFFF8FAFC)
private val LibraryMuted = Color(0xFFB6C2D5)
private val LibraryRule = Color(0xFF29364E)
private val LibraryRed = Color(0xFFF34D4D)

@Composable
fun ScriptsPage(
    state: StudioState,
    onNew: () -> Unit,
    onOpen: (ScriptDocument) -> Unit,
    onDelete: (ScriptDocument) -> Unit,
    onCloud: () -> Unit,
) {
    var deletingId by rememberSaveable(state.ownerId) { mutableStateOf<String?>(null) }
    val draft = state.library.draft
    val deleting = (listOf(draft) + state.library.scripts).firstOrNull { it.id == deletingId }
    val compact = compactLibraryHeight()
    val saved = remember(state.library.scripts, draft.id) {
        state.library.scripts.filterNot { it.id == draft.id }.sortedByDescending { it.updatedAt }
    }
    val draftIsSaved = state.library.scripts.any { it.id == draft.id }
    val canUseCloud = state.session != null && !state.requiresLogin

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(LibraryNavy),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = if (compact) 12.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp),
    ) {
        item(key = "scripts-heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Roteiros", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = LibraryText)
                    Text("Seu texto, pronto para gravar.", style = MaterialTheme.typography.bodyMedium, color = LibraryMuted,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onNew, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LibraryRed, contentColor = Color.White)) {
                    Text("Novo")
                }
            }
        }
        item(key = "current-draft") {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp)).background(LibraryPanel)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(LibraryRed))
                Column(Modifier.weight(1f).padding(18.dp)) {
                    Text("EM EDIÇÃO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = LibraryRed)
                    Text(draft.title.ifBlank { "Sem título" }, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, color = LibraryText, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp))
                    Text(scriptSnippet(draft), color = LibraryMuted, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text("${if (draftIsSaved) "Salvo neste aparelho" else "Rascunho local"} · ${libraryDate(draft.updatedAt)}",
                        color = LibraryMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpen(draft) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (draft.content.isBlank()) "Abrir editor" else "Continuar edição", color = LibraryText)
                        }
                        Spacer(Modifier.weight(1f))
                        if (draft.content.isNotBlank() || draftIsSaved) {
                            TextButton(onClick = { deletingId = draft.id }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Excluir", color = LibraryMuted)
                            }
                        }
                    }
                }
            }
        }
        item(key = "cloud-action") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCloud, enabled = canUseCloud && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.busy && canUseCloud) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LibraryMuted)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text("Buscar na conta")
                }
                Text(if (canUseCloud) "Abra uma cópia dos roteiros salvos na sua conta."
                    else "Entre na aba Conta para buscar seus roteiros. Os arquivos deste aparelho continuam disponíveis.",
                    style = MaterialTheme.typography.bodySmall, color = LibraryMuted)
            }
        }
        item(key = "saved-heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Salvos neste aparelho", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = LibraryText, modifier = Modifier.weight(1f))
                Text(saved.size.toString(), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                    color = LibraryMuted)
            }
        }
        if (saved.isEmpty()) {
            item(key = "empty-scripts") {
                LibraryEmptyState(
                    title = if (draftIsSaved) "Seu roteiro salvo está em edição." else "Sua biblioteca começa com um roteiro.",
                    detail = if (draftIsSaved) "Use Continuar edição para abri-lo. Novos roteiros salvos aparecerão nesta lista."
                    else "Escreva no editor e salve para voltar ao texto quando quiser gravar.",
                )
            }
        } else {
            items(saved, key = { "saved-${it.id}" }) { script ->
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        DocumentMark(Modifier.padding(top = 4.dp).size(24.dp))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(script.title.ifBlank { "Sem título" }, style = MaterialTheme.typography.titleMedium,
                                color = LibraryText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(scriptSnippet(script), color = LibraryMuted, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text("Editado em ${libraryDate(script.updatedAt)}", color = LibraryMuted,
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(onClick = { onOpen(script) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("Abrir roteiro", color = LibraryText)
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { deletingId = script.id }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("Excluir", color = LibraryMuted)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = LibraryRule, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }

    deleting?.let { script ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Excluir cópia local?") },
            text = { Text("“${script.title.ifBlank { "Sem título" }}” será excluído deste aparelho. Se o roteiro estiver salvo na conta, continuará disponível lá.") },
            confirmButton = {
                TextButton(onClick = { deletingId = null; onDelete(script) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Excluir cópia", color = LibraryRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") }
            },
        )
    }
}

@Composable
fun TakesPage(
    takes: List<RecordedTake>,
    onPlay: (RecordedTake) -> Unit,
    onExport: (RecordedTake) -> Unit,
    onShare: (RecordedTake) -> Unit,
    onDelete: (RecordedTake) -> Unit,
) {
    val sorted = remember(takes) { takes.sortedByDescending { it.createdAt } }
    val compact = compactLibraryHeight()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(LibraryNavy),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = if (compact) 12.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp),
    ) {
        item(key = "takes-heading") {
            Column {
                Text("Takes", style = MaterialTheme.typography.headlineLarge, color = LibraryText, fontWeight = FontWeight.Bold)
                Text(if (takes.size == 1) "1 vídeo salvo neste aparelho" else "${takes.size} vídeos salvos neste aparelho",
                    style = MaterialTheme.typography.bodyMedium, color = LibraryMuted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (takes.isEmpty()) {
            item(key = "empty-takes") {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayMark(Modifier.size(52.dp), color = LibraryRed)
                    Text("Seu primeiro take começa no estúdio.", style = MaterialTheme.typography.titleLarge,
                        color = LibraryText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp))
                    Text("Abra o Estúdio, prepare seu roteiro e grave. Os vídeos ficam disponíveis aqui para revisar, exportar e compartilhar.",
                        style = MaterialTheme.typography.bodyMedium, color = LibraryMuted, modifier = Modifier.padding(top = 12.dp))
                }
            }
        } else {
            items(sorted, key = { it.id }) { take ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wide = maxWidth >= 640.dp
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            TakeThumbnail(take, Modifier.width(104.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp))
                                .clickable(onClickLabel = "Revisar ${take.title}") { onPlay(take) })
                            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                                Text(take.title.ifBlank { "Sem título" }, color = LibraryText, style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(libraryDate(take.createdAt), color = LibraryMuted, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 10.dp))
                                Text("${take.width} × ${take.height}", color = LibraryMuted, fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                                Text("Original · MP4", color = LibraryMuted, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            if (wide) {
                                TakeActions(take, onPlay, onExport, onShare, onDelete, Modifier.width(248.dp))
                            }
                        }
                        if (!wide) {
                            TakeActions(take, onPlay, onExport, onShare, onDelete, Modifier.fillMaxWidth().padding(top = 12.dp))
                        }
                        HorizontalDivider(color = LibraryRule, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CloudScriptsDialog(scripts: List<ScriptDocument>, onOpen: (ScriptDocument) -> Unit, onClose: () -> Unit) {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val bodyHeight = (with(density) { window.height.toDp() } - insets.calculateTopPadding() -
        insets.calculateBottomPadding() - 180.dp).coerceIn(72.dp, 440.dp)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Roteiros da conta") },
        text = {
            Column(Modifier.heightIn(max = bodyHeight), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Abra uma cópia para editar neste aparelho.", color = LibraryMuted, style = MaterialTheme.typography.bodyMedium)
                if (scripts.isEmpty()) {
                    Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        LibraryEmptyState("Sua conta ainda não tem roteiros.", "Os textos salvos na conta aparecerão aqui.")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(scripts, key = { it.id }) { script ->
                            Column {
                                Text(script.title.ifBlank { "Sem título" }, style = MaterialTheme.typography.titleMedium,
                                    color = LibraryText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(scriptSnippet(script), style = MaterialTheme.typography.bodySmall, color = LibraryMuted,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text(libraryDate(script.updatedAt), style = MaterialTheme.typography.labelSmall,
                                    color = LibraryMuted, modifier = Modifier.padding(top = 8.dp))
                                TextButton(onClick = { onOpen(script) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("Abrir cópia local")
                                }
                                HorizontalDivider(color = LibraryRule)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fechar") }
        },
    )
}

@Composable
private fun TakeActions(
    take: RecordedTake,
    onPlay: (RecordedTake) -> Unit,
    onExport: (RecordedTake) -> Unit,
    onShare: (RecordedTake) -> Unit,
    onDelete: (RecordedTake) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPlay(take) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Revisar") }
            Button(onClick = { onExport(take) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LibraryRed, contentColor = Color.White)) { Text("Exportar") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onShare(take) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Compartilhar", color = LibraryText) }
            TextButton(onClick = { onDelete(take) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Excluir", color = LibraryMuted) }
        }
    }
}

@Composable
private fun compactLibraryHeight(): Boolean {
    val window = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { window.height.toDp() < 480.dp }
}

@Composable
private fun LibraryEmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = LibraryText, fontWeight = FontWeight.Medium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = LibraryMuted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TakeThumbnail(take: RecordedTake, modifier: Modifier = Modifier) {
    val disposed = remember(take.path, take.createdAt) { AtomicBoolean(false) }
    DisposableEffect(disposed) {
        onDispose { disposed.set(true) }
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, take.path, take.createdAt) {
        value = null
        var candidate: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { candidate = readTakeThumbnail(take.path) }
            ensureActive()
            if (!disposed.get()) {
                value = candidate
                // Ownership passes to Compose; recycling a displayed image races with RenderThread.
                candidate = null
            }
        } finally {
            candidate?.let { if (!it.isRecycled) it.recycle() }
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(modifier.background(LibraryPanel), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = "Prévia do take ${take.title}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            PlayMark(Modifier.size(28.dp), color = LibraryMuted)
        }
        Text(takeDuration(take.durationMs), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                .background(LibraryNavy.copy(alpha = 0.9f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 3.dp))
    }
}

/** API 26 has getFrameAtTime; scaled-frame retrieval starts at API 27. */
private fun readTakeThumbnail(path: String): Bitmap? {
    val file = File(path)
    if (!file.isFile || file.length() == 0L) return null
    val retriever = MediaMetadataRetriever()
    var original: Bitmap? = null
    return try {
        retriever.setDataSource(file.absolutePath)
        original = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val frame = original
        val longest = maxOf(frame.width, frame.height)
        if (longest <= 320) {
            original = null
            frame
        } else {
            val factor = 320f / longest
            Bitmap.createScaledBitmap(frame, (frame.width * factor).toInt().coerceAtLeast(1),
                (frame.height * factor).toInt().coerceAtLeast(1), true)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        original?.let { if (!it.isRecycled) it.recycle() }
        runCatching { retriever.release() }
    }
}

@Composable
private fun DocumentMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRoundRect(LibraryMuted, topLeft = Offset(size.width * 0.15f, 1f),
            size = Size(size.width * 0.7f, size.height - 2f), cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        for (line in 1..3) {
            val y = size.height * (0.25f + line * 0.15f)
            drawLine(LibraryMuted, Offset(size.width * 0.3f, y), Offset(size.width * 0.7f, y), 1.2.dp.toPx())
        }
    }
}

@Composable
private fun PlayMark(modifier: Modifier = Modifier, color: Color = LibraryText) {
    Canvas(modifier) {
        val triangle = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.15f)
            lineTo(size.width * 0.82f, size.height * 0.5f)
            lineTo(size.width * 0.28f, size.height * 0.85f)
            close()
        }
        drawPath(triangle, color, style = Stroke(2.dp.toPx()))
    }
}

private fun scriptSnippet(script: ScriptDocument): String = script.content.trim().replace(Regex("\\s+"), " ")
    .ifBlank { "Escreva ou cole seu texto no editor." }

private fun libraryDate(timestamp: Long): String = if (timestamp > 0L) {
    SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(timestamp))
} else "Data indisponível"

private fun takeDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds / 60L) % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds % 60L)
    else "%02d:%02d".format(Locale.ROOT, minutes, seconds % 60L)
}
