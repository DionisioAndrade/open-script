package com.example.open_script

import com.example.open_script.media.ExportProfile
import java.util.Locale

internal data class ExportDestinationGroup(
    val id: String,
    val label: String,
    val profiles: List<ExportProfile>,
)

/** Group presentation only: the original profiles, settings and catalog order stay intact. */
internal fun groupExportDestinations(profiles: List<ExportProfile>): List<ExportDestinationGroup> {
    val brands = linkedMapOf(
        "instagram" to "Instagram",
        "tiktok" to "TikTok",
        "youtube" to "YouTube",
        "facebook" to "Facebook",
        "linkedin" to "LinkedIn",
        "x" to "X",
        "whatsapp" to "WhatsApp",
        "pinterest" to "Pinterest",
        "kwai" to "Kwai",
    )
    return profiles.filterNot { it.id == "custom" }.groupBy { profile ->
        val id = profile.id.lowercase(Locale.ROOT)
        brands.keys.firstOrNull { id == it || id.startsWith("$it-") } ?: "other:${profile.id}"
    }.map { (brand, members) ->
        ExportDestinationGroup(brand, brands[brand] ?: members.first().label, members)
    }
}
