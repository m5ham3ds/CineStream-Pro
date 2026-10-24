package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.LibraryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("""
        SELECT 
            CASE 
                WHEN (variantMode = 'dubbed' OR variantMode = 'ARABIC_DUBBED') AND libraryId NOT LIKE '%_dubbed' 
                THEN libraryId || '_dubbed' 
                ELSE libraryId 
            END AS libraryId,
            tmdbId,
            title,
            posterUrl,
            contentType,
            isMovie,
            CASE 
                WHEN variantMode IS NULL OR variantMode = '' THEN 'subtitled' 
                ELSE variantMode 
            END AS variantMode,
            providerId 
        FROM library_items
    """)
    fun getAllItems(): Flow<List<LibraryItem>>

    @Query("""
        SELECT 
            CASE 
                WHEN (variantMode = 'dubbed' OR variantMode = 'ARABIC_DUBBED') AND libraryId NOT LIKE '%_dubbed' 
                THEN libraryId || '_dubbed' 
                ELSE libraryId 
            END AS libraryId,
            tmdbId,
            title,
            posterUrl,
            contentType,
            isMovie,
            CASE 
                WHEN variantMode IS NULL OR variantMode = '' THEN 'subtitled' 
                ELSE variantMode 
            END AS variantMode,
            providerId 
        FROM library_items 
        WHERE libraryId = :libraryId 
           OR (:libraryId LIKE '%_dubbed' AND libraryId = SUBSTR(:libraryId, 1, LENGTH(:libraryId) - 7) AND (variantMode = 'dubbed' OR variantMode = 'ARABIC_DUBBED'))
        LIMIT 1
    """)
    suspend fun getItemById(libraryId: String): LibraryItem?

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM library_items 
            WHERE libraryId = :libraryId 
               OR (:libraryId LIKE '%_dubbed' AND libraryId = SUBSTR(:libraryId, 1, LENGTH(:libraryId) - 7) AND (variantMode = 'dubbed' OR variantMode = 'ARABIC_DUBBED'))
        )
    """)
    fun isItemInLibrary(libraryId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE tmdbId = :tmdbId AND contentType = :contentType)")
    fun isItemInLibrary(tmdbId: String, contentType: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE tmdbId = :tmdbId AND contentType = :contentType AND (variantMode = :variantMode OR (:variantMode = 'subtitled' AND (variantMode IS NULL OR variantMode = '' OR variantMode = 'subtitled'))))")
    fun isVariantInLibrary(tmdbId: String, contentType: String, variantMode: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE tmdbId = :tmdbId AND (contentType = 'tv' OR contentType = 'anime'))")
    fun isSeriesInLibrary(tmdbId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE tmdbId = :tmdbId AND (contentType = 'tv' OR contentType = 'anime') AND (variantMode = :variantMode OR (:variantMode = 'subtitled' AND (variantMode IS NULL OR variantMode = '' OR variantMode = 'subtitled'))))")
    fun isSeriesVariantInLibrary(tmdbId: String, variantMode: String): Flow<Boolean>

    @Query("""
        SELECT 
            CASE 
                WHEN (variantMode = 'dubbed' OR variantMode = 'ARABIC_DUBBED') AND libraryId NOT LIKE '%_dubbed' 
                THEN libraryId || '_dubbed' 
                ELSE libraryId 
            END AS libraryId,
            tmdbId,
            title,
            posterUrl,
            contentType,
            isMovie,
            CASE 
                WHEN variantMode IS NULL OR variantMode = '' THEN 'subtitled' 
                ELSE variantMode 
            END AS variantMode,
            providerId 
        FROM library_items 
        WHERE tmdbId = :tmdbId AND contentType = :contentType AND (variantMode = :variantMode OR (:variantMode = 'subtitled' AND (variantMode IS NULL OR variantMode = '' OR variantMode = 'subtitled'))) 
        LIMIT 1
    """)
    suspend fun getItemByVariant(tmdbId: String, contentType: String, variantMode: String): LibraryItem?

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE tmdbId = :tmdbId)")
    fun isItemInLibraryByTmdbId(tmdbId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: LibraryItem)

    @Delete
    suspend fun deleteItem(item: LibraryItem)

    @Query("""
        DELETE FROM library_items 
        WHERE libraryId = :libraryId 
           OR (:libraryId LIKE '%_dubbed' AND libraryId = SUBSTR(:libraryId, 1, LENGTH(:libraryId) - 7) AND (variantMode = 'dubbed' OR variantMode = 'ARABIC_DUBBED'))
    """)
    suspend fun deleteByLibraryId(libraryId: String)

    @Query("DELETE FROM library_items")
    suspend fun clearAll()
}
