package com.example.open_script.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProfilesTest {
    @Test
    fun dimensionsUseTheShortEdgeForEverySupportedAspect() {
        assertEquals(ExportDimensions(1080, 1920), ExportProfiles.dimensions("9:16", 1080))
        assertEquals(ExportDimensions(1920, 1080), ExportProfiles.dimensions("16:9", 1080))
        assertEquals(ExportDimensions(1080, 1350), ExportProfiles.dimensions("4:5", 1080))
        assertEquals(ExportDimensions(1080, 1080), ExportProfiles.dimensions("1:1", 1080))
        assertEquals(ExportDimensions(1080, 1620), ExportProfiles.dimensions("2:3", 1080))
        assertEquals(ExportDimensions(360, 450), ExportProfiles.dimensions("4:5", 360))
    }

    @Test
    fun allCatalogDimensionsArePositiveEvenNumbers() {
        for (aspect in ExportProfiles.aspects) {
            for (resolution in ExportProfiles.resolutions) {
                val dimensions = ExportProfiles.dimensions(aspect, resolution)
                assertTrue(dimensions.width > 0 && dimensions.height > 0)
                assertEquals(0, dimensions.width % 2)
                assertEquals(0, dimensions.height % 2)
            }
        }
        assertEquals(ExportDimensions(720, 1280), ExportProfiles.dimensions("invalid", -1))
    }

    @Test
    fun requesting1080From720SourceDoesNotUpscaleItsImage() {
        val settings = ExportProfiles.settings(ExportProfile("test", "Teste"), 720, 1280)
        assertEquals(ExportDimensions(720, 1280), settings.dimensions)
        assertTrue(settings.limitedBySource)
        assertTrue(settings.videoBitsPerSecond < 8_000_000)
    }

    @Test
    fun landscapeAndPortraitHaveTheSameFullFrameBudget() {
        val landscape = ExportProfiles.settings(ExportProfile("test", "Teste", aspect = "16:9"), 1920, 1080)
        assertEquals(ExportDimensions(1920, 1080), landscape.dimensions)
        assertEquals(8_000_000, landscape.videoBitsPerSecond)
        assertFalse(landscape.limitedBySource)
        val square = ExportProfiles.settings(ExportProfile("test", "Teste", aspect = "1:1"), 1080, 1920)
        assertEquals(4_500_000, square.videoBitsPerSecond)
        assertEquals(ExportDimensions(1080, 1080), square.dimensions)
    }

    @Test
    fun smallerExportAndDifferentQualityKeepTheirOwnBitrate() {
        val compact = ExportProfiles.settings(
            ExportProfile("custom", "Personalizado", resolution = 720, quality = "compact", frameRate = 24),
            1080,
            1920,
        )
        assertEquals(ExportDimensions(720, 1280), compact.dimensions)
        assertEquals(2_000_000, compact.videoBitsPerSecond)
        assertEquals(64_000, compact.audioBitsPerSecond)
        assertEquals(24, compact.frameRate)
        assertFalse(compact.limitedBySource)
    }

    @Test
    fun oddSourceDimensionsAreRoundedDownAndNeverUpscaled() {
        val settings = ExportProfiles.settings(ExportProfile("test", "Teste"), 719, 1279)
        assertEquals(718, settings.dimensions.width)
        assertTrue(settings.dimensions.height <= 1279)
        assertEquals(0, settings.dimensions.height % 2)
        assertTrue(settings.limitedBySource)
    }

    @Test
    fun invalidFrameRateAndQualityFallBackToSupportedValues() {
        val settings = ExportProfiles.settings(
            ExportProfile("test", "Teste", quality = "invalid", frameRate = 120),
            1080,
            1920,
        )
        assertEquals(30, settings.frameRate)
        assertEquals(8_000_000, settings.videoBitsPerSecond)
        assertEquals(128_000, settings.audioBitsPerSecond)
    }

    @Test
    fun sixtyFpsIsAvailableOnlyWithinTheOriginalFrameBudget() {
        val profile = ExportProfile("custom", "Personalizada", frameRate = 60)
        val fullRate = ExportProfiles.settings(profile, 1080, 1920, inputFrameRate = 60f)
        assertEquals(60, fullRate.frameRate)
        assertEquals(16_000_000, fullRate.videoBitsPerSecond)
        assertFalse(fullRate.frameRateLimitedBySource)

        val sourceLimited = ExportProfiles.settings(profile, 1080, 1920, inputFrameRate = 30f)
        assertEquals(30, sourceLimited.frameRate)
        assertEquals(8_000_000, sourceLimited.videoBitsPerSecond)
        assertTrue(sourceLimited.frameRateLimitedBySource)

        val lowerRequested = ExportProfiles.settings(profile.copy(frameRate = 24), 1080, 1920, inputFrameRate = 60f)
        assertEquals(24, lowerRequested.frameRate)
        assertFalse(lowerRequested.frameRateLimitedBySource)
    }

    @Test
    fun fractionalAndUnknownSourceFrameRatesDoNotInventFramesOrRejectTheVideo() {
        val profile = ExportProfile("custom", "Personalizada", frameRate = 60)
        // Media3's integer ceiling must not turn 29.97 fps into 29 fps and drop extra frames.
        val fractional = ExportProfiles.settings(profile, 1080, 1920, inputFrameRate = 29.97f)
        assertEquals(30, fractional.frameRate)
        assertTrue(fractional.frameRateLimitedBySource)

        for (unknown in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            val settings = ExportProfiles.settings(profile, 1080, 1920, inputFrameRate = unknown)
            assertEquals(60, settings.frameRate)
            assertFalse(settings.frameRateLimitedBySource)
        }
    }

    @Test
    fun containersKeepTheirOwnExtensionAndCompatibleCodecs() {
        val mp4 = ExportProfiles.settings(ExportProfile("custom", "Personalizada"), 1080, 1920).format
        assertEquals("mp4", mp4.extension)
        assertEquals("video/mp4", mp4.mimeType)
        assertTrue(mp4.acceptsCodecs("video/avc", "audio/mp4a-latm"))
        assertTrue(mp4.acceptsCodecs("video/avc", null)) // A take may be silent.
        assertFalse(mp4.acceptsCodecs("video/hevc", "audio/mp4a-latm"))

        val webm = ExportProfiles.settings(
            ExportProfile("custom", "Personalizada", format = "webm"), 1080, 1920,
        ).format
        assertEquals("webm", webm.extension)
        assertEquals("video/webm", webm.mimeType)
        assertTrue(webm.acceptsCodecs("video/x-vnd.on2.vp8", "audio/opus"))
        assertFalse(webm.acceptsCodecs("video/avc", "audio/opus"))
        assertFalse(webm.acceptsCodecs("video/x-vnd.on2.vp8", "audio/mp4a-latm"))
    }

    @Test
    fun sharingUsesTheActualExtensionAndDoesNotGuessUnknownContainers() {
        assertEquals("video/mp4", ExportProfiles.formatForFileName("take.MP4")?.mimeType)
        assertEquals("video/webm", ExportProfiles.formatForFileName("openscript-123.webm")?.mimeType)
        assertNull(ExportProfiles.formatForFileName("take.mp4.mov"))
        assertNull(ExportProfiles.formatForFileName("take"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedContainerIsRejectedInsteadOfRenamingAnMp4() {
        ExportProfiles.settings(ExportProfile("custom", "Personalizada", format = "mov"), 1080, 1920)
    }

    @Test(expected = IllegalArgumentException::class)
    fun videoWithoutUsableDimensionsCannotStartAnExport() {
        ExportProfiles.settings(ExportProfile("test", "Teste"), 0, 1920)
    }
}
