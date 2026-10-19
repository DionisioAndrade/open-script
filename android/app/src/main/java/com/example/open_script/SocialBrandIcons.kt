package com.example.open_script

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform

/** Small vector marks, rendered locally; the adjacent network name supplies the accessible label. */
@Composable
internal fun SocialBrandIcon(brand: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            when (brand) {
                "instagram" -> {
                    val colors = Brush.linearGradient(
                        listOf(Color(0xFFFFC655), Color(0xFFF54B74), Color(0xFFAD64E8)),
                        start = Offset(2f, 22f), end = Offset(22f, 2f),
                    )
                    drawRoundRect(colors, Offset(2.2f, 2.2f), Size(19.6f, 19.6f), CornerRadius(5.5f), style = Stroke(2f))
                    drawCircle(colors, 4.6f, Offset(12f, 12f), style = Stroke(2f))
                    drawCircle(Color(0xFFF68CB9), 1.35f, Offset(17.8f, 6.3f))
                }
                "youtube" -> {
                    drawRoundRect(Color(0xFFFF4545), Offset(1f, 4f), Size(22f, 16f), CornerRadius(5f))
                    drawPath(Path().apply { moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close() }, Color.White)
                }
                "facebook" -> {
                    drawRoundRect(Color(0xFF3C85F6), Offset(1f, 1f), Size(22f, 22f), CornerRadius(5f))
                    drawPath(Path().apply {
                        moveTo(14.2f, 23f); lineTo(10.5f, 23f); lineTo(10.5f, 14f)
                        lineTo(8f, 14f); lineTo(8f, 10.7f); lineTo(10.5f, 10.7f); lineTo(10.5f, 8.5f)
                        cubicTo(10.5f, 4.8f, 12.5f, 3.3f, 15.8f, 3.3f)
                        lineTo(18f, 3.5f); lineTo(18f, 6.6f); lineTo(16.2f, 6.6f)
                        cubicTo(14.5f, 6.6f, 14.2f, 7.4f, 14.2f, 8.7f)
                        lineTo(14.2f, 10.7f); lineTo(17.8f, 10.7f); lineTo(17.2f, 14f)
                        lineTo(14.2f, 14f); close()
                    }, Color.White)
                }
                "tiktok" -> {
                    translate(-1f, -.5f) { musicNote(Color(0xFF4DE8E5)) }
                    translate(1f, .7f) { musicNote(Color(0xFFFF5676)) }
                    musicNote(Color.White)
                }
                "linkedin" -> {
                    drawRoundRect(Color(0xFF3A91D5), Offset(1f, 1f), Size(22f, 22f), CornerRadius(3f))
                    drawCircle(Color.White, 1.55f, Offset(6.2f, 6.5f))
                    drawRect(Color.White, Offset(4.8f, 9.5f), Size(2.9f, 9f))
                    drawPath(Path().apply {
                        moveTo(10f, 9.5f); lineTo(12.9f, 9.5f); lineTo(12.9f, 10.7f)
                        cubicTo(14f, 8.6f, 19.6f, 8f, 19.6f, 13.5f)
                        lineTo(19.6f, 18.5f); lineTo(16.5f, 18.5f); lineTo(16.5f, 13.8f)
                        cubicTo(16.5f, 11.2f, 13f, 11.4f, 13f, 14f)
                        lineTo(13f, 18.5f); lineTo(10f, 18.5f); close()
                    }, Color.White)
                }
                "x" -> {
                    drawPath(Path().apply {
                        moveTo(3f, 2.5f); lineTo(8.2f, 2.5f); lineTo(21f, 21.5f)
                        lineTo(15.8f, 21.5f); close()
                    }, Color(0xFFF8FAFC), style = Stroke(1.5f))
                    drawLine(Color(0xFFF8FAFC), Offset(20f, 2.5f), Offset(3f, 21.5f), 1.8f)
                }
                "whatsapp" -> {
                    drawCircle(Color(0xFF3FC87C), 11f, Offset(12f, 12f))
                    drawPath(Path().apply {
                        moveTo(5.7f, 17.4f); lineTo(4.8f, 20.1f); lineTo(8.1f, 19.2f)
                        cubicTo(16.2f, 23f, 23f, 12.2f, 17.2f, 6.4f)
                        cubicTo(11.8f, 1f, 1.4f, 7.1f, 5.7f, 15.9f)
                    }, Color.White, style = Stroke(1.45f, cap = StrokeCap.Round))
                    drawPath(Path().apply {
                        moveTo(8.4f, 8.5f); cubicTo(7.8f, 11.1f, 11.2f, 15.5f, 15.7f, 15.4f)
                        lineTo(16.4f, 13.9f); lineTo(13.6f, 12.6f); lineTo(12.7f, 13.5f)
                        cubicTo(10.9f, 12.7f, 10.2f, 11.8f, 10f, 10.6f)
                        lineTo(10.9f, 9.9f); lineTo(9.7f, 7.5f); close()
                    }, Color.White)
                }
                "pinterest" -> {
                    drawCircle(Color(0xFFF14A59), 11f, Offset(12f, 12f))
                    drawPath(Path().apply {
                        moveTo(9.1f, 22f); lineTo(12.1f, 10.3f)
                        moveTo(9.2f, 13.5f)
                        cubicTo(5.3f, 8.1f, 12.6f, 2.9f, 16.2f, 7.1f)
                        cubicTo(20.1f, 11.6f, 13.3f, 18.8f, 11.1f, 13.3f)
                    }, Color.White, style = Stroke(2.2f, cap = StrokeCap.Round))
                }
                "kwai" -> {
                    drawRoundRect(Color(0xFFF9A144), Offset(1f, 1f), Size(22f, 22f), CornerRadius(6f))
                    drawCircle(Color.White, 3.1f, Offset(8.4f, 7.7f), style = Stroke(1.7f))
                    drawCircle(Color.White, 3.1f, Offset(15.7f, 7.7f), style = Stroke(1.7f))
                    drawRoundRect(Color.White, Offset(5.4f, 11.1f), Size(10.8f, 7.4f), CornerRadius(2f), style = Stroke(1.7f))
                    drawPath(Path().apply {
                        moveTo(16.2f, 13.4f); lineTo(20.2f, 11.7f)
                        lineTo(20.2f, 18.2f); lineTo(16.2f, 16.4f)
                    }, Color.White, style = Stroke(1.7f))
                }
                else -> {
                    drawCircle(Color(0xFFB6C2D5), 9f, Offset(12f, 12f), style = Stroke(1.7f))
                    drawPath(Path().apply { moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close() }, Color(0xFFF8FAFC))
                }
            }
        }
    }
}

private fun DrawScope.musicNote(color: Color) {
    drawPath(Path().apply {
        moveTo(12f, 2f); lineTo(15.1f, 2f)
        cubicTo(15.2f, 5.3f, 17.3f, 7.1f, 20.3f, 7.3f)
        lineTo(20.3f, 10.4f)
        cubicTo(18.3f, 10.4f, 16.6f, 9.7f, 15.2f, 8.7f)
        lineTo(15.2f, 16f)
        cubicTo(15.2f, 24.4f, 2.7f, 23.3f, 3.7f, 15.7f)
        cubicTo(4f, 12.4f, 7.3f, 10.8f, 10.2f, 11.7f)
        lineTo(10.2f, 15f)
        cubicTo(6.1f, 13.5f, 5.8f, 19.2f, 9.3f, 18.8f)
        cubicTo(11.6f, 18.8f, 12f, 17.2f, 12f, 15.4f)
        close()
    }, color)
}
