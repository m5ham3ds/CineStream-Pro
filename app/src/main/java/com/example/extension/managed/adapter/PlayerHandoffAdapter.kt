package com.example.extension.managed.adapter

import com.example.extension.managed.model.MediaVariant
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.QualitySource
import com.example.extension.managed.model.StreamProtocol

/**
 * Normalized player input model.
 * The media player consumes only this model without knowing anything about scrapers, WebViews, or session credentials.
 */
data class PlayerInput(
    val mediaUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val qualities: List<QualitySource> = emptyList(),
    val variants: List<MediaVariant> = emptyList(),
    val serverName: String = "",
    val websiteName: String = "",
    val protocol: StreamProtocol = StreamProtocol.UNKNOWN
)

/**
 * Adapter converting normalized Managed Extension results into PlayerInput.
 */
object PlayerHandoffAdapter {

    fun toPlayerInput(
        playbackSource: PlaybackSource,
        serverName: String = "",
        websiteName: String = ""
    ): PlayerInput {
        return PlayerInput(
            mediaUrl = playbackSource.streamUrl,
            headers = playbackSource.headers,
            mimeType = playbackSource.mimeType,
            qualities = playbackSource.qualities,
            variants = playbackSource.variants,
            serverName = serverName,
            websiteName = websiteName,
            protocol = playbackSource.protocol
        )
    }
}
