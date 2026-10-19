@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.open_script

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.open_script.data.PromptSettings
import com.example.open_script.data.ScriptDocument
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ScriptEditor(draft: ScriptDocument, onChange: (String, String) -> Unit, onSave: () -> Unit, onClose: () -> Unit,
    onRecord: () -> Unit, onUpload: (() -> Unit)?, busy: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val formScroll = rememberScrollState()
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val active = busy || importing
    val currentDraft by rememberUpdatedState(draft)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Não foi possível abrir o arquivo." }
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 102400) { "Escolha um arquivo de até 100 KiB." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
                    }
                }
                onChange(currentDraft.title, text)
                importError = null
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { importError = error.message ?: "Use um arquivo .txt em UTF-8." }
            finally { importing = false }
        }
    }
    Dialog(onDismissRequest = { if (!active) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color(0xFF0F172A), modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val keyboard = LocalSoftwareKeyboardController.current
                val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                // The available height also shrinks with the IME, not only on rotation.
                val compact = maxHeight < 480.dp
                Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 6.dp else 20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Seu roteiro", fontSize = if (compact) 21.sp else 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (keyboardVisible) TextButton(onClick = { keyboard?.hide() }) { Text("Ocultar teclado", fontSize = 12.sp) }
                        TextButton(onClick = onClose, enabled = !active) { Text("Fechar") }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().then(if (compact) Modifier.verticalScroll(formScroll) else Modifier),
                    ) {
                        if (!compact) {
                            Text("Prepare as palavras. A conversa continua sendo sua.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Spacer(Modifier.height(18.dp))
                        }
                        OutlinedTextField(value = draft.title, onValueChange = { if (it.length <= 100) onChange(it, draft.content) },
                            label = { Text("Título") }, singleLine = true, enabled = !active, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                        OutlinedTextField(value = draft.content, onValueChange = {
                            if (it.toByteArray(Charsets.UTF_8).size <= 102400) { onChange(draft.title, it); importError = null }
                            else importError = "Use até 100 KiB no roteiro. Seu texto anterior foi mantido."
                        }, label = { Text("Roteiro") }, placeholder = { Text("Cole seu texto ou comece a escrever…") },
                            enabled = !active,
                            modifier = Modifier.fillMaxWidth().then(if (compact) Modifier.heightIn(min = 140.dp, max = 240.dp) else Modifier.weight(1f)),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { importer.launch(arrayOf("text/plain")) }, enabled = !active) { Text(if (importing) "Importando…" else "Importar .txt") }
                            Text("${draft.content.trim().split(Regex("\\s+")).count { it.isNotEmpty() }} palavras · ${draft.content.toByteArray(Charsets.UTF_8).size / 1024} / 100 KiB",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        importError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onSave, enabled = !active, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Salvar roteiro") }
                            Button(onClick = onRecord, enabled = !active, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Ir para câmera") }
                        }
                        if (onUpload != null) TextButton(onClick = onUpload, enabled = !active, modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "Enviando…" else "Enviar roteiro à conta")
                        }
                        Text("O rascunho é salvo automaticamente neste aparelho.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = if (compact) 12.dp else 0.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PromptSettingsSheet(settings: PromptSettings, onChange: (PromptSettings) -> Unit, onClose: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color(0xFF162036)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text("No seu ritmo", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("Ajuste a leitura sobre a câmera.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
            SettingSlider("Velocidade", "${settings.speed.toInt()} px/s", settings.speed, 10f..100f) { onChange(settings.copy(speed = it)) }
            SettingSlider(if (settings.autoSize) "Tamanho máximo do texto" else "Tamanho do texto", "${settings.size.toInt()}", settings.size, 20f..120f) { onChange(settings.copy(size = it)) }
            SettingToggle("Ajustar texto à tela", "Limita o tamanho para manter a leitura confortável.", settings.autoSize) { onChange(settings.copy(autoSize = it)) }
            Text("Fonte", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("sans" to "Clássica", "serif" to "Serifada", "mono" to "Mono").forEach { (value, label) ->
                    FilterChip(selected = settings.font == value, onClick = { onChange(settings.copy(font = value)) }, label = { Text(label) })
                }
            }
            SettingSlider("Fundo do roteiro", "${settings.opacity.toInt()}%", settings.opacity, 0f..70f) { onChange(settings.copy(opacity = it)) }
            Text("Cor do texto", fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("#ffffff" to "Branco", "#fff1cf" to "Areia", "#c6f6e5" to "Menta", "#f7b4b4" to "Rosa").forEach { (hex, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange(settings.copy(color = hex)) }.padding(4.dp)) {
                        Box(Modifier.size(34.dp).background(Color(android.graphics.Color.parseColor(hex)), CircleShape)
                            .border(if (settings.color.equals(hex, true)) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape))
                        Text(label, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
            SettingToggle("Mostrar roteiro", "O texto aparece apenas na prévia, fora do vídeo gravado.", settings.showPrompt) { onChange(settings.copy(showPrompt = it)) }
            SettingToggle("Espelhar prévia frontal", "O arquivo gravado mantém a imagem sem espelhamento.", settings.mirror) { onChange(settings.copy(mirror = it)) }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp).height(50.dp)) { Text("Voltar ao estúdio") }
        }
    }
}

@Composable
private fun SettingSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontWeight = FontWeight.Medium); Text(valueLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun SettingToggle(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(label, fontWeight = FontWeight.Medium); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
