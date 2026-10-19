package com.example.open_script

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

internal enum class StudioControlIcon { Edit, Adjust, Play, Pause, SwitchCamera }

/** Decorative glyph: the parent action provides its text and accessibility label. */
@Composable
internal fun StudioControlGlyph(
    icon: StudioControlIcon,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(24.dp)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (icon) {
                StudioControlIcon.Edit -> {
                    drawPath(Path().apply {
                        moveTo(12.5f, 3.5f); lineTo(5.5f, 3.5f)
                        quadraticTo(3.5f, 3.5f, 3.5f, 5.5f)
                        lineTo(3.5f, 18.5f); quadraticTo(3.5f, 20.5f, 5.5f, 20.5f)
                        lineTo(17.5f, 20.5f)
                    }, color, style = stroke)
                    drawLine(color, Offset(6.8f, 9f), Offset(10f, 9f), 2f, StrokeCap.Round)
                    drawLine(color, Offset(6.8f, 13f), Offset(8.4f, 13f), 2f, StrokeCap.Round)
                    drawPath(Path().apply {
                        moveTo(10.3f, 17.1f); lineTo(11.2f, 13.1f); lineTo(19.2f, 5.1f)
                        quadraticTo(20.4f, 3.9f, 21.5f, 5.1f)
                        quadraticTo(22.6f, 6.3f, 21.5f, 7.4f)
                        lineTo(13.5f, 15.4f); close()
                        moveTo(17.7f, 6.6f); lineTo(20f, 8.9f)
                    }, color, style = stroke)
                }
                StudioControlIcon.Adjust -> {
                    listOf(5f to 8f, 12f to 16f, 19f to 10f).forEach { (y, knob) ->
                        drawLine(color, Offset(3f, y), Offset(knob - 2.5f, y), 2f, StrokeCap.Round)
                        drawLine(color, Offset(knob + 2.5f, y), Offset(21f, y), 2f, StrokeCap.Round)
                        drawCircle(color, 2.5f, Offset(knob, y), style = stroke)
                    }
                }
                StudioControlIcon.Play -> drawPath(Path().apply {
                    moveTo(8f, 4.7f); lineTo(19f, 12f); lineTo(8f, 19.3f); close()
                }, color, style = stroke)
                StudioControlIcon.Pause -> {
                    drawRoundRect(color, Offset(6f, 4.5f), Size(3.3f, 15f), CornerRadius(1f), style = stroke)
                    drawRoundRect(color, Offset(14.7f, 4.5f), Size(3.3f, 15f), CornerRadius(1f), style = stroke)
                }
                StudioControlIcon.SwitchCamera -> {
                    drawPath(Path().apply {
                        moveTo(8.1f, 6.5f); lineTo(9.5f, 4.2f); lineTo(14.5f, 4.2f); lineTo(15.9f, 6.5f)
                        lineTo(19.5f, 6.5f); quadraticTo(21.5f, 6.5f, 21.5f, 8.5f)
                        lineTo(21.5f, 18f); quadraticTo(21.5f, 20f, 19.5f, 20f)
                        lineTo(4.5f, 20f); quadraticTo(2.5f, 20f, 2.5f, 18f)
                        lineTo(2.5f, 8.5f); quadraticTo(2.5f, 6.5f, 4.5f, 6.5f); close()
                    }, color, style = stroke)
                    drawPath(Path().apply {
                        moveTo(7.6f, 12.3f)
                        cubicTo(8.1f, 9.3f, 13.2f, 8.5f, 15.7f, 11f)
                        lineTo(16.2f, 11.7f)
                        moveTo(13.5f, 11.7f); lineTo(16.2f, 11.7f); lineTo(16.2f, 9.1f)
                        moveTo(16.4f, 14.1f)
                        cubicTo(15.9f, 17.1f, 10.8f, 17.9f, 8.3f, 15.4f)
                        lineTo(7.8f, 14.7f)
                        moveTo(10.5f, 14.7f); lineTo(7.8f, 14.7f); lineTo(7.8f, 17.3f)
                    }, color, style = stroke)
                }
            }
        }
    }
}
