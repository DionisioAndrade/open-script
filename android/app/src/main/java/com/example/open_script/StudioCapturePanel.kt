package com.example.open_script

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.open_script.ui.theme.Open_scriptTheme

private val CaptureInk = Color(0xFF0F172A)
private val CapturePanel = Color(0xFF182337)
private val CaptureWhite = Color(0xFFF8FAFC)
private val CaptureMuted = Color(0xFFB6C2D5)
private val CaptureRed = Color(0xFFF34D4D)

@Composable
internal fun StudioScriptToolbar(speed: Int, enabled: Boolean, onEdit: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier, onSpeed: () -> Unit = {}) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp || fontScale > 1.15f) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioToolbarAction(StudioControlIcon.Edit, "Editar roteiro", null, enabled, onEdit, Modifier.weight(1f))
                    StudioToolbarAction(StudioControlIcon.Adjust, "Ajustes", null, enabled, onSettings, Modifier.weight(1f))
                }
                StudioSpeedAction(speed, enabled, onSpeed, Modifier.fillMaxWidth(), horizontal = true)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioToolbarAction(StudioControlIcon.Edit, "Editar roteiro", null, enabled, onEdit, Modifier.weight(1.2f))
                StudioToolbarAction(StudioControlIcon.Adjust, "Ajustes", null, enabled, onSettings, Modifier.weight(.9f))
                StudioSpeedAction(speed, enabled, onSpeed, Modifier.weight(.8f))
            }
        }
    }
}

@Composable
private fun StudioSpeedAction(speed: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier,
    horizontal: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    val alpha = if (enabled) 1f else .4f
    val action = modifier.clip(shape).background(CaptureRed.copy(alpha = .075f))
        .border(1.dp, CaptureRed.copy(alpha = .22f), shape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Ajustar velocidade", onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = "Ajustar velocidade"
            stateDescription = "$speed pixels por segundo"
        }
        .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp)
    if (horizontal) {
        Row(action, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Velocidade", color = CaptureMuted.copy(alpha = alpha), fontSize = 12.sp,
                lineHeight = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("$speed px/s", color = CaptureWhite.copy(alpha = alpha), fontSize = 12.sp,
                lineHeight = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
        }
    } else {
        Column(action, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Velocidade", color = CaptureMuted.copy(alpha = alpha), fontSize = 10.sp, lineHeight = 13.sp)
            Text("$speed px/s", color = CaptureWhite.copy(alpha = alpha), fontSize = 12.sp,
                lineHeight = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StudioToolbarAction(icon: StudioControlIcon, title: String, detail: String?, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val alpha = if (enabled) 1f else .4f
    Row(modifier.clip(shape).background(Color.White.copy(alpha = .035f))
        .border(1.dp, Color.White.copy(alpha = .065f), shape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = title, onClick = onClick)
        .semantics(mergeDescendants = true) { }
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        StudioControlGlyph(icon, CaptureMuted.copy(alpha = alpha), Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = CaptureWhite.copy(alpha = alpha), fontSize = 12.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.Medium)
            detail?.let { Text(it, color = CaptureMuted.copy(alpha = alpha), fontSize = 10.sp,
                lineHeight = 13.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

/** All children retain their measurements while recording; only the chrome's paint disappears. */
@Composable
internal fun StudioCapturePanel(
    landscape: Boolean, recording: Boolean, chromeOpacity: Float, rehearsal: Boolean,
    speed: Int, editingEnabled: Boolean, rehearsalEnabled: Boolean, swapEnabled: Boolean, recordEnabled: Boolean,
    onEdit: () -> Unit, onSettings: () -> Unit, onRehearse: () -> Unit, onSwap: () -> Unit, onRecord: () -> Unit,
    modifier: Modifier = Modifier, onSpeed: () -> Unit = {},
) {
    val shape = if (landscape) RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)
        else RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val panelModifier = modifier.background(Brush.verticalGradient(listOf(
        CapturePanel.copy(alpha = .98f * chromeOpacity), CaptureInk.copy(alpha = .98f * chromeOpacity))), shape)
        .border(1.dp, Color.White.copy(alpha = .07f * chromeOpacity), shape)
    val rehearse: @Composable (Modifier) -> Unit = { actionModifier ->
        StudioChrome(visible = !recording, modifier = actionModifier) {
            StudioCaptureAction(if (rehearsal) StudioControlIcon.Pause else StudioControlIcon.Play,
                if (rehearsal) "Pausar leitura" else "Ensaiar", rehearsalEnabled, rehearsal, onRehearse)
        }
    }
    val swap: @Composable (Modifier) -> Unit = { actionModifier ->
        StudioChrome(visible = !recording, modifier = actionModifier) {
            StudioCaptureAction(StudioControlIcon.SwitchCamera, "Trocar câmera", swapEnabled, false, onSwap)
        }
    }
    if (landscape) {
        BoxWithConstraints(panelModifier) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                rehearse(Modifier.fillMaxWidth())
                StudioRecordControl(recordEnabled, recording, onRecord)
                swap(Modifier.fillMaxWidth())
            }
        }
    } else {
        Column(panelModifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)) {
            StudioChrome(visible = !recording) { StudioScriptToolbar(speed, editingEnabled, onEdit, onSettings, onSpeed = onSpeed) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rehearse(Modifier.weight(1f))
                StudioRecordControl(recordEnabled, recording, onRecord)
                swap(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StudioCaptureAction(icon: StudioControlIcon, label: String, enabled: Boolean, selected: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else .35f
    val ink = if (selected) CaptureRed else CaptureWhite
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics(mergeDescendants = true) { }
        .heightIn(min = 88.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Surface(Modifier.size(42.dp), shape = CircleShape,
            color = if (selected) CaptureRed.copy(alpha = .13f) else Color.White.copy(alpha = .045f),
            border = BorderStroke(1.dp, if (selected) CaptureRed.copy(alpha = .35f) else Color.White.copy(alpha = .08f))) {
            Box(contentAlignment = Alignment.Center) { StudioControlGlyph(icon, ink.copy(alpha = alpha), Modifier.size(22.dp)) }
        }
        Text(label, color = (if (selected) CaptureWhite else CaptureMuted).copy(alpha = alpha),
            fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 30.dp))
    }
}

@Composable
private fun StudioRecordControl(enabled: Boolean, stop: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) .94f else 1f, tween(120), label = "recordPress")
    Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interactions, indication = null,
                onClickLabel = if (stop) "Encerrar gravação" else "Gravar vídeo", onClick = onClick)
            .semantics { contentDescription = if (stop) "Encerrar gravação" else "Gravar vídeo" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }) {
                drawCircle(CaptureWhite.copy(alpha = if (enabled) .9f else .28f), radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                if (!stop) drawCircle(CaptureRed.copy(alpha = if (enabled) .12f else .04f), radius = size.minDimension / 2 - 5.dp.toPx())
            }
            Box(Modifier.size(if (stop) 28.dp else 62.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .background(if (enabled) CaptureRed else CaptureRed.copy(alpha = .35f), if (stop) RoundedCornerShape(6.dp) else CircleShape))
        }
        StudioChrome(visible = !stop) {
            Text("Gravar", color = CaptureWhite.copy(alpha = if (enabled) .9f else .4f), fontSize = 11.sp, lineHeight = 15.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 7.dp).heightIn(min = 30.dp))
        }
    }
}

@Preview(name = "Controles do estúdio", widthDp = 390, showBackground = true, backgroundColor = 0xFF0F172A)
@Preview(name = "Controles · fonte ampliada", widthDp = 320, fontScale = 1.5f, showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun CapturePanelPreview() {
    Open_scriptTheme {
        StudioCapturePanel(false, false, 1f, false, 83, true, true, true, true, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Controles · paisagem", widthDp = 132, heightDp = 310, showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun LandscapeCapturePanelPreview() {
    Open_scriptTheme {
        StudioCapturePanel(true, false, 1f, true, 40, true, true, true, true, {}, {}, {}, {}, {}, Modifier.fillMaxHeight())
    }
}
