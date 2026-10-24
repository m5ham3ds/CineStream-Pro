package com.example.extension.managed.model

import com.example.utils.M3U8Parser

/**
 * Quality evidence classification enforcing strict evidence-based quality representation.
 */
enum class QualityEvidence {
    RESOLUTION,
    EXPLICIT_LABEL,
    OTHER_VERIFIED
}

/**
 * Internal representation of a candidate stream quality discovered from a specific server.
 * Preserves all source provenance (serverId, serverName, sourceUrl, headers, evidence)
 * to support robust multi-server fallback without exposing duplicates to the user.
 */
data class QualityCandidate(
    val qualityKey: String,
    val label: String,
    val width: Int? = null,
    val height: Int? = null,
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val serverId: String? = null,
    val serverName: String? = null,
    val sourceUrl: String? = null,
    val evidence: QualityEvidence = QualityEvidence.RESOLUTION
)

/**
 * Aggregated representation of a canonical quality level.
 * Contains the chosen primary candidate for playback and all alternate fallback candidates
 * from other discovered servers offering the same resolution.
 */
data class AggregatedQuality(
    val qualityKey: String,
    val label: String,
    val primaryCandidate: QualityCandidate,
    val fallbackCandidates: List<QualityCandidate> = emptyList(),
    val width: Int? = primaryCandidate.width,
    val height: Int? = primaryCandidate.height
) {
    val allCandidates: List<QualityCandidate>
        get() = listOf(primaryCandidate) + fallbackCandidates
}

/**
 * Central Quality Aggregator.
 * Responsible for deduplicating, grouping, and ordering discovered stream qualities
 * across all participating servers into a canonical descending hierarchy.
 */
object QualityAggregator {

    private val STANDARD_ORDER = listOf(
        "1080", "720", "480", "360", "240", "144"
    )

    fun aggregate(
        candidates: List<QualityCandidate>
    ): List<AggregatedQuality> {
        val valid = candidates.filter { it.qualityKey in STANDARD_ORDER }
        val grouped = valid.groupBy { it.qualityKey }

        val aggregatedList = mutableListOf<AggregatedQuality>()
        for ((key, list) in grouped) {
            val primary = list.first()
            val fallbacks = list.drop(1)
            aggregatedList.add(
                AggregatedQuality(
                    qualityKey = key,
                    label = primary.label,
                    primaryCandidate = primary,
                    fallbackCandidates = fallbacks,
                    width = primary.width,
                    height = primary.height
                )
            )
        }

        // Sort descending: 4320p -> 2160p -> 1440p -> 1080p -> 720p -> 480p -> 360p ...
        return aggregatedList.sortedWith(Comparator { a, b ->
            val idxA = STANDARD_ORDER.indexOf(a.qualityKey).let { if (it == -1) 999 else it }
            val idxB = STANDARD_ORDER.indexOf(b.qualityKey).let { if (it == -1) 999 else it }
            idxA.compareTo(idxB)
        })
    }

    fun toQualityInfoList(
        aggregated: List<AggregatedQuality>,
        defaultStreamUrl: String? = null,
        defaultHeaders: Map<String, String> = emptyMap()
    ): List<M3U8Parser.QualityInfo> {
        val result = mutableListOf<M3U8Parser.QualityInfo>()
        for (item in aggregated) {
            val canonicalName = if (isAutoQualityToken(item.label)) "Auto" else (normalizeCanonicalQualityName(item.label) ?: continue)
            if (result.none { it.name == canonicalName }) {
                result.add(
                    M3U8Parser.QualityInfo(
                        name = canonicalName,
                        url = item.primaryCandidate.streamUrl,
                        width = item.width,
                        height = item.height,
                        headers = item.primaryCandidate.headers,
                        serverId = item.primaryCandidate.serverId,
                        serverName = item.primaryCandidate.serverName
                    )
                )
            }
        }

        // Auto is appended at the end if explicit qualities exist and not yet present
        if (result.none { isAutoQualityToken(it.name) }) {
            val autoUrl = defaultStreamUrl ?: aggregated.firstOrNull()?.primaryCandidate?.streamUrl.orEmpty()
            result.add(
                M3U8Parser.QualityInfo(
                    name = "Auto",
                    url = autoUrl,
                    headers = defaultHeaders
                )
            )
        }
        return result
    }
}

fun isAutoQualityToken(rawName: String?): Boolean {
    if (rawName.isNullOrBlank()) return true
    val trimmed = rawName.trim()
    val lower = trimmed.lowercase()

    // 1. If it contains an explicit standard numeric resolution, prioritize resolution
    val hasStandardRes = Regex("""(?i)\b(1080|720|480|360|240|144)p?\b""").containsMatchIn(lower) ||
        Regex("""(?i)(?:^|[\s_\-/\.x])(1080|720|480|360|240|144)p?(?:[\s_\-/\.]|$)""").containsMatchIn(lower) ||
        lower.contains("1920x1080") || lower.contains("1280x720") || lower.contains("854x480") || lower.contains("640x360") ||
        Regex("""(?i)\b(fhd|4k|2160|1440|2k)\b""").containsMatchIn(lower)

    if (hasStandardRes) {
        return false
    }

    // 2. Fast path exact matches & common Auto / AutoP variations
    if (lower == "auto" || lower == "autop" || lower == "auto p" || lower == "auto-p" || 
        lower == "auto_p" || lower == "auto.p" || lower == "auto(p)" || lower == "auto (p)" || lower == "auto [p]" ||
        lower == "autop." || lower == "autop-" || lower == "autop_" || lower == "(autop)" || lower == "[autop]" ||
        lower == "automatic" || lower == "تلقائي" || lower == "تلقائى" || lower == "الجودة التلقائية" ||
        lower == "auto quality" || lower == "default" || lower == "adaptive") {
        return true
    }

    // 3. Catch all AutoP variants without standard resolution (e.g. "AutoP", "auto p", "autop", etc.)
    if (lower.contains("autop") || lower.contains("auto p") || lower.contains("auto-p") || lower.contains("auto_p") || lower.contains("auto.p")) {
        return true
    }

    // 4. Check for auto / autop / تلقائي tokens
    val stripped = lower.replace(Regex("""[\[\]\(\)\{\}\"\'\-_:;|/\\]"""), " ").trim()
    val tokens = stripped.split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (tokens.any { it == "auto" || it == "autop" || it == "automatic" || it == "تلقائي" || it == "تلقائى" || it == "default" || it == "adaptive" }) {
        return true
    }

    return lower.startsWith("auto")
}

fun normalizeQualityKey(rawName: String?): String? {
    if (rawName.isNullOrBlank()) return null
    if (isAutoQualityToken(rawName)) return null
    val trimmed = rawName.trim()
    val lower = trimmed.lowercase()

    // 1. Explicitly reject Auto variants and arbitrary/non-evidence descriptors
    if (lower in listOf("high", "best", "medium", "low", "hd1", "quality-1", "quality-2", "unknown", "custom", "default", "none") ||
        (lower.startsWith("server") && !lower.contains("4k") && !lower.contains("2160") && !lower.contains("1440") && !lower.contains("1080") && !lower.contains("720") && !lower.contains("480") && !lower.contains("360") && !lower.contains("240") && !lower.contains("144")) ||
        lower.contains("quality-") ||
        lower.matches(Regex("""hd\d+""")) ||
        lower.endsWith("-custom")
    ) {
        return null
    }

    // 2. Resolution attribute matching (e.g. RESOLUTION=3840x2160 or 1920x1080)
    val resMatch = Regex("""(?i)(?:resolution\s*=\s*)?(\d{3,4})\s*x\s*(\d{3,4})""").find(trimmed)
    if (resMatch != null) {
        val h = resMatch.groupValues[2].toIntOrNull()
            ?: resMatch.groupValues[1].toIntOrNull()
        if (h != null) {
            return when {
                h >= 2000 -> "4K"
                h in 1300..1999 && h !in 900..1299 -> "1440"
                h in 900..1299 -> "1080"
                h in 600..899 -> "720"
                h in 400..599 -> "480"
                h in 300..399 -> "360"
                h in 200..299 -> "240"
                h in 100..199 -> "144"
                else -> null
            }
        }
    }

    // 3. Exact standard primary resolution tokens (4K, 2160, 1440, 1080, 720, 480, 360, 240, 144) with word boundaries
    val pMatch = Regex("""(?i)\b(4k|2160|1440|1080|720|480|360|240|144)p?\b""").find(trimmed)
    if (pMatch != null) {
        val raw = pMatch.groupValues[1].lowercase()
        return when (raw) {
            "4k", "2160" -> "4K"
            "1440" -> "1440"
            else -> raw
        }
    }

    // 4. Delimiter-bounded resolution matching (e.g., _720p_, -1080p., /480/, _4k_)
    val delimMatch = Regex("""(?i)(?:^|[\s_\-/\.x])(4k|2160|1440|1080|720|480|360|240|144)p?(?:[\s_\-/\.]|$)""").find(trimmed)
    if (delimMatch != null) {
        val raw = delimMatch.groupValues[1].lowercase()
        return when (raw) {
            "4k", "2160" -> "4K"
            "1440" -> "1440"
            else -> raw
        }
    }

    // 5. Arbitrary \d{3,4}p format mapped to standard tiers
    val arbMatch = Regex("""(?i)\b(\d{3,4})p\b""").find(trimmed)
    if (arbMatch != null) {
        val num = arbMatch.groupValues[1].toIntOrNull()
        if (num != null) {
            return when {
                num >= 2000 -> "4K"
                num in 1300..1999 && num !in 900..1299 -> "1440"
                num in 900..1299 -> "1080"
                num in 600..899 -> "720"
                num in 400..599 -> "480"
                num in 300..399 -> "360"
                num in 200..299 -> "240"
                num in 100..199 -> "144"
                else -> null
            }
        }
    }

    // 6. Standard alias tokens with word boundaries (reject blind substring matches on URLs, timestamps, or hashes)
    return when {
        Regex("""(?i)\b(4k|2160|3840|uhd)\b""").containsMatchIn(lower) -> "4K"
        Regex("""(?i)\b(1440|2560|2k|qhd)\b""").containsMatchIn(lower) -> "1440"
        Regex("""(?i)\b(1080|1920|fhd)\b""").containsMatchIn(lower) -> "1080"
        Regex("""(?i)\b(720|1280)\b""").containsMatchIn(lower) -> "720"
        Regex("""(?i)\bhd\b""").containsMatchIn(lower) && !lower.contains("fhd") && !lower.contains("server") && !lower.contains("hd1") && !lower.contains("hd-") -> "720"
        Regex("""(?i)\b(480|854|576|sd)\b""").containsMatchIn(lower) -> "480"
        Regex("""(?i)\b(360|640)\b""").containsMatchIn(lower) -> "360"
        Regex("""(?i)\b(240|426)\b""").containsMatchIn(lower) -> "240"
        Regex("""(?i)\b(144|256)\b""").containsMatchIn(lower) -> "144"
        else -> null
    }
}

fun normalizeQualityLabel(rawName: String?): String? {
    if (rawName == null) return null
    if (isAutoQualityToken(rawName)) return "Auto"
    val key = normalizeQualityKey(rawName) ?: return null
    return if (key == "4K") "4K" else "${key}p"
}

fun normalizeCanonicalQualityName(rawName: String?): String? {
    if (rawName.isNullOrBlank()) return "Auto"
    if (isAutoQualityToken(rawName)) return "Auto"
    val key = normalizeQualityKey(rawName) ?: return null
    return when (key) {
        "4K", "2160" -> "4K"
        "1440" -> "1440p"
        "1080" -> "1080p"
        "720" -> "720p"
        "480" -> "480p"
        "360" -> "360p"
        "240" -> "240p"
        "144" -> "144p"
        else -> null
    }
}


