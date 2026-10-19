package com.example.open_script

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.open_script.data.PromptSettings
import com.example.open_script.data.ScriptDocument

/** Zero-based rendered line; metadata only, without another accessible text or announcement. */
internal val ActiveReadingLine = SemanticsPropertyKey<Int>("ActiveReadingLine")

@Composable
internal fun ReadingPrompt(
    draft: ScriptDocument,
    preferences: PromptSettings,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    showPlaceholder: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        val readingHeight = maxHeight
        if (preferences.showPrompt && (showPlaceholder || draft.content.isNotBlank())) {
            val density = LocalDensity.current
            val viewportHeight = with(density) { readingHeight.toPx() }
            val padding = with(density) { 10.dp.toPx() }
            val leadingSpace = viewportHeight * .20f
            val fontSize = if (preferences.autoSize) minOf(preferences.size, maxOf(24f, maxWidth.value / 9f)) else preferences.size
            val font = when (preferences.font) {
                "serif" -> FontFamily.Serif
                "mono" -> FontFamily.Monospace
                else -> FontFamily.SansSerif
            }
            val color = remember(preferences.color) {
                runCatching { Color(android.graphics.Color.parseColor(preferences.color)) }.getOrDefault(Color.White)
            }
            val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .8f), Offset(0f, 2f), 6f))
            var geometry by remember(draft.content, fontSize, font, maxWidth, density.density, density.fontScale) {
                mutableStateOf<ReadingTextGeometry?>(null)
            }
            // Pixel scrolling only invalidates the text when it crosses a real rendered line.
            val activeLine by remember(geometry, scroll) {
                derivedStateOf { geometry?.let { activeReadingLine(it.lines, scroll.value.toFloat()) } }
            }
            val styledText = remember(draft.content, geometry, activeLine, color) {
                AnnotatedString.Builder(draft.content).apply {
                    val line = activeLine?.let { geometry?.lines?.getOrNull(it) }
                    if (line != null && line.start < line.end) {
                        addStyle(SpanStyle(color = color), line.start, line.end)
                    }
                }.toAnnotatedString()
            }
            val trailingSpace = readingTrailingSpace(viewportHeight, leadingSpace, padding, geometry)

            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = preferences.opacity / 100f), RoundedCornerShape(12.dp))) {
                if (draft.content.isNotBlank()) {
                    Canvas(Modifier.fillMaxSize()) {
                        val first = geometry?.lines?.firstOrNull() ?: return@Canvas
                        val current = activeLine?.let { geometry?.lines?.getOrNull(it) } ?: return@Canvas
                        val bandHeight = current.height.coerceAtMost(size.height)
                        if (bandHeight <= 0f || size.width <= 0f) return@Canvas
                        val guideCenter = padding + leadingSpace + first.center
                        val top = (guideCenter - bandHeight / 2f).coerceIn(0f, (size.height - bandHeight).coerceAtLeast(0f))
                        val inset = 3.dp.toPx().coerceAtMost(size.width / 2f)
                        val bandSize = Size((size.width - inset * 2f).coerceAtLeast(0f), bandHeight)
                        drawRoundRect(Color(0xFF0F172A).copy(alpha = .34f), Offset(inset, top), bandSize, CornerRadius(8.dp.toPx()))
                        drawRoundRect(Color.White.copy(alpha = .08f), Offset(inset, top), bandSize,
                            CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
                    }
                }
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(10.dp)) {
                    Spacer(Modifier.height(readingHeight * .20f))
                    if (draft.content.isBlank()) {
                        Text("Sua próxima ideia\ncomeça aqui.", fontSize = 34.sp, lineHeight = 42.sp,
                            fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .9f), style = shadow)
                        Text("Toque em Editar roteiro para preparar seu vídeo.", Modifier.padding(top = 16.dp),
                            color = Color(0xFFB6C2D5), fontSize = 15.sp, style = shadow)
                    } else {
                        Text(styledText, fontSize = fontSize.sp, lineHeight = (fontSize * 1.45f).sp,
                            fontFamily = font, color = color.copy(alpha = color.alpha * .44f), style = shadow,
                            modifier = Modifier.testTag("reading-prompt-text").semantics {
                                this[ActiveReadingLine] = activeLine ?: -1
                            },
                            onTextLayout = { result ->
                                val next = ReadingTextGeometry(result.size.height.toFloat(), List(result.lineCount) { line ->
                                    ReadingLine(result.getLineStart(line), result.getLineEnd(line), result.getLineTop(line), result.getLineBottom(line))
                                })
                                // Color spans produce another layout callback with identical geometry.
                                // Compare only stable metrics, never save the changing TextLayoutResult.
                                if (geometry != next) geometry = next
                            })
                    }
                    Spacer(Modifier.height(with(density) { trailingSpace.toDp() }))
                }
            }
        }
    }
}
