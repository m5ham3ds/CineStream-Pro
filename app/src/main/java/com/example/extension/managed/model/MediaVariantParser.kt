package com.example.extension.managed.model

import com.example.utils.M3U8Parser

/**
 * Standard utility mapping M3U8Parser output to normalized MediaVariant instances.
 * Guarantees zero artificial resolution defaults ("Auto" or exact parsed height only).
 */
object MediaVariantParser {

    fun fromQualityInfo(
        info: M3U8Parser.QualityInfo,
        protocol: StreamProtocol = StreamProtocol.HLS,
        headers: Map<String, String> = emptyMap()
    ): MediaVariant {
        val isAuto = isAutoQualityToken(info.name)
        val canonical = if (isAuto) "Auto" else (normalizeCanonicalQualityName(info.name) ?: info.name)
        val height = info.height ?: canonical.replace(Regex("[^0-9]"), "").toIntOrNull()
        return MediaVariant(
            url = info.url,
            height = height,
            label = canonical,
            mimeType = if (protocol == StreamProtocol.HLS) "application/x-mpegURL" else "video/mp4",
            protocol = protocol,
            headers = headers,
            isDefault = isAuto
        )
    }

    fun fromQualityInfoList(
        infos: List<M3U8Parser.QualityInfo>,
        protocol: StreamProtocol = StreamProtocol.HLS,
        headers: Map<String, String> = emptyMap()
    ): List<MediaVariant> {
        return infos.map { fromQualityInfo(it, protocol, headers) }
    }
}
