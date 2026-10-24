package com.example.utils

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import com.example.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CacheManagementHelper {

    /**
     * Calculates the total size of safe-to-clean cache.
     */
    suspend fun calculateCleanableCacheSizeBytes(context: Context): Long = withContext(Dispatchers.IO) {
        var totalBytes = 0L
        try {
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.exists()) {
                totalBytes += getFolderSize(cacheDir)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        totalBytes
    }

    /**
     * Cleans cache safely while strictly preserving:
     * 1. Media details & metadata of all downloaded items.
     * 2. Poster images & covers of all downloaded items.
     * 3. Downloaded video files and database records.
     */
    suspend fun cleanCacheSafely(context: Context): Long = withContext(Dispatchers.IO) {
        val initialSize = calculateCleanableCacheSizeBytes(context)
        try {
            val db = AppDatabase.getDatabase(context)
            val downloadedItems = db.downloadDao().getAllItemsSync()
            val protectedPosters = downloadedItems.mapNotNull { it.posterUrl }.filter { it.isNotBlank() }

            // 1. Clear non-downloaded media details JSON files
            MediaDetailsCacheManager.clearNonDownloadedCache(context)

            // 2. Clear Coil memory cache and in-memory media cache
            context.imageLoader.memoryCache?.clear()
            com.example.di.AppContainer.mediaRepository.clearCache()

            // 3. Clean temporary files in cacheDir (e.g. http_cache, temp share files)
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.exists()) {
                val okHttpCache = File(cacheDir, "http_cache")
                if (okHttpCache.exists()) {
                    okHttpCache.deleteRecursively()
                }

                // Delete loose temp files in cacheDir that are not active downloads
                cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.endsWith(".tmp") || file.name.endsWith(".mp4") || file.name.startsWith("audio_"))) {
                        file.delete()
                    }
                }
            }

            // 4. Pre-warm / ensure protected posters remain cached in Coil
            val imageLoader = context.imageLoader
            protectedPosters.forEach { url ->
                try {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .build()
                    imageLoader.enqueue(request)
                } catch (_: Exception) {}
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        val remainingSize = calculateCleanableCacheSizeBytes(context)
        (initialSize - remainingSize).coerceAtLeast(0L)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }

    private fun getFolderSize(file: File): Long {
        var size = 0L
        val files = file.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) getFolderSize(f) else f.length()
        }
        return size
    }
}
