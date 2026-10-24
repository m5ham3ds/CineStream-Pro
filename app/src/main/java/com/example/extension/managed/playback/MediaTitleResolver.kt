package com.example.extension.managed.playback

import com.example.extension.managed.model.ManagedExtension

/**
 * Phase 05V: Shared Canonical Media Title Resolution.
 *
 * Solves the Title Language Mismatch where TMDB catalogs provide English titles
 * ("Attack on Titan", "Demon Slayer", "Solo Leveling") while Arabic providers
 * (WitAnime, Anime4Up, AnimeBlkom, EgyDead) index exclusively under localized Arabic names
 * ("هجوم العمالقة", "قاتل الشياطين", "سولو ليفلينج").
 *
 * Enforces Rule 06:
 * - When provider language is Arabic ("ar"), prioritize Arabic localized title first,
 *   with graceful fallback to primary, original, and year variants.
 * - When provider language is non-Arabic, prioritize primary English/standard title first.
 * - Zero hardcoded translation tables per scraper; fully shared across the canonical pipeline.
 */
object MediaTitleResolver {

    fun resolveSearchQueries(
        title: String,
        originalTitle: String? = null,
        arabicTitle: String? = null,
        year: String? = null,
        candidate: ManagedExtension
    ): List<String> {
        val cleanTitle = clean(title)
        val cleanOrig = originalTitle?.let { clean(it) }?.takeIf { it.isNotBlank() && it != cleanTitle }
        val cleanArabic = arabicTitle?.let { clean(it) }?.takeIf { it.isNotBlank() }
            ?: (if (hasArabic(cleanTitle)) cleanTitle else null)
            ?: (if (cleanOrig != null && hasArabic(cleanOrig)) cleanOrig else null)

        val cleanYear = year?.trim()?.takeIf { it.isNotBlank() && it != "0" }

        val queries = mutableListOf<String>()

        val isArabicProvider = candidate.language.equals("ar", ignoreCase = true) ||
                candidate.scraperKey.lowercase() in listOf("witanime", "anime4up", "animeblkom", "egydead")

        if (isArabicProvider) {
            // Priority 1: Arabic title (if available)
            if (!cleanArabic.isNullOrBlank()) {
                queries.add(cleanArabic)
                if (cleanYear != null) queries.add("$cleanArabic $cleanYear")
            }
            // Priority 2: Primary clean title
            if (cleanTitle.isNotBlank() && cleanTitle != cleanArabic) {
                queries.add(cleanTitle)
                if (cleanYear != null) queries.add("$cleanTitle $cleanYear")
            }
            // Priority 3: Original title
            if (!cleanOrig.isNullOrBlank() && cleanOrig != cleanArabic && cleanOrig != cleanTitle) {
                queries.add(cleanOrig)
                if (cleanYear != null) queries.add("$cleanOrig $cleanYear")
            }
        } else {
            // Non-Arabic provider: English/Primary title first
            if (cleanTitle.isNotBlank()) {
                queries.add(cleanTitle)
                if (cleanYear != null) queries.add("$cleanTitle $cleanYear")
            }
            if (!cleanOrig.isNullOrBlank() && cleanOrig != cleanTitle) {
                queries.add(cleanOrig)
                if (cleanYear != null) queries.add("$cleanOrig $cleanYear")
            }
            if (!cleanArabic.isNullOrBlank() && cleanArabic != cleanTitle) {
                queries.add(cleanArabic)
                if (cleanYear != null) queries.add("$cleanArabic $cleanYear")
            }
        }

        return queries.distinct().filter { it.isNotBlank() }
    }

    fun clean(text: String): String {
        return text.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun hasArabic(text: String): Boolean {
        return text.any { it in '\u0600'..'\u06FF' }
    }
}
