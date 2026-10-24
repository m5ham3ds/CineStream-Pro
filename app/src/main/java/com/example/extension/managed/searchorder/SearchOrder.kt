package com.example.extension.managed.searchorder

import com.example.domain.models.GroupStatus
import com.example.domain.models.VariantLanguage
import com.example.extension.managed.model.ContentType

/**
 * Canonical Search Order Model.
 *
 * Represents the configuration stored at `/config/search_order`:
 * - movie: List of extension IDs for Movies.
 * - tv: List of extension IDs for Television series.
 * - series: Compatibility alias for tv list.
 * - anime: List of extension IDs for Japanese Anime.
 * - arabicDubbed: List of extension IDs for Arabic Dubbed content.
 * - englishDubbed: List of extension IDs for English Dubbed content.
 * - arabicDubbedStatus: ACTIVE, MAINTENANCE, or DISABLED (Default: ACTIVE).
 * - englishDubbedStatus: ACTIVE, MAINTENANCE, or DISABLED (Default: MAINTENANCE).
 *
 * The Admin order is authoritative and deterministic.
 */
data class SearchOrder(
    val movie: List<String> = emptyList(),
    val tv: List<String>? = null,
    val series: List<String> = emptyList(),
    val anime: List<String> = emptyList(),
    val arabicDubbed: List<String> = emptyList(),
    val englishDubbed: List<String> = emptyList(),
    val arabicDubbedStatus: GroupStatus = GroupStatus.ACTIVE,
    val englishDubbedStatus: GroupStatus = GroupStatus.MAINTENANCE,
    val updatedAt: Long? = null,
    val updatedBy: String? = null
) {
    /**
     * Resolves the authoritative ordered extension IDs for a requested ContentType.
     * Enforces canonical read behavior:
     * - MOVIE -> movie
     * - SERIES -> tv (primary); if tv is absent/null, fallback to series alias
     * - ANIME -> anime
     */
    fun getOrderForContentType(contentType: ContentType): List<String> {
        return when (contentType) {
            ContentType.MOVIE -> movie
            ContentType.SERIES -> tv ?: series
            ContentType.ANIME -> anime
        }
    }

    /**
     * Gets the extension IDs for the requested dubbed language group.
     */
    fun getDubbedOrder(language: VariantLanguage): List<String> {
        return when (language) {
            VariantLanguage.ARABIC -> arabicDubbed
            VariantLanguage.ENGLISH -> englishDubbed
            VariantLanguage.ORIGINAL -> emptyList()
        }
    }

    /**
     * Gets the operational status for the requested dubbed language group.
     */
    fun getDubbedStatus(language: VariantLanguage): GroupStatus {
        return when (language) {
            VariantLanguage.ARABIC -> arabicDubbedStatus
            VariantLanguage.ENGLISH -> englishDubbedStatus
            VariantLanguage.ORIGINAL -> GroupStatus.DISABLED
        }
    }

    /**
     * Checks if requests are permitted to the dubbed language group.
     * Strictly prevents network requests when status != ACTIVE (e.g. MAINTENANCE / DISABLED).
     */
    fun isDubbedAllowed(language: VariantLanguage): Boolean {
        return getDubbedStatus(language) == GroupStatus.ACTIVE
    }

    val isEmpty: Boolean
        get() = movie.isEmpty() && (tv?.isEmpty() ?: series.isEmpty()) && anime.isEmpty() && arabicDubbed.isEmpty() && englishDubbed.isEmpty()
}
