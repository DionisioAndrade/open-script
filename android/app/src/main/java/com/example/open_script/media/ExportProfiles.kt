package com.example.open_script.media

import android.content.Context
import org.json.JSONObject
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
)

data class ExportDimensions(val width: Int, val height: Int)

data class ExportSettings(
    val dimensions: ExportDimensions,
    val frameRate: Int,
    val videoBitsPerSecond: Int,
    val audioBitsPerSecond: Int,
    val limitedBySource: Boolean,
)

/** Social limits are advice for the person publishing, never recording/export cutoffs. */
object ExportProfiles {
    val resolutions: List<Int> = listOf(360, 720, 1080)
    val aspects: List<String> = listOf("9:16", "4:5", "1:1", "16:9", "2:3")

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
    fun settings(profile: ExportProfile, inputWidth: Int, inputHeight: Int): ExportSettings {
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
        return ExportSettings(
            dimensions = output,
            frameRate = normalizedFrameRate(profile.frameRate),
            videoBitsPerSecond = (baseRate * pixelRatio).roundToInt().coerceAtLeast(128_000),
            audioBitsPerSecond = currentRates.audio.getValue(quality),
            limitedBySource = output != requested,
        )
    }

    private fun normalizedResolution(value: Int): Int = value.takeIf { it in resolutions } ?: 720

    private fun normalizedFrameRate(value: Int): Int = if (value == 24) 24 else 30

    private fun evenFloor(value: Double): Int = (floor(value / 2).toInt() * 2).coerceAtLeast(2)
}
