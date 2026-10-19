package com.example.open_script

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.open_script.media.ExportProfile
import com.example.open_script.ui.theme.Open_scriptTheme

private val DestinationNavy = Color(0xFF0F172A)
private val DestinationPanel = Color(0xFF162036)
private val DestinationRule = Color(0xFF29364E)
private val DestinationText = Color(0xFFF8FAFC)
private val DestinationMuted = Color(0xFFB6C2D5)
private val DestinationRed = Color(0xFFF34D4D)

/** The parent owns scrolling so every destination remains reachable on short screens. */
@Composable
internal fun ExportDestinations(
    profiles: List<ExportProfile>,
    enabled: Boolean,
    onSelect: (ExportProfile) -> Unit,
) {
    val custom = profiles.firstOrNull { it.id == "custom" }
        ?: ExportProfile("custom", "Personalizada")
    val groups = remember(profiles) { groupExportDestinations(profiles) }
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Escolha um destino",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = DestinationText,
        )
        CustomDestination(enabled, onClick = { onSelect(custom) })
        if (groups.isNotEmpty()) {
            Text(
                "Redes sociais",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = DestinationMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                groups.forEach { group ->
                    SocialDestinationGroup(
                        group = group,
                        expanded = expandedGroup == group.id,
                        enabled = enabled,
                        onExpand = { expandedGroup = if (expandedGroup == group.id) null else group.id },
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomDestination(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        color = DestinationPanel,
        border = BorderStroke(1.dp, DestinationRed.copy(alpha = .65f)),
        shape = shape,
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f)
            .clip(shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { },
    ) {
        Row(
            modifier = Modifier.heightIn(min = 88.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(Modifier.size(28.dp)) {
                val stroke = 1.6.dp.toPx()
                listOf(.22f to .32f, .5f to .7f, .78f to .45f).forEach { (row, knob) ->
                    val y = size.height * row
                    drawLine(DestinationRed, Offset(0f, y), Offset(size.width, y), stroke)
                    drawCircle(DestinationPanel, 3.1.dp.toPx(), Offset(size.width * knob, y))
                    drawCircle(DestinationRed, 3.1.dp.toPx(), Offset(size.width * knob, y), style = Stroke(stroke))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Personalizada", color = DestinationText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("FPS, qualidade, formato e resolução", color = DestinationMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
            DestinationArrow(DestinationRed)
        }
    }
}

@Composable
private fun SocialDestinationGroup(
    group: ExportDestinationGroup,
    expanded: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onSelect: (ExportProfile) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val multiple = group.profiles.size > 1
    val disclosureRotation by animateFloatAsState(if (expanded && multiple) 90f else 0f,
        animationSpec = tween(180), label = "destinationDisclosure")
    Surface(
        color = DestinationPanel,
        shape = shape,
        border = BorderStroke(1.dp, if (expanded && multiple) DestinationRed.copy(alpha = .5f) else DestinationRule),
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f)
            .animateContentSize(animationSpec = tween(180)),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clip(shape)
                    .clickable(enabled = enabled, role = Role.Button,
                        onClickLabel = if (multiple) "Ver formatos de ${group.label}" else "Escolher ${group.profiles.first().label}",
                        onClick = { if (multiple) onExpand() else onSelect(group.profiles.first()) })
                    .semantics(mergeDescendants = true) {
                        if (multiple) stateDescription = if (expanded) "Expandido" else "Recolhido"
                    }
                    .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SocialBrandIcon(group.id, Modifier.size(30.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(group.label, color = DestinationText, fontSize = 16.sp,
                        lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (multiple) group.profiles.joinToString(" · ") { destinationVariantLabel(it, group.label) }
                        else group.profiles.first().let { profile ->
                            val variant = destinationVariantLabel(profile, group.label)
                            if (variant != group.label) "$variant · ${profile.aspect}" else "${profile.aspect} · ${profile.resolution}p"
                        },
                        color = DestinationMuted, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
                DestinationArrow(DestinationMuted, Modifier.rotate(disclosureRotation))
            }
            if (multiple && expanded) {
                group.profiles.forEach { profile ->
                    HorizontalDivider(color = DestinationRule, modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = enabled, role = Role.Button,
                                onClickLabel = "Escolher ${profile.label}", onClick = { onSelect(profile) })
                            .semantics(mergeDescendants = true) { }
                            .heightIn(min = 58.dp).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(destinationVariantLabel(profile, group.label), color = DestinationText,
                                fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("${profile.aspect} · ${profile.resolution}p", color = DestinationMuted,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        DestinationArrow(DestinationMuted)
                    }
                }
            }
        }
    }
}

private fun destinationVariantLabel(profile: ExportProfile, brand: String): String =
    profile.label.removePrefix(brand).trimStart(' ', '·', '–', '-').ifBlank { profile.label }

@Composable
private fun DestinationArrow(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 8.dp, height = 14.dp)) {
        val stroke = 1.5.dp.toPx()
        drawLine(color, Offset(0f, 1.dp.toPx()), Offset(size.width - stroke, size.height / 2), stroke)
        drawLine(color, Offset(size.width - stroke, size.height / 2), Offset(0f, size.height - 1.dp.toPx()), stroke)
    }
}

private val PreviewDestinations = listOf(
    ExportProfile("custom", "Personalizada"),
    ExportProfile("instagram-reels", "Instagram · Reels"),
    ExportProfile("instagram-stories", "Instagram · Stories"),
    ExportProfile("instagram-feed", "Instagram · Feed", aspect = "4:5"),
    ExportProfile("tiktok", "TikTok"),
    ExportProfile("youtube-shorts", "YouTube · Shorts"),
    ExportProfile("youtube-video", "YouTube · Vídeos", aspect = "16:9"),
    ExportProfile("facebook-reels", "Facebook · Reels"),
    ExportProfile("facebook-feed", "Facebook · Feed", aspect = "4:5"),
    ExportProfile("linkedin", "LinkedIn · Feed", aspect = "1:1"),
    ExportProfile("x", "X", aspect = "16:9"),
    ExportProfile("whatsapp-status", "WhatsApp · Status", resolution = 720),
    ExportProfile("pinterest", "Pinterest"),
    ExportProfile("kwai", "Kwai"),
)

@Preview(name = "Destinos de exportação", widthDp = 390, showBackground = true, backgroundColor = 0xFF0F172A)
@Preview(name = "Destinos com texto ampliado", widthDp = 320, fontScale = 1.8f, showBackground = true,
    backgroundColor = 0xFF0F172A)
@Composable
private fun ExportDestinationsPreview() {
    Open_scriptTheme {
        Surface(color = DestinationNavy) {
            Column(Modifier.padding(20.dp)) {
                ExportDestinations(PreviewDestinations, enabled = true, onSelect = { })
            }
        }
    }
}
