package com.example.open_script

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.open_script.data.RecordedTake
import com.example.open_script.data.ScriptDocument
import com.example.open_script.ui.theme.Open_scriptTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LibraryNavy = Color(0xFF0F172A)
private val LibraryPanel = Color(0xFF162036)
private val LibraryText = Color(0xFFF8FAFC)
private val LibraryMuted = Color(0xFFB6C2D5)
private val LibraryRule = Color(0xFF29364E)
private val LibraryRed = Color(0xFFF34D4D)

@Composable
fun TakesPage(
    takes: List<RecordedTake>,
    onPlay: (RecordedTake) -> Unit,
    onExport: (RecordedTake) -> Unit,
    onShare: (RecordedTake) -> Unit,
    onDelete: (RecordedTake) -> Unit,
    onOpenStudio: () -> Unit,
    onRename: (RecordedTake) -> Unit = {},
) {
    val sorted = remember(takes) { takes.sortedByDescending { it.createdAt } }
    val compact = compactLibraryHeight()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        modifier = Modifier.fillMaxSize().background(LibraryNavy),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = if (compact) 12.dp else 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "takes-heading", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = if (compact) 0.dp else 8.dp)) {
                Text("SUAS GRAVAÇÕES", color = LibraryMuted, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Takes", Modifier.weight(1f), fontSize = 34.sp, lineHeight = 40.sp,
                        letterSpacing = (-1).sp, color = LibraryText, fontWeight = FontWeight.SemiBold)
                    if (takes.isNotEmpty()) Surface(shape = RoundedCornerShape(12.dp), color = LibraryPanel,
                        border = BorderStroke(1.dp, LibraryRule)) {
                        Text(if (takes.size == 1) "1 vídeo" else "${takes.size} vídeos", color = LibraryMuted,
                            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Text("Salvos neste aparelho", color = LibraryMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (takes.isEmpty()) {
            item(key = "empty-takes", span = { GridItemSpan(maxLineSpan) }) {
                EmptyTakes(onOpenStudio, compact)
            }
        } else {
            item(key = "takes-order", span = { GridItemSpan(maxLineSpan) }) {
                Text("Mais recentes", color = LibraryMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            items(sorted, key = { it.id }) { take ->
                TakeCard(take, onPlay, onExport, onShare, onDelete, onRename)
            }
        }
    }
}

@Composable
private fun TakeCard(
    take: RecordedTake, onPlay: (RecordedTake) -> Unit, onExport: (RecordedTake) -> Unit,
    onShare: (RecordedTake) -> Unit, onDelete: (RecordedTake) -> Unit, onRename: (RecordedTake) -> Unit,
) {
    val title = takeDisplayTitle(take)
    Surface(shape = RoundedCornerShape(22.dp), color = LibraryPanel,
        border = BorderStroke(1.dp, LibraryRule.copy(alpha = .7f))) {
        Column {
            TakeMediaPreview(take, { onPlay(take) }, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = 2.dp)) {
                        Text(title, color = LibraryText, fontSize = 17.sp, lineHeight = 23.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(libraryDate(take.createdAt), color = LibraryMuted, fontSize = 12.sp, lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 5.dp))
                    }
                    TakeOptions(take, onShare, onDelete, onRename)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(takeFileSize(take.sizeBytes), color = LibraryText, fontSize = 12.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (take.width > 0 && take.height > 0) Text("${take.width}×${take.height}", color = LibraryMuted,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
                }
                TakeExportAction(take, onExport)
            }
        }
    }
}

@Composable
private fun TakeOptions(
    take: RecordedTake, onShare: (RecordedTake) -> Unit, onDelete: (RecordedTake) -> Unit,
    onRename: (RecordedTake) -> Unit,
) {
    var expanded by rememberSaveable(take.id) { mutableStateOf(false) }
    val title = takeDisplayTitle(take)
    val description = if (title == "Take") "Mais opções do take de ${libraryDate(take.createdAt)}" else "Mais opções de $title"
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)
            .semantics { contentDescription = description }) {
            Canvas(Modifier.size(20.dp)) {
                for (position in listOf(.2f, .5f, .8f)) drawCircle(LibraryMuted, 1.6.dp.toPx(), Offset(size.width / 2, size.height * position))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Renomear") }, onClick = { expanded = false; onRename(take) })
            DropdownMenuItem(text = { Text("Compartilhar") }, onClick = { expanded = false; onShare(take) })
            DropdownMenuItem(text = { Text("Excluir", color = MaterialTheme.colorScheme.error) }, onClick = { expanded = false; onDelete(take) })
        }
    }
}

@Composable
private fun EmptyTakes(onOpenStudio: () -> Unit, compact: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = LibraryPanel,
        border = BorderStroke(1.dp, LibraryRule.copy(alpha = .7f))) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = if (compact) 24.dp else 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(width = 160.dp, height = 112.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 132.dp, height = 82.dp).rotate(-9f)
                    .background(LibraryRule.copy(alpha = .4f), RoundedCornerShape(16.dp)))
                Surface(Modifier.size(width = 132.dp, height = 82.dp).rotate(4f), color = LibraryNavy,
                    shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LibraryRule)) {
                    Box(contentAlignment = Alignment.Center) { PlayMark(Modifier.size(30.dp), LibraryRed) }
                }
            }
            Text("Seu primeiro take\ncomeça no estúdio.", color = LibraryText, fontSize = 24.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
            Text("Depois de gravar, seus vídeos aparecem aqui para revisar, exportar e compartilhar.",
                color = LibraryMuted, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
            Button(onClick = onOpenStudio, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp)) { Text("Abrir estúdio") }
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
                Text("O texto escolhido passa a ser seu roteiro neste aparelho.", color = LibraryMuted, style = MaterialTheme.typography.bodyMedium)
                if (scripts.isEmpty()) {
                    Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        LibraryEmptyState("Sua conta ainda não tem roteiros.", "Os textos salvos na conta aparecerão aqui.")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(scripts, key = { it.id }) { script ->
                            Column {
                                Text(scriptSnippet(script), style = MaterialTheme.typography.bodyMedium, color = LibraryText,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(libraryDate(script.updatedAt), style = MaterialTheme.typography.labelSmall,
                                    color = LibraryMuted, modifier = Modifier.padding(top = 8.dp))
                                TextButton(onClick = { onOpen(script) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("Usar este texto")
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
private fun TakeExportAction(
    take: RecordedTake,
    onExport: (RecordedTake) -> Unit,
) {
    Button(onClick = { onExport(take) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LibraryRed, contentColor = Color.White)) { Text("Exportar") }
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

private val automaticTakeTitle = Regex(
    "^Take (?:\\p{Nd}{2}/\\p{Nd}{2}/\\p{Nd}{4} \\p{Nd}{2}:\\p{Nd}{2}|\\p{Nd}{2}/\\p{Nd}{2} · \\p{Nd}{2}:\\p{Nd}{2})$",
)

/** Hide only the known automatic timestamp names; custom titles stay untouched. */
internal fun takeDisplayTitle(take: RecordedTake): String = when {
    take.title.isBlank() || automaticTakeTitle.matches(take.title) -> "Take"
    else -> take.title
}

private fun takeFileSize(bytes: Long): String = when {
    bytes <= 0L -> "Tamanho indisponível"
    bytes < 100_000L -> "< 0,1 MB"
    bytes < 1_000_000_000L -> String.format(Locale.forLanguageTag("pt-BR"), "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.forLanguageTag("pt-BR"), "%.1f GB", bytes / 1_000_000_000.0)
}

private fun libraryDate(timestamp: Long): String = if (timestamp > 0L) {
    SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(timestamp))
} else "Data indisponível"

@Preview(name = "Takes · biblioteca", widthDp = 390, heightDp = 760, showBackground = true)
@Preview(name = "Takes · paisagem", widthDp = 760, heightDp = 360, showBackground = true)
@Preview(name = "Takes · cartões com fonte ampliada", widthDp = 320, heightDp = 760, fontScale = 1.5f, showBackground = true)
@Composable
private fun TakesLibraryPreview() {
    val samples = listOf(
        RecordedTake(id = "preview-1", title = "Take 19/10/2026 16:32", path = "", durationMs = 84_000, createdAt = 1_792_436_520_000,
            sizeBytes = 93_600_000L),
        RecordedTake(id = "preview-2", title = "Apresentação do projeto", path = "", durationMs = 1_460_000, createdAt = 1_792_432_800_000,
            width = 1920, height = 1080, sizeBytes = 1_245_000_000L),
    )
    Open_scriptTheme { TakesPage(samples, {}, {}, {}, {}, {}) }
}

@Preview(name = "Takes · vazio", widthDp = 390, heightDp = 700, showBackground = true)
@Preview(name = "Takes · fonte ampliada", widthDp = 320, heightDp = 760, fontScale = 1.5f, showBackground = true)
@Composable
private fun EmptyTakesPreview() {
    Open_scriptTheme { TakesPage(emptyList(), {}, {}, {}, {}, {}) }
}
