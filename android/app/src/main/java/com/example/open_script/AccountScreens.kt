package com.example.open_script

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.open_script.data.LegalDocument

private enum class AccountMode { LOGIN, SIGNUP, RESET }

@Composable
fun AccountPage(state: StudioState, model: StudioViewModel) {
    val enabled = !state.busy && !state.loading
    var origin by remember(state.origin) { mutableStateOf(state.origin) }
    var mode by remember(state.session?.user?.id, state.requiresLogin) { mutableStateOf(AccountMode.LOGIN) }
    var name by remember { mutableStateOf("") }
    var email by remember(state.session?.user?.id) { mutableStateOf(state.session?.user?.email.orEmpty()) }
    // Passwords deliberately use memory-only state, never rememberSaveable or preferences.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var acceptTerms by remember(state.terms?.receipt) { mutableStateOf(false) }
    var acceptPrivacy by remember(state.privacy?.receipt) { mutableStateOf(false) }
    val changedOrigin = origin.trim().trimEnd('/') != state.origin.trimEnd('/')
    val canConnect = state.origin.isNotBlank() && !changedOrigin
    val signedIn = state.session != null && !state.requiresLogin
    val changeMode: (AccountMode) -> Unit = { next ->
        mode = next
        password = ""
        confirmation = ""
        acceptTerms = false
        acceptPrivacy = false
    }

    LaunchedEffect(state.session?.user?.id, state.requiresLogin, state.origin) {
        password = ""
        confirmation = ""
        acceptTerms = false
        acceptPrivacy = false
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sua conta", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Entre para guardar roteiros e ajustes no servidor. A câmera e seus vídeos continuam no aparelho.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AccountSection {
            Text("Servidor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = origin, onValueChange = { origin = it }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), label = { Text("Origem do servidor") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                supportingText = { Text("Use HTTPS e informe apenas o domínio, sem /api/v1.") },
            )
            if (BuildConfig.DEBUG) {
                Text(
                    "No emulador Android, o servidor local usa http://10.0.2.2:8000.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Alterar o servidor encerra o acesso neste aparelho e abre a área de visitante. As bibliotecas locais continuam separadas por conta.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { model.setOrigin(origin) }, enabled = enabled && origin.isNotBlank() && changedOrigin,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Aplicar servidor") }
            if (changedOrigin) {
                Text("Aplique o servidor antes de acessar sua conta.", style = MaterialTheme.typography.bodySmall)
            } else if (state.origin.isNotBlank()) {
                Text("Servidor configurado", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (signedIn) {
            val user = requireNotNull(state.session).user
            AccountSection {
                Text(user.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(user.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text(
                    "Você escolhe quando buscar ou enviar dados. Os vídeos não são enviados à conta.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = model::fetchScripts, enabled = enabled && canConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Buscar roteiros da conta")
                }
                OutlinedButton(onClick = model::pullPreferences, enabled = enabled && canConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Aplicar ajustes da conta")
                }
                Text(
                    "Aplicar substitui os ajustes de leitura e câmera deste aparelho pelos ajustes guardados na conta.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = model::pushPreferences, enabled = enabled && canConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Enviar ajustes deste aparelho")
                }
                TextButton(onClick = model::logout, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Sair da conta")
                }
            }
        } else {
            if (state.requiresLogin) {
                AccountSection {
                    Text("Entre novamente", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Seu acesso precisa ser renovado. Os roteiros e takes desta conta continuam salvos no aparelho.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.session?.user?.email?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = model::logout, enabled = enabled) { Text("Sair desta conta e usar como visitante") }
                }
            }

            AccountSection {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = mode == AccountMode.LOGIN, onClick = { changeMode(AccountMode.LOGIN) },
                        enabled = enabled, label = { Text("Entrar") },
                    )
                    FilterChip(
                        selected = mode == AccountMode.SIGNUP, onClick = { changeMode(AccountMode.SIGNUP) },
                        enabled = enabled, label = { Text("Criar conta") },
                    )
                }
                Text(
                    when (mode) {
                        AccountMode.LOGIN -> "Bem-vindo ao seu estúdio"
                        AccountMode.SIGNUP -> "Crie sua conta OpenScript"
                        AccountMode.RESET -> "Recuperar acesso"
                    },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                )
                if (mode == AccountMode.RESET) {
                    Text(
                        "Informe seu e-mail para receber um link e escolher uma nova senha. O link abre a recuperação no navegador.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (mode == AccountMode.SIGNUP) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth(), label = { Text("Seu nome") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), label = { Text("E-mail") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = if (mode == AccountMode.RESET) ImeAction.Done else ImeAction.Next),
                )

                if (mode != AccountMode.RESET) {
                    AccountPasswordField(password, { password = it }, "Senha", enabled)
                }
                if (mode == AccountMode.SIGNUP) {
                    AccountPasswordField(
                        value = confirmation, onValueChange = { confirmation = it }, label = "Confirmar senha", enabled = enabled,
                        mismatch = confirmation.isNotEmpty() && confirmation != password,
                    )
                    Text(
                        "Use uma senha forte. Abra cada documento e confirme sua leitura antes de marcar os aceites.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { model.legal("terms") }, enabled = enabled && canConnect) {
                        Text(if (state.terms == null) "Ler Termos de Uso" else "Reler Termos de Uso")
                    }
                    AcceptanceRow(
                        checked = acceptTerms,
                        onCheckedChange = { acceptTerms = it },
                        enabled = enabled && state.terms != null,
                        text = "Li e aceito os Termos de Uso",
                    )
                    TextButton(onClick = { model.legal("privacy") }, enabled = enabled && canConnect) {
                        Text(if (state.privacy == null) "Ler Política de Privacidade" else "Reler Política de Privacidade")
                    }
                    AcceptanceRow(
                        checked = acceptPrivacy,
                        onCheckedChange = { acceptPrivacy = it },
                        enabled = enabled && state.privacy != null,
                        text = "Li e aceito a Política de Privacidade",
                    )
                }

                val ready = enabled && canConnect && email.isNotBlank() && when (mode) {
                    AccountMode.LOGIN -> password.isNotEmpty()
                    AccountMode.SIGNUP -> name.isNotBlank() && password.isNotEmpty() && password == confirmation
                        && acceptTerms && acceptPrivacy && state.terms != null && state.privacy != null
                    AccountMode.RESET -> true
                }
                Button(
                    onClick = {
                        when (mode) {
                            AccountMode.LOGIN -> model.login(email.trim(), password)
                            AccountMode.SIGNUP -> model.signup(name.trim(), email.trim(), password, confirmation)
                            AccountMode.RESET -> model.passwordReset(email.trim())
                        }
                        password = ""
                        confirmation = ""
                    },
                    enabled = ready, modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when (mode) {
                        AccountMode.LOGIN -> "Entrar"
                        AccountMode.SIGNUP -> "Criar minha conta"
                        AccountMode.RESET -> "Enviar link de recuperação"
                    })
                }
                if (mode == AccountMode.LOGIN) {
                    TextButton(onClick = { changeMode(AccountMode.RESET) }, enabled = enabled) { Text("Esqueci minha senha") }
                } else if (mode == AccountMode.RESET) {
                    TextButton(onClick = { changeMode(AccountMode.LOGIN) }, enabled = enabled) { Text("Voltar para entrar") }
                }
                if (!canConnect) {
                    Text("Configure e aplique o servidor acima para continuar.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            "A conta é opcional. Você pode escrever, gravar e salvar vídeos sem entrar. Os dados de visitante ficam separados dos dados de cada conta.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AccountSection(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun AccountPasswordField(
    value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean, mismatch: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, enabled = enabled,
        singleLine = true, modifier = Modifier.fillMaxWidth(), isError = mismatch,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }, enabled = enabled) { Text(if (visible) "Ocultar" else "Mostrar") }
        },
        supportingText = if (mismatch) {{ Text("As senhas precisam ser iguais.") }} else null,
    )
}

@Composable
private fun AcceptanceRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LegalDialog(document: LegalDocument, busy: Boolean, onConfirm: () -> Unit, onClose: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = remember(document.kind, document.openedReceipt) { ScrollState(0) }
    var laidOut by remember(document.kind, document.openedReceipt) { mutableStateOf(false) }
    val atEnd by remember(scroll, laidOut) { derivedStateOf { laidOut && scroll.value >= scroll.maxValue } }
    LaunchedEffect(document.kind, document.openedReceipt) { keyboard?.hide() }
    Dialog(onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val availableHeight = minOf(maxHeight, 680.dp)
            val compact = availableHeight < 480.dp
            Surface(
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = availableHeight),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = availableHeight).padding(if (compact) 12.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(document.title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClose, enabled = !busy) { Text("Fechar") }
                    }
                    HorizontalDivider()
                    // Keep the complete document and its final action in one bounded viewport.
                    // A fixed footer could consume the entire height of a landscape window.
                    Column(
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                            .onSizeChanged { laidOut = it.height > 0 }.verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Versão ${document.version}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        document.sections.forEach { section ->
                            Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            section.paragraphs.forEach { paragraph ->
                                Text(paragraph, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        HorizontalDivider()
                        Text(
                            "Leia até o fim e confirme a leitura. O aceite será marcado separadamente no cadastro.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { if (atEnd && !busy) onConfirm() }, enabled = atEnd && !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Confirmar leitura")
                        }
                    }
                }
            }
        }
    }
}
