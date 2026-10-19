package com.example.open_script.media

import android.content.Context
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

data class ExportProfile(
    val id: String,
    val label: String,
    val aspect: String = "9:16",
    val resolution: Int = 1080,
    val quality: String = "balanced",
    val frameRate: Int = 30,
    val note: String = "",
    val advice: String = "",
    val format: String = "mp4",
)

data class ExportDimensions(val width: Int, val height: Int)

/** Container and its actual encoding, kept together so filenames never mislabel the media. */
data class ExportFormat(
    val id: String,
    val label: String,
    val extension: String,
    val mimeType: String,
    val videoMimeType: String,
    val audioMimeType: String,
) {
    fun acceptsCodecs(video: String?, audio: String?): Boolean =
        (video == null || video == videoMimeType) && (audio == null || audio == audioMimeType)
}

data class ExportSettings(
    val dimensions: ExportDimensions,
    val frameRate: Int,
    val videoBitsPerSecond: Int,
    val audioBitsPerSecond: Int,
    val limitedBySource: Boolean,
    val format: ExportFormat = ExportProfiles.formats.first(),
    val frameRateLimitedBySource: Boolean = false,
)

/** Social limits are advice for the person publishing, never recording/export cutoffs. */
object ExportProfiles {
    val resolutions: List<Int> = listOf(360, 720, 1080)
    val aspects: List<String> = listOf("9:16", "4:5", "1:1", "16:9", "2:3")
    val frameRates: List<Int> = listOf(24, 30, 60)
    val formats: List<ExportFormat> = listOf(
        ExportFormat("mp4", "MP4", "mp4", "video/mp4", "video/avc", "audio/mp4a-latm"),
        ExportFormat("webm", "WebM", "webm", "video/webm", "video/x-vnd.on2.vp8", "audio/opus"),
    )

    /** An unknown filename must not be advertised as a known video container. */
    fun formatForFileName(name: String): ExportFormat? = formats.firstOrNull {
        name.substringAfterLast('.', "").equals(it.extension, ignoreCase = true)
    }

    /** Unlike cosmetic settings, an invalid container cannot silently change to another format. */
    fun format(id: String): ExportFormat = formats.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Escolha MP4 ou WebM para exportar o vídeo.")

    private data class Rates(
        val video: Map<Int, Map<String, Int>>,
        val audio: Map<String, Int>,
    )

    // Also lets the pure export calculations work before an Android context exists.
    // load() replaces these defaults with the shared catalog bundled by Gradle.
    @Volatile
    private var rates = Rates(
        video = mapOf(
            360 to mapOf("compact" to 600_000, "balanced" to 1_200_000, "high" to 2_000_000),
            720 to mapOf("compact" to 2_000_000, "balanced" to 4_000_000, "high" to 6_000_000),
            1080 to mapOf("compact" to 4_000_000, "balanced" to 8_000_000, "high" to 10_000_000),
        ),
        audio = mapOf("compact" to 64_000, "balanced" to 128_000, "high" to 192_000),
    )

    fun load(context: Context): List<ExportProfile> {
        val catalog = context.assets.open("export-catalog.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val qualities = listOf("compact", "balanced", "high")
        val videoRates = catalog.getJSONObject("bitrates")
        val audioRates = catalog.getJSONObject("audioBitrates")
        rates = Rates(
            video = resolutions.associateWith { resolution ->
                val values = videoRates.getJSONObject(resolution.toString())
                qualities.associateWith { quality -> values.getInt(quality).also { require(it > 0) } }
            },
            audio = qualities.associateWith { quality -> audioRates.getInt(quality).also { require(it > 0) } },
        )
        val items = catalog.getJSONArray("profiles")
        val profiles = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val suggestions = buildList {
                item.optString("advice").takeIf { it.isNotBlank() }?.let(::add)
                item.optString("limitLabel").takeIf { it.isNotBlank() }?.let {
                    add("Duração sugerida para publicação: até $it. O vídeo será exportado inteiro.")
                }
                item.optString("minimumLabel").takeIf { it.isNotBlank() }?.let {
                    add("Duração mínima sugerida para publicação: $it.")
                }
            }
            ExportProfile(
                id = item.getString("id"),
                label = item.getString("label"),
                aspect = item.optString("aspect", "9:16").takeIf { it in aspects } ?: "9:16",
                resolution = normalizedResolution(item.optInt("resolution", 1080)),
                quality = item.optString("quality", "balanced").takeIf { it in qualities } ?: "balanced",
                frameRate = normalizedFrameRate(item.optInt("frameRate", 30)),
                note = item.optString("note"),
                advice = suggestions.joinToString(" "),
                format = item.optString("format", "mp4").takeIf { id -> formats.any { it.id == id } } ?: "mp4",
            )
        }
        return if (profiles.any { it.id == "custom" }) profiles else {
            listOf(ExportProfile("custom", "Personalizado", note = "Ajuste o formato e a qualidade.")) + profiles
        }
    }

    /** Resolution is the short edge, as in the shared web export catalog. */
    fun dimensions(aspect: String, resolution: Int): ExportDimensions {
        val shortSide = normalizedResolution(resolution)
        val (horizontal, vertical) = when (aspect) {
            "4:5" -> 4 to 5
            "1:1" -> 1 to 1
            "16:9" -> 16 to 9
            "2:3" -> 2 to 3
            else -> 9 to 16
        }
        return if (horizontal > vertical) {
            ExportDimensions(evenFloor(shortSide.toDouble() * horizontal / vertical), shortSide)
        } else {
            ExportDimensions(shortSide, evenFloor(shortSide.toDouble() * vertical / horizontal))
        }
    }

    /** Never increase the source's short edge; padding keeps the complete camera frame. */
    fun settings(
        profile: ExportProfile,
        inputWidth: Int,
        inputHeight: Int,
        inputFrameRate: Float? = null,
    ): ExportSettings {
        require(inputWidth >= 2 && inputHeight >= 2) { "O vídeo não tem dimensões válidas." }
        val requested = dimensions(profile.aspect, profile.resolution)
        val sourceShortSide = min(inputWidth, inputHeight)
        val scale = min(1.0, sourceShortSide.toDouble() / min(requested.width, requested.height))
        val output = ExportDimensions(
            evenFloor(requested.width * scale),
            evenFloor(requested.height * scale),
        )
        val resolution = normalizedResolution(profile.resolution)
        val quality = profile.quality.takeIf { it in rates.audio } ?: "balanced"
        val currentRates = rates
        val baseRate = currentRates.video.getValue(resolution).getValue(quality)
        val referencePixels = resolution.toDouble() * resolution * 16.0 / 9.0
        val pixelRatio = output.width.toDouble() * output.height / referencePixels
        val requestedFrameRate = normalizedFrameRate(profile.frameRate)
        val sourceFrameRate = inputFrameRate?.takeIf { it.isFinite() && it > 0f }
        // Media3 accepts an integer maximum. Rounding UP a fractional source rate (29.97)
        // preserves its frames; EditedMediaItem and FrameDropEffect never duplicate them.
        val frameRate = sourceFrameRate?.let { ceil(min(requestedFrameRate.toFloat(), it).toDouble()).toInt() }
            ?: requestedFrameRate
        return ExportSettings(
            dimensions = output,
            frameRate = frameRate.coerceAtLeast(1),
            videoBitsPerSecond = (baseRate * pixelRatio * maxOf(1.0, frameRate / 30.0))
                .roundToInt().coerceAtLeast(128_000),
            audioBitsPerSecond = currentRates.audio.getValue(quality),
            limitedBySource = output != requested,
            format = format(profile.format),
            frameRateLimitedBySource = sourceFrameRate != null && sourceFrameRate < requestedFrameRate,
        )
    }

    private fun normalizedResolution(value: Int): Int = value.takeIf { it in resolutions } ?: 720

    private fun normalizedFrameRate(value: Int): Int = value.takeIf { it in frameRates } ?: 30

    private fun evenFloor(value: Double): Int = (floor(value / 2).toInt() * 2).coerceAtLeast(2)
}
