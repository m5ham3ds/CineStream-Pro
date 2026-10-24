package com.example.data.model

data class P2PTransferRecord(
    val id: String,
    val mediaId: String,
    val title: String,
    val posterUrl: String,
    val isMovie: Boolean,
    val isReceived: Boolean, // true = received, false = sent
    val timestamp: Long,
    val deviceName: String = "",
    val quality: String = "",
    val contentType: String = "" // "movie", "series", "anime"
) {
    fun getContentCategory(): String {
        val lowerType = contentType.lowercase()
        if (lowerType.contains("anime")) return "anime"
        if (lowerType.contains("series") || lowerType.contains("tv") || lowerType.contains("show")) return "series"
        if (lowerType.contains("movie") || lowerType.contains("film")) return "movie"

        val lowerTitle = title.lowercase()
        val isAnimeHeuristic = lowerTitle.contains("anime") ||
                lowerTitle.contains("naruto") ||
                lowerTitle.contains("one piece") ||
                lowerTitle.contains("bleach") ||
                lowerTitle.contains("attack on titan") ||
                lowerTitle.contains("jujutsu") ||
                lowerTitle.contains("demon slayer") ||
                lowerTitle.contains("dragon ball") ||
                lowerTitle.contains("death note") ||
                lowerTitle.contains("hunter x hunter") ||
                lowerTitle.contains("tokyo ghoul") ||
                lowerTitle.contains("my hero academia") ||
                lowerTitle.contains("chainsaw man") ||
                lowerTitle.contains("solo leveling") ||
                lowerTitle.contains("boruto") ||
                lowerTitle.contains("haikyuu") ||
                lowerTitle.contains("black clover") ||
                id.startsWith("anime_") ||
                mediaId.startsWith("anime_")

        if (isAnimeHeuristic) return "anime"
        if (isMovie) return "movie"
        return "series"
    }

    fun getDisplayCategory(): String {
        return when (getContentCategory()) {
            "anime" -> "Anime"
            "series" -> "TV Series"
            else -> "Movies"
        }
    }
}
