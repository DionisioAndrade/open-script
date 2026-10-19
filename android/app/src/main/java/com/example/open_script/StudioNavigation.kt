package com.example.open_script

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.open_script.ui.theme.Open_scriptTheme

private val NavigationItems = listOf("Estúdio", "Roteiro", "Takes", "Conta")
private val DockShape = RoundedCornerShape(24.dp)
private val TabShape = RoundedCornerShape(18.dp)

@Composable
internal fun StudioNavigation(page: Int, enabled: Boolean, landscape: Boolean, onSelect: (Int) -> Unit) {
    val background = MaterialTheme.colorScheme.background
    if (landscape) {
        // The Scaffold has already consumed system insets around the side rail.
        Box(Modifier.width(88.dp).fillMaxHeight().background(background).padding(horizontal = 8.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center) {
            NavigationDock(page, enabled, true, onSelect, Modifier.verticalScroll(rememberScrollState(), enabled = enabled))
        }
    } else {
        Box(Modifier.fillMaxWidth().background(background)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            NavigationDock(page, enabled, false, onSelect, Modifier.widthIn(max = 520.dp).fillMaxWidth())
        }
    }
}

@Composable
private fun NavigationDock(
    page: Int, enabled: Boolean, vertical: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = DockShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .45f)),
        shadowElevation = 8.dp) {
        if (vertical) {
            Column(Modifier.padding(4.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NavigationItems.forEachIndexed { index, title ->
                    NavigationTab(index, title, page == index, enabled, { onSelect(index) }, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(Modifier.padding(4.dp).selectableGroup()) {
                NavigationItems.forEachIndexed { index, title ->
                    NavigationTab(index, title, page == index, enabled, { onSelect(index) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NavigationTab(
    index: Int, title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val selection by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "navigationSelection")
    val tileColor by animateColorAsState(
        if (selected) colors.surfaceContainerHighest.copy(alpha = .7f) else Color.Transparent,
        tween(200), label = "navigationTile",
    )
    val iconColor by animateColorAsState(
        if (selected) colors.primary else colors.onSurfaceVariant, tween(200), label = "navigationIcon",
    )
    val textColor by animateColorAsState(
        if (selected) colors.onSurface else colors.onSurfaceVariant, tween(200), label = "navigationLabel",
    )
    // Selection changes paint only. Recording can fade the whole dock without moving it.
    Box(modifier.heightIn(min = 60.dp).clip(TabShape).background(tileColor)
        .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 3.dp).size(width = 16.dp, height = 2.dp)
            .graphicsLayer { alpha = selection }.background(colors.primary, RoundedCornerShape(1.dp)))
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            StudioGlyph(index, iconColor, Modifier.size(24.dp))
            Spacer(Modifier.height(5.dp))
            Text(title, color = textColor, fontSize = 11.sp, lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = .1.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Preview(name = "Menu inferior · Estúdio", widthDp = 390, showBackground = true)
@Preview(name = "Menu inferior · fonte ampliada", widthDp = 320, fontScale = 1.5f, showBackground = true)
@Composable
private fun StudioFooterPreview() {
    Open_scriptTheme { StudioNavigation(page = 0, enabled = true, landscape = false, onSelect = {}) }
}

@Preview(name = "Menu lateral", widthDp = 88, heightDp = 320, showBackground = true)
@Composable
private fun StudioRailPreview() {
    Open_scriptTheme { StudioNavigation(page = 2, enabled = true, landscape = true, onSelect = {}) }
}
