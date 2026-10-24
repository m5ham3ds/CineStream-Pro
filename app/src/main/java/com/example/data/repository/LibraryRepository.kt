package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.model.ContentType
import com.example.data.model.LibraryIdentity
import com.example.data.model.LibraryItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.Flow

class LibraryRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val libraryDao = db.libraryDao()
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Checks if an item is in the library.
     * Supports both namespaced libraryId (e.g. "movie_550", "anime_1399")
     * and fallback TMDB ID.
     */
    fun isItemInLibrary(idOrLibraryId: String): Flow<Boolean> {
        return if (idOrLibraryId.contains("_")) {
            libraryDao.isItemInLibrary(idOrLibraryId)
        } else {
            libraryDao.isItemInLibraryByTmdbId(idOrLibraryId)
        }
    }

    /**
     * Exact check for a specific media item by its TMDB ID, canonical contentType, and variantMode.
     * Guarantees movie_8587 (subtitled) != movie_8587_dubbed (dubbed).
     */
    fun isItemInLibrary(tmdbId: String, contentType: String, variantMode: String): Flow<Boolean> {
        val normalizedType = ContentType.normalize(contentType)
        val normalizedMode = LibraryIdentity.normalizeVariantMode(variantMode)
        return libraryDao.isVariantInLibrary(tmdbId.trim(), normalizedType, normalizedMode)
    }

    /**
     * Exact check for a specific media item by its TMDB ID and canonical contentType (defaults to subtitled/legacy).
     * Guarantees movie_550 != tv_550.
     */
    fun isItemInLibrary(tmdbId: String, contentType: String): Flow<Boolean> {
        val normalizedType = ContentType.normalize(contentType)
        val libraryId = LibraryIdentity.createLibraryId(normalizedType, tmdbId)
        return libraryDao.isItemInLibrary(libraryId)
    }

    fun isSeriesInLibrary(tmdbId: String): Flow<Boolean> {
        return libraryDao.isSeriesInLibrary(tmdbId)
    }

    fun isSeriesInLibrary(tmdbId: String, variantMode: String): Flow<Boolean> {
        val normalizedMode = LibraryIdentity.normalizeVariantMode(variantMode)
        return libraryDao.isSeriesVariantInLibrary(tmdbId.trim(), normalizedMode)
    }

    fun getLibraryItems(): Flow<List<LibraryItem>> {
        return libraryDao.getAllItems()
    }

    suspend fun addToLibrary(item: LibraryItem) {
        LibraryIdentity.validate(item)
        val existing = libraryDao.getItemById(item.libraryId)
        val resolvedItem = if (existing != null) {
            val resolvedMode = if (item.variantMode.isNotBlank()) item.variantMode else existing.variantMode
            val canonicalLibId = LibraryIdentity.createLibraryId(item.contentType, item.tmdbId, resolvedMode)
            item.copy(
                libraryId = canonicalLibId,
                posterUrl = if (item.posterUrl.isNotBlank()) item.posterUrl else existing.posterUrl,
                variantMode = resolvedMode,
                providerId = if (item.providerId.isNotBlank()) item.providerId else existing.providerId,
                title = if (item.title.isNotBlank()) item.title else existing.title
            )
        } else {
            item
        }
        LibraryIdentity.validate(resolvedItem)
        libraryDao.insertItem(resolvedItem)

        auth.currentUser?.uid?.let { uid ->
            try {
                // Canonical Firestore path uses resolvedItem.libraryId as document ID
                firestore.collection("users").document(uid).collection("library")
                    .document(resolvedItem.libraryId)
                    .set(resolvedItem.toFirestoreMap(), SetOptions.merge())
            } catch (e: Exception) {
                // Non-blocking offline-first sync
            }
        }
    }

    suspend fun removeFromLibrary(item: LibraryItem) {
        LibraryIdentity.validate(item)
        libraryDao.deleteItem(item)

        auth.currentUser?.uid?.let { uid ->
            try {
                val libCol = firestore.collection("users").document(uid).collection("library")
                // Delete canonical namespaced document
                libCol.document(item.libraryId).delete()
                // Only clean up legacy un-namespaced document if this is the standard/subtitled version
                if (item.variantMode != LibraryIdentity.VARIANT_DUBBED) {
                    libCol.document(item.tmdbId).delete()
                }
            } catch (e: Exception) {
                // Non-blocking offline-first sync
            }
        }
    }

    suspend fun removeFromLibrary(libraryId: String) {
        val item = libraryDao.getItemById(libraryId)
        if (item != null) {
            removeFromLibrary(item)
        } else {
            libraryDao.deleteByLibraryId(libraryId)
        }
    }
}
