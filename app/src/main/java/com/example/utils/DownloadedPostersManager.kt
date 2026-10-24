package com.example.utils

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance manager for local downloaded media posters (PERF-AD-06).
 *
 * Optimizations implemented:
 * 1. Precompiled SANITIZE_REGEX to eliminate Regex object compilation on the Compose hot path.
 * 2. In-memory localPostersMap (ConcurrentHashMap<String, File>) providing O(1) in-memory resolution
 *    without invoking synchronous File.exists(), File.length(), or File.mkdirs() on the UI thread.
 * 3. Cached directory reference (cachedPostersDir) preventing redundant File.exists() filesystem calls.
 * 4. Dedicated lifecycle-safe background scope (SupervisorJob + Dispatchers.IO).
 */
object DownloadedPostersManager {
    private const val POSTERS_DIR = "downloaded_posters"

    // Precompiled regex strictly preserving original filename sanitization semantics
    private val SANITIZE_REGEX = Regex("[^a-zA-Z0-9_.-]")

    // Thread-safe in-memory cache of known downloaded poster files (safeMediaId -> File)
    private val localPostersMap = ConcurrentHashMap<String, File>()

    @Volatile
    private var isCacheInitialized = false

    @Volatile
    private var cachedPostersDir: File? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Sanitizes a media identifier to a safe filename string using the precompiled regex.
     */
    fun sanitizeMediaId(mediaId: String): String {
        return mediaId.replace(SANITIZE_REGEX, "_")
    }

    /**
     * Asynchronously warms up the in-memory cache on Dispatchers.IO at app cold start.
     */
    fun initAsync(context: Context) {
        if (isCacheInitialized) return
        scope.launch {
            initSync(context)
        }
    }

    /**
     * Synchronously scans the downloaded posters directory on the calling thread (intended for IO dispatchers).
     */
    fun initSync(context: Context) {
        if (!isCacheInitialized) {
            synchronized(this) {
                if (!isCacheInitialized) {
                    try {
                        val dir = getPostersDirInternal(context)
                        if (dir.exists()) {
                            val files = dir.listFiles()
                            if (files != null) {
                                for (f in files) {
                                    if (f.isFile && f.length() > 0L) {
                                        localPostersMap[f.nameWithoutExtension] = f
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    isCacheInitialized = true
                }
            }
        }
    }

    private fun getPostersDirInternal(context: Context): File {
        cachedPostersDir?.let { return it }
        val dir = File(context.filesDir, POSTERS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        cachedPostersDir = dir
        return dir
    }

    fun getPostersDir(context: Context): File {
        return getPostersDirInternal(context)
    }

    fun getLocalPosterFile(context: Context, mediaId: String): File {
        val safe = sanitizeMediaId(mediaId)
        return File(getPostersDirInternal(context), "${safe}.jpg")
    }

    /**
     * Zero-disk-I/O in-memory check executing in O(1) on the main/Compose thread.
     * Returns the pre-indexed local File if present in memory, else returns remoteUrl directly.
     * If the cache hasn't finished warming up, immediately returns remoteUrl and initiates async warm up.
     */
    fun getPosterModel(context: Context, mediaId: String, remoteUrl: String): Any {
        if (mediaId.isBlank()) return remoteUrl
        if (!isCacheInitialized) {
            initAsync(context)
            return remoteUrl
        }
        val safe = sanitizeMediaId(mediaId)
        return localPostersMap[safe] ?: remoteUrl
    }

    suspend fun savePosterLocally(context: Context, mediaId: String, posterUrl: String) = withContext(Dispatchers.IO) {
        if (posterUrl.isBlank() || mediaId.isBlank()) return@withContext
        val safe = sanitizeMediaId(mediaId)
        initSync(context)
        val dir = getPostersDirInternal(context)
        val local = File(dir, "${safe}.jpg")
        if (localPostersMap.containsKey(safe) && local.exists() && local.length() > 0L) return@withContext
        try {
            val url = URL(posterUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.getInputStream().use { input ->
                FileOutputStream(local).use { output ->
                    input.copyTo(output)
                }
            }
            if (local.length() > 0L) {
                localPostersMap[safe] = local
            }
        } catch (_: Exception) {
            try {
                val req = ImageRequest.Builder(context)
                    .data(posterUrl)
                    .build()
                context.imageLoader.enqueue(req)
            } catch (_: Exception) {}
        }
    }

    fun removePoster(context: Context, mediaId: String) {
        if (mediaId.isBlank()) return
        val safe = sanitizeMediaId(mediaId)
        localPostersMap.remove(safe)
        scope.launch {
            try {
                val dir = getPostersDirInternal(context)
                val local = File(dir, "${safe}.jpg")
                if (local.exists()) {
                    local.delete()
                }
            } catch (_: Exception) {}
        }
    }

    // Testing helpers
    fun isPosterCached(mediaId: String): Boolean {
        val safe = sanitizeMediaId(mediaId)
        return localPostersMap.containsKey(safe)
    }

    fun isInitialized(): Boolean = isCacheInitialized

    fun clearCacheForTesting() {
        localPostersMap.clear()
        isCacheInitialized = false
        cachedPostersDir = null
    }
}
