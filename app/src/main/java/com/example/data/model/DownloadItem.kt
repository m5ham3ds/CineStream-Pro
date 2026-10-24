package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_items")
data class DownloadItem(
    @PrimaryKey val id: String = "",
    val mediaId: String = "",
    val title: String = "",
    val posterUrl: String = "",
    val isMovie: Boolean = true,
    val quality: String = "",
    val progress: Float = 0f,
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val fileSizeBytes: Long = 0L,
    val variantMode: String = "subtitled",
    val audioLanguage: String = "ar",
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val providerId: String = ""
) {
    val cleanQuality: String
        get() = cleanQualityName(quality)

    companion object {
        fun cleanQualityName(rawQuality: String?): String {
            if (rawQuality.isNullOrBlank()) return "Auto"
            val trimmed = if (rawQuality.contains("||")) {
                rawQuality.substringBefore("||").trim()
            } else {
                rawQuality.trim()
            }
            if (com.example.ui.screens.player.isAutoQuality(trimmed)) {
                return "Auto"
            }
            val canonical = com.example.ui.screens.player.normalizeCanonicalQualityName(trimmed)
            if (canonical != null) {
                return canonical
            }
            return "Auto"
        }
    }
}
