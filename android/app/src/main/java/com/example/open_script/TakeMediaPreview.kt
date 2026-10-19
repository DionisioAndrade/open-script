package com.example.open_script

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.open_script.data.RecordedTake
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val PreviewNavy = Color(0xFF0F172A)
private val PreviewPanel = Color(0xFF162036)
private val PreviewWhite = Color(0xFFF8FAFC)

/** The caller supplies the frame's aspect ratio and clips its outer card corners. */
@Composable
internal fun TakeMediaPreview(
    take: RecordedTake,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inspecting = LocalInspectionMode.current
    val disposed = remember(take.path, take.createdAt, inspecting) { AtomicBoolean(false) }
    DisposableEffect(disposed) {
        onDispose { disposed.set(true) }
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, disposed) {
        value = null
        if (inspecting) return@produceState
        var candidate: Bitmap? = null
        try {
            // Keep ownership outside withContext: cancellation can occur after decoding,
            // before dispatch back to the UI, and that candidate must still be recycled.
            withContext(Dispatchers.IO) { candidate = readTakePreview(take.path) }
            ensureActive()
            if (!disposed.get()) {
                value = candidate
                // Compose/RenderThread owns a displayed bitmap. Let GC release it safely.
                candidate = null
            }
        } finally {
            candidate?.let { if (!it.isRecycled) it.recycle() }
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val label = "Reproduzir ${takeDisplayTitle(take)}, duração ${takePreviewDuration(take.durationMs)}"
    Box(
        modifier
            .background(Brush.linearGradient(listOf(PreviewPanel, PreviewNavy)))
            .clickable(role = Role.Button, onClick = onPlay)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            // A finished fallback also covers unreadable/missing files; no endless spinner.
            FilmFrame(Modifier.size(112.dp))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Transparent,
            PreviewNavy.copy(alpha = 0.12f),
            PreviewNavy.copy(alpha = 0.7f),
        ))))
        Box(
            Modifier.size(56.dp).background(PreviewNavy.copy(alpha = 0.76f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val triangle = Path().apply {
                    moveTo(size.width * 0.24f, size.height * 0.12f)
                    lineTo(size.width * 0.87f, size.height * 0.5f)
                    lineTo(size.width * 0.24f, size.height * 0.88f)
                    close()
                }
                drawPath(triangle, PreviewWhite)
            }
        }
        Text(
            takePreviewDuration(take.durationMs),
            color = PreviewWhite,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                .background(PreviewNavy.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp).clearAndSetSemantics { },
        )
    }
}

@Composable
private fun FilmFrame(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.4.dp.toPx()
        val tint = Color(0xFFB6C2D5).copy(alpha = 0.28f)
        drawRoundRect(
            tint,
            topLeft = Offset(size.width * 0.08f, size.height * 0.15f),
            size = Size(size.width * 0.84f, size.height * 0.7f),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(stroke),
        )
        for (side in listOf(0.2f, 0.8f)) {
            drawLine(tint, Offset(size.width * side, size.height * 0.15f),
                Offset(size.width * side, size.height * 0.85f), stroke)
            for (row in 0..3) {
                val x = if (side < 0.5f) 0.12f else 0.84f
                val y = 0.23f + row * 0.16f
                drawRoundRect(tint, topLeft = Offset(size.width * x, size.height * y),
                    size = Size(size.width * 0.04f, size.height * 0.065f),
                    cornerRadius = CornerRadius(1.dp.toPx()))
            }
        }
    }
}

private fun readTakePreview(path: String): Bitmap? {
    val file = File(path)
    if (!file.isFile || file.length() == 0L) return null
    val retriever = MediaMetadataRetriever()
    var decoded: Bitmap? = null
    return try {
        retriever.setDataSource(file.absolutePath)
        decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Android preserves the source aspect ratio within this maximum frame size.
            retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
        } else {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        val frame = decoded ?: return null
        val longest = maxOf(frame.width, frame.height)
        if (longest <= 640) {
            decoded = null
            frame
        } else {
            val factor = 640f / longest
            Bitmap.createScaledBitmap(frame, (frame.width * factor).toInt().coerceAtLeast(1),
                (frame.height * factor).toInt().coerceAtLeast(1), true)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        decoded?.let { if (!it.isRecycled) it.recycle() }
        runCatching { retriever.release() }
    }
}

internal fun takePreviewDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds / 60L) % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds % 60L)
    else "%02d:%02d".format(Locale.ROOT, minutes, seconds % 60L)
}
