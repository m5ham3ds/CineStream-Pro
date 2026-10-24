package com.example.domain.models

import androidx.compose.runtime.Immutable

@Immutable
data class VideoStream(
    val serverName: String,
    val quality: VideoQuality,
    val url: String, // The actual video or download URL
    val type: StreamType = StreamType.VIDEO // HLS, MP4, etc.
)

enum class VideoQuality(val resolution: Int, val displayName: String) {
    Q_4K(2160, "4K (Ultra HD)"),
    Q_1080(1080, "1080p (FHD)"),
    Q_720(720, "720p (HD)"),
    Q_480(480, "480p (SD)"),
    Q_360(360, "360p (Low)"),
    Q_240(240, "240p (Very Low)"),
    UNKNOWN(0, "Unknown");

    companion object {
        fun fromString(res: String): VideoQuality {
            return when {
                res.contains("2160") || res.contains("4k") || res.contains("4K") -> Q_4K
                res.contains("1080") -> Q_1080
                res.contains("720") -> Q_720
                res.contains("480") -> Q_480
                res.contains("360") -> Q_360
                res.contains("240") -> Q_240
                else -> UNKNOWN
            }
        }
    }
}

enum class StreamType {
    VIDEO, // direct mp4/mkv
    HLS, // .m3u8 playlist
    EMBED // iframe url
}