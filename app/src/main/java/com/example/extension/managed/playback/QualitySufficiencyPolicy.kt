package com.example.extension.managed.playback

/**
 * Evaluates whether an accumulated set of discovered media qualities satisfies
 * the quality sufficiency policy, allowing early termination of further background
 * extension searches and server extractions.
 *
 * Quality Tiers:
 * - LOW: 144p, 240p, 360p, 480p
 * - MEDIUM: 720p
 * - HIGH: 1080p, 1440p, 2160p / 4K
 */
class QualitySufficiencyPolicy(
    val requireHigh: Boolean = true,
    val requireMedium: Boolean = true
) {
    enum class QualityTier {
        LOW,
        MEDIUM,
        HIGH,
        AUTO
    }

    companion object {
        fun classifyQuality(rawName: String?): QualityTier {
            if (rawName.isNullOrBlank()) return QualityTier.AUTO
            val lower = rawName.lowercase().trim()
            if (lower.contains("1080") || lower.contains("fhd") || lower.contains("4k") || lower.contains("2160")) {
                return QualityTier.HIGH
            }
            if (lower.contains("720") || lower.contains("hd")) {
                return QualityTier.MEDIUM
            }
            if (lower.contains("480") || lower.contains("360") || lower.contains("240") || lower.contains("144") || lower.contains("sd")) {
                return QualityTier.LOW
            }
            return QualityTier.AUTO
        }
    }

    /**
     * Determines whether the given quality labels satisfy sufficiency.
     * Sufficiency is met if:
     * - We have both HIGH and (MEDIUM or LOW), OR
     * - We have at least 2 distinct non-auto standard tiers, OR
     * - We have HIGH and medium is not strictly required.
     */
    fun isSufficient(qualities: Collection<String>): Boolean {
        if (qualities.isEmpty()) return false
        val tiers = qualities.map { classifyQuality(it) }.filter { it != QualityTier.AUTO }.toSet()

        val hasHigh = tiers.contains(QualityTier.HIGH)
        val hasMedium = tiers.contains(QualityTier.MEDIUM)
        val hasLow = tiers.contains(QualityTier.LOW)

        if (hasHigh && (hasMedium || hasLow)) return true
        if (hasHigh && !requireMedium) return true
        if (hasMedium && hasLow && !requireHigh) return true
        if (tiers.size >= 2 && hasHigh) return true

        return false
    }
}
