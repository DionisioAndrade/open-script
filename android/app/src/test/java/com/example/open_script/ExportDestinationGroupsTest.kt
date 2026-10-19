package com.example.open_script

import com.example.open_script.media.ExportProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDestinationGroupsTest {
    @Test
    fun `variants stay under their network with their complete original settings`() {
        val reels = ExportProfile("instagram-reels", "Instagram · Reels", aspect = "9:16", frameRate = 60)
        val feed = ExportProfile("instagram-feed", "Instagram · Feed", aspect = "4:5", quality = "high")
        val short = ExportProfile("youtube-shorts", "YouTube · Shorts", advice = "Preserve this advice")
        val video = ExportProfile("youtube-video", "YouTube · Vídeos", aspect = "16:9", format = "webm")
        val groups = groupExportDestinations(listOf(reels, short, feed, video))

        assertEquals(listOf("Instagram", "YouTube"), groups.map { it.label })
        assertEquals(listOf(reels, feed), groups[0].profiles)
        assertEquals(listOf(short, video), groups[1].profiles)
        assertSame(reels, groups[0].profiles[0])
        assertSame(feed, groups[0].profiles[1])
        assertSame(video, groups[1].profiles[1])
    }

    @Test
    fun `every social profile remains reachable once and custom stays separate`() {
        val ids = listOf("instagram-reels", "instagram-stories", "instagram-feed", "tiktok",
            "youtube-shorts", "youtube-video", "facebook-reels", "facebook-feed", "linkedin", "x",
            "whatsapp-status", "pinterest", "kwai")
        val profiles = listOf(ExportProfile("custom", "Personalizado")) + ids.map { ExportProfile(it, it) }
        val result = groupExportDestinations(profiles).flatMap { it.profiles }

        assertEquals(ids.sorted(), result.map { it.id }.sorted())
        assertEquals(ids.size, result.size)
        assertEquals(ids.size, result.map { it.id }.distinct().size)
        assertTrue(result.none { it.id == "custom" })
    }

    @Test
    fun `future destinations stay available without falling into unrelated brands`() {
        val future = ExportProfile("future-feed", "Future · Feed", resolution = 720)
        val unrelated = ExportProfile("xylophone-video", "Xylophone · Vídeo")
        val groups = groupExportDestinations(listOf(future, unrelated))

        assertEquals(listOf("Future · Feed", "Xylophone · Vídeo"), groups.map { it.label })
        assertEquals(listOf(future, unrelated), groups.flatMap { it.profiles })
        assertTrue(groups.none { it.id == "x" })
    }

    @Test
    fun `empty and custom-only catalogs do not invent social destinations`() {
        assertTrue(groupExportDestinations(emptyList()).isEmpty())
        assertTrue(groupExportDestinations(listOf(ExportProfile("custom", "Personalizado"))).isEmpty())
    }
}
