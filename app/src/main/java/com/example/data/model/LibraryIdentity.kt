package com.example.data.model

data class LibraryIdDetails(
    val contentType: String,
    val tmdbId: String,
    val variantMode: String
)

/**
 * Single source of truth for constructing, parsing, and validating
 * Canonical Library Identities: {contentType}_{tmdbId} (subtitled/legacy)
 * and {contentType}_{tmdbId}_dubbed (dubbed variant)
 */
object LibraryIdentity {

    const val VARIANT_DUBBED = "dubbed"
    const val VARIANT_SUBTITLED = "subtitled"

    fun normalizeVariantMode(mode: String?): String {
        return if (mode.equals(VARIANT_DUBBED, ignoreCase = true)) VARIANT_DUBBED else VARIANT_SUBTITLED
    }

    /**
     * Creates a namespaced libraryId from contentType, tmdbId, and variantMode.
     * Guarantees distinct identity between dubbed and subtitled variants of the same work.
     * Examples:
     * - createLibraryId("movie", "8587", "dubbed") -> "movie_8587_dubbed"
     * - createLibraryId("movie", "8587", "subtitled") -> "movie_8587"
     */
    fun createLibraryId(contentType: String, tmdbId: String, variantMode: String = VARIANT_SUBTITLED): String {
        val normalizedType = ContentType.normalize(contentType)
        require(ContentType.isValid(normalizedType)) {
            "Invalid contentType: '$contentType'. Allowed values: ${ContentType.VALID_TYPES}"
        }
        val cleanTmdbId = tmdbId.trim()
        require(cleanTmdbId.isNotBlank()) {
            "tmdbId must not be blank"
        }
        val normalizedMode = normalizeVariantMode(variantMode)
        return if (normalizedMode == VARIANT_DUBBED) {
            "${normalizedType}_${cleanTmdbId}_$VARIANT_DUBBED"
        } else {
            "${normalizedType}_$cleanTmdbId"
        }
    }

    /**
     * Parses a namespaced libraryId into structured details (contentType, tmdbId, variantMode).
     * Fully backward compatible with legacy "{contentType}_{tmdbId}" format and "{contentType}_{tmdbId}_{variantMode}".
     */
    fun parseLibraryDetails(libraryId: String): LibraryIdDetails? {
        if (!libraryId.contains("_")) return null
        val parts = libraryId.split("_")
        if (parts.size < 2) return null
        val type = ContentType.normalize(parts[0].trim())
        if (!ContentType.isValid(type)) return null

        return when (parts.size) {
            2 -> {
                val tmdbId = parts[1].trim()
                if (tmdbId.isBlank()) null
                else LibraryIdDetails(type, tmdbId, VARIANT_SUBTITLED)
            }
            3 -> {
                val tmdbId = parts[1].trim()
                val mode = normalizeVariantMode(parts[2].trim())
                if (tmdbId.isBlank()) null
                else LibraryIdDetails(type, tmdbId, mode)
            }
            else -> {
                val lastPart = parts.last().trim()
                if (lastPart.equals(VARIANT_DUBBED, ignoreCase = true) || lastPart.equals(VARIANT_SUBTITLED, ignoreCase = true)) {
                    val tmdbId = parts.subList(1, parts.size - 1).joinToString("_").trim()
                    if (tmdbId.isBlank()) null
                    else LibraryIdDetails(type, tmdbId, normalizeVariantMode(lastPart))
                } else {
                    val tmdbId = parts.subList(1, parts.size).joinToString("_").trim()
                    if (tmdbId.isBlank()) null
                    else LibraryIdDetails(type, tmdbId, VARIANT_SUBTITLED)
                }
            }
        }
    }

    /**
     * Parses a namespaced libraryId into (contentType, tmdbId) for backward compatibility.
     * Returns null if the libraryId is not a valid namespaced format.
     */
    fun parseLibraryId(libraryId: String): Pair<String, String>? {
        val details = parseLibraryDetails(libraryId) ?: return null
        return Pair(details.contentType, details.tmdbId)
    }

    /**
     * Validates all canonical invariants:
     * 1. contentType in {"movie", "tv", "anime"}
     * 2. tmdbId is not blank
     * 3. libraryId matches expected variant-aware pattern
     * 4. isMovie == (contentType == "movie")
     */
    fun validate(
        libraryId: String,
        tmdbId: String,
        contentType: String,
        isMovie: Boolean,
        variantMode: String = VARIANT_SUBTITLED
    ) {
        val normalizedType = ContentType.normalize(contentType)
        require(ContentType.isValid(normalizedType)) {
            "Invariant violation: contentType '$contentType' is invalid. Must be one of ${ContentType.VALID_TYPES}"
        }
        val cleanTmdbId = tmdbId.trim()
        require(cleanTmdbId.isNotBlank()) {
            "Invariant violation: tmdbId must not be blank"
        }
        val normalizedMode = normalizeVariantMode(variantMode)
        val expectedCanonicalId = createLibraryId(normalizedType, cleanTmdbId, normalizedMode)
        val isMatch = (libraryId == expectedCanonicalId) ||
            (normalizedMode == VARIANT_SUBTITLED && libraryId == "${normalizedType}_${cleanTmdbId}_$VARIANT_SUBTITLED")

        require(isMatch) {
            "Invariant violation: libraryId '$libraryId' does not match expected '$expectedCanonicalId' for mode '$normalizedMode'"
        }
        val expectedIsMovie = (normalizedType == ContentType.MOVIE)
        require(isMovie == expectedIsMovie) {
            "Invariant violation: isMovie ($isMovie) must be $expectedIsMovie for contentType '$contentType'"
        }
    }

    /**
     * Overload to validate a LibraryItem instance.
     */
    fun validate(item: LibraryItem) {
        validate(
            libraryId = item.libraryId,
            tmdbId = item.tmdbId,
            contentType = item.contentType,
            isMovie = item.isMovie,
            variantMode = item.variantMode
        )
    }
}
