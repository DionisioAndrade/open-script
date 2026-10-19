package com.example.open_script.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test(expected = IllegalArgumentException::class)
    fun videoWithoutUsableDimensionsCannotStartAnExport() {
        ExportProfiles.settings(ExportProfile("test", "Teste"), 0, 1920)
    }
}
