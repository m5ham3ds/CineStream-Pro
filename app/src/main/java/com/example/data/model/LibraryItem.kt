package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
@Entity(tableName = "library_items")
data class LibraryItem(
    @PrimaryKey
    val libraryId: String = "",
    val tmdbId: String = "",
    val title: String = "",
    val posterUrl: String = "",
    val contentType: String = ContentType.MOVIE,
    val isMovie: Boolean = (contentType == ContentType.MOVIE),
    val variantMode: String = "subtitled",
    val providerId: String = ""
) {
    /**
     * Backward-compatibility property:
     * Historically, components referenced `item.id` to get the TMDB ID.
     * This getter returns `tmdbId` if present, or extracts it from `libraryId`.
     */
    val id: String
        get() = tmdbId.ifEmpty {
            LibraryIdentity.parseLibraryId(libraryId)?.second ?: libraryId
        }

    init {
        // Enforce invariants when non-empty instances are constructed
        if (libraryId.isNotBlank() && tmdbId.isNotBlank()) {
            LibraryIdentity.validate(
                libraryId = libraryId,
                tmdbId = tmdbId,
                contentType = contentType,
                isMovie = isMovie,
                variantMode = variantMode
            )
        }
    }

    fun toFirestoreMap(): Map<String, Any> {
        return mapOf(
            "libraryId" to libraryId,
            "tmdbId" to tmdbId,
            "title" to title,
            "posterUrl" to posterUrl,
            "contentType" to contentType,
            "isMovie" to isMovie,
            "variantMode" to variantMode,
            "providerId" to providerId
        )
    }

    companion object {
        /**
         * Canonical factory method.
         */
        fun create(
            contentType: String,
            tmdbId: String,
            title: String,
            posterUrl: String,
            variantMode: String = LibraryIdentity.VARIANT_SUBTITLED,
            providerId: String = ""
        ): LibraryItem {
            val normalizedType = ContentType.normalize(contentType)
            val cleanTmdbId = tmdbId.trim()
            val normalizedMode = LibraryIdentity.normalizeVariantMode(variantMode)
            val libraryId = LibraryIdentity.createLibraryId(normalizedType, cleanTmdbId, normalizedMode)
            val isMovie = (normalizedType == ContentType.MOVIE)
            return LibraryItem(
                libraryId = libraryId,
                tmdbId = cleanTmdbId,
                title = title.trim(),
                posterUrl = posterUrl.trim(),
                contentType = normalizedType,
                isMovie = isMovie,
                variantMode = normalizedMode,
                providerId = providerId.trim()
            )
        }

        /**
         * Converts legacy (id, isMovie) representation into canonical LibraryItem.
         * Legacy Rule:
         * - isMovie == true -> contentType = "movie"
         * - isMovie == false -> contentType = "tv" (NEVER assumed anime)
         */
        fun fromLegacy(
            id: String,
            title: String,
            posterUrl: String,
            isMovie: Boolean,
            variantMode: String = LibraryIdentity.VARIANT_SUBTITLED,
            providerId: String = ""
        ): LibraryItem {
            val cleanId = id.trim()
            val contentType = if (isMovie) ContentType.MOVIE else ContentType.TV
            val normalizedMode = LibraryIdentity.normalizeVariantMode(variantMode)
            val libraryId = LibraryIdentity.createLibraryId(contentType, cleanId, normalizedMode)
            return LibraryItem(
                libraryId = libraryId,
                tmdbId = cleanId,
                title = title.trim(),
                posterUrl = posterUrl.trim(),
                contentType = contentType,
                isMovie = isMovie,
                variantMode = normalizedMode,
                providerId = providerId.trim()
            )
        }

        /**
         * Safely parses a Firestore document map, seamlessly supporting both
         * canonical documents and legacy documents.
         */
        fun fromFirestoreMap(docId: String, data: Map<String, Any?>): LibraryItem? {
            try {
                val title = (data["title"] as? String) ?: ""
                val posterUrl = (data["posterUrl"] as? String) ?: ""
                val rawVariantMode = (data["variantMode"] as? String) ?: LibraryIdentity.VARIANT_SUBTITLED
                val variantMode = LibraryIdentity.normalizeVariantMode(rawVariantMode)
                val providerId = (data["providerId"] as? String) ?: ""

                // 1. Check if canonical fields exist
                val rawContentType = data["contentType"] as? String
                val rawTmdbId = (data["tmdbId"] as? String)?.trim()
                val rawLibraryId = (data["libraryId"] as? String)?.trim()

                if (rawContentType != null && ContentType.isValid(rawContentType) && !rawTmdbId.isNullOrBlank()) {
                    val expectedId = LibraryIdentity.createLibraryId(rawContentType, rawTmdbId, variantMode)
                    val libId = if (rawLibraryId != null && (rawLibraryId == expectedId || (variantMode == LibraryIdentity.VARIANT_SUBTITLED && rawLibraryId == "${rawContentType}_${rawTmdbId}_$variantMode"))) {
                        rawLibraryId
                    } else {
                        expectedId
                    }
                    val isMovie = (rawContentType == ContentType.MOVIE)
                    return LibraryItem(
                        libraryId = libId,
                        tmdbId = rawTmdbId,
                        title = title,
                        posterUrl = posterUrl,
                        contentType = rawContentType,
                        isMovie = isMovie,
                        variantMode = variantMode,
                        providerId = providerId
                    )
                }

                // 2. Check if docId itself is a namespaced libraryId (e.g. "movie_550", "movie_8587_dubbed", "anime_1399")
                val parsedFromDocId = LibraryIdentity.parseLibraryDetails(docId)
                if (parsedFromDocId != null) {
                    val type = parsedFromDocId.contentType
                    val tmdbId = parsedFromDocId.tmdbId
                    val effectiveMode = if (rawVariantMode.equals(LibraryIdentity.VARIANT_DUBBED, ignoreCase = true)) {
                        LibraryIdentity.VARIANT_DUBBED
                    } else {
                        parsedFromDocId.variantMode
                    }
                    val canonicalId = LibraryIdentity.createLibraryId(type, tmdbId, effectiveMode)
                    val isMovie = (type == ContentType.MOVIE)
                    return LibraryItem(
                        libraryId = canonicalId,
                        tmdbId = tmdbId,
                        title = title,
                        posterUrl = posterUrl,
                        contentType = type,
                        isMovie = isMovie,
                        variantMode = effectiveMode,
                        providerId = providerId
                    )
                }

                // 3. Fallback: Legacy Document format (where docId or "id" is raw TMDB ID)
                val legacyRawId = rawTmdbId ?: (data["id"] as? String) ?: docId
                if (legacyRawId.isBlank()) return null

                val legacyIsMovie = (data["isMovie"] as? Boolean) ?: true
                val legacyContentType = if (legacyIsMovie) ContentType.MOVIE else ContentType.TV
                val canonicalLibraryId = LibraryIdentity.createLibraryId(legacyContentType, legacyRawId, variantMode)

                return LibraryItem(
                    libraryId = canonicalLibraryId,
                    tmdbId = legacyRawId,
                    title = title,
                    posterUrl = posterUrl,
                    contentType = legacyContentType,
                    isMovie = legacyIsMovie,
                    variantMode = variantMode,
                    providerId = providerId
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}
