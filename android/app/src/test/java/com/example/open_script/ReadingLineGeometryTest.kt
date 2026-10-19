package com.example.open_script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingLineGeometryTest {
    @Test fun wrappedLinesUseTheirActualBounds() {
        val lines = listOf(ReadingLine(0, 9, 0f, 30f), ReadingLine(9, 14, 30f, 80f), ReadingLine(14, 21, 80f, 105f))
        assertEquals(0, activeReadingLine(lines, 0f))
        assertEquals(0, activeReadingLine(lines, 14f))
        assertEquals(1, activeReadingLine(lines, 15f))
        assertEquals(1, activeReadingLine(lines, 64f))
        assertEquals(2, activeReadingLine(lines, 65f))
        assertEquals(2, activeReadingLine(lines, 1_000f))
        assertEquals(0, activeReadingLine(lines, -20f))
    }

    @Test fun emptyAndTrailingLinesRemainRealLines() {
        assertNull(activeReadingLine(emptyList(), 0f))
        val lines = listOf(ReadingLine(0, 1, 0f, 20f), ReadingLine(1, 2, 20f, 40f), ReadingLine(2, 2, 40f, 60f))
        assertEquals(1, activeReadingLine(lines, 20f))
        assertEquals(2, activeReadingLine(lines, 40f))
        assertEquals(2, lines.last().start)
        assertEquals(2, lines.last().end)
    }

    @Test fun finalLineCanReachTheGuideInATallViewport() {
        val lines = List(10) { ReadingLine(it, it + 1, it * 42f, (it + 1) * 42f) }
        val geometry = ReadingTextGeometry(420f, lines)
        val trailing = readingTrailingSpace(600f, 120f, 10f, geometry)
        assertEquals(418f, trailing, .001f)
        val finalScroll = 10f + 120f + geometry.height + trailing + 10f - 600f
        assertEquals(lines.last().center, lines.first().center + finalScroll, .001f)
        assertEquals(9, activeReadingLine(lines, finalScroll))
        // The previous fixed 65% end space stopped with the penultimate line at the guide.
        assertEquals(8, activeReadingLine(lines, 10f + 120f + geometry.height + 390f + 10f - 600f))
    }

    @Test fun sufficientOriginalEndSpaceIsPreserved() {
        val geometry = ReadingTextGeometry(200f, listOf(ReadingLine(0, 1, 0f, 100f), ReadingLine(1, 2, 100f, 200f)))
        assertEquals(195f, readingTrailingSpace(300f, 60f, 10f, geometry), .001f)
        assertEquals(390f, readingTrailingSpace(600f, 120f, 10f, null), .001f)
    }
}
