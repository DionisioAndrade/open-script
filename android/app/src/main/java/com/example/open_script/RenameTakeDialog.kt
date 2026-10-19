package com.example.open_script

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.example.open_script.data.RecordedTake
import com.example.open_script.data.TakeNames

@Composable
internal fun RenameTakeDialog(take: RecordedTake, busy: Boolean, onRename: (String) -> Unit, onClose: () -> Unit) {
    var name by rememberSaveable(take.id) { mutableStateOf(take.title) }
    var submitted by rememberSaveable(take.id) { mutableStateOf<String?>(null) }
    var error by rememberSaveable(take.id) { mutableStateOf<String?>(null) }
    val normalized = runCatching { TakeNames.normalize(name) }.getOrNull()
    // Close only after the persisted title reaches the UI. A failed write keeps the draft open.
    LaunchedEffect(take.title, submitted) {
        if (submitted != null && take.title == submitted) onClose()
    }
    fun save() {
        if (busy) return
        val title = try { TakeNames.normalize(name) } catch (failure: IllegalArgumentException) {
            error = failure.message
            return
        }
        if (title == take.title) onClose() else { submitted = title; onRename(title) }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Renomear take") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it; error = null },
                    label = { Text("Nome do take") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), isError = error != null,
                    supportingText = { Text(error ?: "${name.trim().codePointCount(0, name.trim().length)} / ${TakeNames.MAX_LENGTH}") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }))
                if (busy) Text("Salvando nome…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = !busy && normalized != null) { Text("Salvar nome") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancelar") } },
    )
}
