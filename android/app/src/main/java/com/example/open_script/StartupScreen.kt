package com.example.open_script

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

/** A single Activity owns startup and the studio; rotating never replays the introduction. */
@Composable
internal fun OpenScriptStartup(showIntroduction: Boolean, model: StudioViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var introduction by rememberSaveable { mutableStateOf(showIntroduction) }
    LaunchedEffect(Unit) { if (introduction) { delay(1100L); introduction = false } }
    if (introduction || state.loading) StartupArt() else OpenScriptApp(model)
}

/** Native vector art stays sharp at any density and needs no image download at launch. */
@Composable
internal fun StartupArt() {
    val navy = Color(0xFF0F172A)
    val red = Color(0xFFF34D4D)
    val muted = Color(0xFFB6C2D5)
    val window = LocalWindowInfo.current.containerSize
    val wide = window.width > window.height
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(450)) }
    Box(Modifier.fillMaxSize().background(navy).semantics { contentDescription = "Abertura OpenScript" }) {
        Canvas(Modifier.fillMaxSize()) {
            val left = size.width * .10f
            val right = size.width * .90f
            val top = size.height * .15f
            val bottom = size.height * .85f
            val corner = minOf(size.width, size.height) * .075f
            val stroke = 2.dp.toPx()
            val ink = Color(0xFF39465E)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color = ink) = drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke)
            line(left, top + corner, left, top); line(left, top, left + corner, top)
            line(right - corner, top, right, top); line(right, top, right, top + corner)
            line(left, bottom - corner, left, bottom); line(left, bottom, left + corner, bottom)
            line(right - corner, bottom, right, bottom); line(right, bottom, right, bottom - corner)
            val guide = size.height * if (wide) .79f else .67f
            line(left + corner, guide, right - corner, guide, ink.copy(alpha = .4f))
            drawLine(red.copy(alpha = reveal.value), Offset(left + corner, guide), Offset(left + corner + (right - left - 2 * corner) * .32f * reveal.value, guide), stroke)
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                modifier = Modifier.size(if (wide) 104.dp else 150.dp).graphicsLayer {
                    alpha = reveal.value
                    scaleX = .94f + .06f * reveal.value
                    scaleY = scaleX
                })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.graphicsLayer { alpha = reveal.value }) {
                Text("open", fontSize = if (wide) 36.sp else 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp, color = Color.White)
                Text("script", fontSize = if (wide) 36.sp else 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, color = Color.White)
            }
            Text("Encontre seu ritmo.", Modifier.padding(top = if (wide) 10.dp else 18.dp), fontSize = 15.sp, color = muted)
        }
        Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = if (wide) 18.dp else 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(6.dp)) { drawCircle(red) }
            Text("  ESTÚDIO DE GRAVAÇÃO", fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 2.sp, color = muted)
        }
    }
}
