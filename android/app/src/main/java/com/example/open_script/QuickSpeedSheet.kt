@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.open_script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.open_script.ui.theme.Open_scriptTheme
import kotlin.math.roundToInt

@Composable
internal fun QuickSpeedSheet(speed: Float, onSpeedChange: (Float) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        QuickSpeedContent(speed, onSpeedChange, onClose,
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).navigationBarsPadding())
    }
}

@Composable
private fun QuickSpeedContent(speed: Float, onSpeedChange: (Float) -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier) {
    val value = if (speed.isFinite()) speed.coerceIn(10f, 100f) else 40f
    Column(modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val number: @Composable () -> Unit = {
                Text("${value.toInt()} px/s", style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { contentDescription = "Velocidade atual: ${value.toInt()} pixels por segundo" })
            }
            if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.2f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Velocidade", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    number()
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Velocidade", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                number()
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedIconButton(onClick = { onSpeedChange((value - 5f).coerceAtLeast(10f)) }, enabled = value > 10f,
                modifier = Modifier.semantics { contentDescription = "Diminuir velocidade" }) { Text("−", fontSize = 24.sp) }
            Slider(value = value, onValueChange = { onSpeedChange(it.roundToInt().coerceIn(10, 100).toFloat()) },
                valueRange = 10f..100f, modifier = Modifier.weight(1f)
                    .semantics { contentDescription = "Velocidade de leitura" })
            OutlinedIconButton(onClick = { onSpeedChange((value + 5f).coerceAtMost(100f)) }, enabled = value < 100f,
                modifier = Modifier.semantics { contentDescription = "Aumentar velocidade" }) { Text("+", fontSize = 24.sp) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("O roteiro acompanha o ajuste na hora.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Concluir") }
        }
    }
}

@Preview(name = "Velocidade rápida", widthDp = 390, showBackground = true, backgroundColor = 0xFF162036)
@Preview(name = "Velocidade · fonte ampliada", widthDp = 320, fontScale = 1.5f, showBackground = true, backgroundColor = 0xFF162036)
@Composable
private fun QuickSpeedPreview() {
    var speed by remember { mutableFloatStateOf(40f) }
    Open_scriptTheme { QuickSpeedContent(speed, { speed = it }, {}) }
}
