package com.example.utils

import com.example.MainActivity
import com.example.R
import com.example.utils.MediaStorageUtils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

class StreamDownloaderService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val CHANNEL_ID = "stream_download_channel"
    
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeNotifications = ConcurrentHashMap<String, Int>()
    private val pausedFlags = ConcurrentHashMap<String, Boolean>()
    private val taskHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val taskFallbacks = ConcurrentHashMap<String, List<String>>()
    private var runningDownloads = AtomicInteger(0)
    private var currentForegroundId: Int? = null

    private fun saveHeaders(fileId: String, headers: Map<String, String>) {
        try {
            val sp = getSharedPreferences("download_headers_pref", Context.MODE_PRIVATE)
            val joined = headers.entries.joinToString(";;") { "${it.key}==${it.value}" }
            sp.edit().putString(fileId, joined).apply()
        } catch (_: Exception) {}
    }

    private fun loadHeaders(fileId: String): Map<String, String>? {
        return try {
            val sp = getSharedPreferences("download_headers_pref", Context.MODE_PRIVATE)
            val raw = sp.getString(fileId, null) ?: return null
            raw.split(";;").mapNotNull {
                val parts = it.split("==")
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
        } catch (_: Exception) {
            null
        }
    }

    private fun clearHeaders(fileId: String) {
        taskHeaders.remove(fileId)
        taskFallbacks.remove(fileId)
        try {
            val sp = getSharedPreferences("download_headers_pref", Context.MODE_PRIVATE)
            sp.edit().remove(fileId).apply()
        } catch (_: Exception) {}
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    private val dispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 128
        maxRequestsPerHost = 64
    }
    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val fileId = intent?.getStringExtra("id") ?: return START_NOT_STICKY
        val title = intent.getStringExtra("title") ?: "Video"
        val url = intent.getStringExtra("url") ?: ""
        
        if (action == "CANCEL") {
            clearHeaders(fileId)
            val job = activeJobs[fileId]
            job?.cancel()
            activeJobs.remove(fileId)
            pausedFlags.remove(fileId)
            
            val notifId = activeNotifications[fileId]
            if (notifId != null) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notifId)
                activeNotifications.remove(fileId)
            }
            
            // Delete files and db entry
            serviceScope.launch {
                try {
                    val db = com.example.data.db.AppDatabase.getDatabase(this@StreamDownloaderService)
                    val dao = db.downloadDao()
                    val item = dao.getItemById(fileId)
                    if (item != null) dao.deleteItem(item)
                    
                    val destFile = MediaStorageUtils.findMediaFile(this@StreamDownloaderService, fileId)
                    if (destFile != null && destFile.exists()) destFile.delete()
                    val internalDir = MediaStorageUtils.getMediaDirectory(this@StreamDownloaderService)
                    val tempDir = File(internalDir, "temp_$fileId")
                    if (tempDir.exists()) tempDir.deleteRecursively()
                } catch (e: Exception) {}
            }
            return START_NOT_STICKY
        }
        
        if (action == "PAUSE") {
            pausedFlags[fileId] = true
            serviceScope.launch {
                updateDbState(fileId, true)
            }
            updateNotification(activeNotifications[fileId] ?: fileId.hashCode(), title, getString(R.string.download_paused), 0, 0, true, fileId, isPaused = true)
            return START_NOT_STICKY
        }
        
        if (action == "RESUME") {
            pausedFlags[fileId] = false
            if (!taskHeaders.containsKey(fileId)) {
                loadHeaders(fileId)?.let { taskHeaders[fileId] = it }
            }
            serviceScope.launch {
                updateDbState(fileId, false)
                val job = activeJobs[fileId]
                if (job == null || !job.isActive) {
                    try {
                        val db = com.example.data.db.AppDatabase.getDatabase(this@StreamDownloaderService)
                        val item = db.downloadDao().getItemById(fileId)
                        if (item != null) {
                            val resolvedUrl = if (item.quality.contains("||")) item.quality.substringAfter("||") else item.quality
                            if (resolvedUrl.isNotEmpty()) {
                                val restartIntent = Intent(this@StreamDownloaderService, StreamDownloaderService::class.java).apply {
                                    putExtra("url", resolvedUrl)
                                    putExtra("title", item.title)
                                    putExtra("id", item.id)
                                }
                                startService(restartIntent)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            updateNotification(activeNotifications[fileId] ?: fileId.hashCode(), title, getString(R.string.downloading_ellipsis), 0, 0, true, fileId, isPaused = false)
            return START_NOT_STICKY
        }
        
        if (url.isEmpty()) return START_NOT_STICKY
        
        if (!com.example.data.repository.UserSecurityManager.canDownload()) {
            return START_NOT_STICKY
        }
        
        val intentHeaders = (intent?.getSerializableExtra("headers") as? HashMap<*, *>)?.mapNotNull { (k, v) ->
            val keyStr = k as? String
            val valStr = v as? String
            if (keyStr != null && valStr != null) keyStr to valStr else null
        }?.toMap() ?: emptyMap()

        if (intentHeaders.isNotEmpty()) {
            taskHeaders[fileId] = intentHeaders
            saveHeaders(fileId, intentHeaders)
        } else if (!taskHeaders.containsKey(fileId)) {
            loadHeaders(fileId)?.let { cachedHeaders ->
                taskHeaders[fileId] = cachedHeaders
            }
        }

        val fallbackList = intent?.getStringArrayListExtra("fallback_urls")
        if (fallbackList != null && fallbackList.isNotEmpty()) {
            taskFallbacks[fileId] = fallbackList
        }

        // Duplicate job protection: do not launch another job if already active
        if (activeJobs.containsKey(fileId) && activeJobs[fileId]?.isActive == true) {
            return START_NOT_STICKY
        }

        val notificationId = fileId.hashCode()
        activeNotifications[fileId] = notificationId
        pausedFlags[fileId] = false
        
        updateNotification(notificationId, title, getString(R.string.initializing_download), 0, 0, true, fileId, false)
        
        val job = serviceScope.launch {
            try {
                val prefs = UserPreferencesRepository(this@StreamDownloaderService)
                val maxConcurrentMovies = prefs.maxConcurrentDownloads.first()
                val maxSegments = prefs.maxSegments.first()
                val networkType = prefs.downloadNetwork.first()
                
                if (networkType == 1) {
                    while (!isWifiConnected() && isActive) {
                        updateNotification(notificationId, title, getString(R.string.waiting_for_wifi), 0, 0, true, fileId, false)
                        delay(5000)
                    }
                }
                
                while (runningDownloads.get() >= maxConcurrentMovies && isActive) {
                    updateNotification(notificationId, title, getString(R.string.download_queued), 0, 0, true, fileId, false)
                    delay(3000)
                }
                
                runningDownloads.incrementAndGet()
                
                val candidatesToTry = listOf(url) + (taskFallbacks[fileId] ?: emptyList())
                var downloadSuccess = false
                var lastError: Exception? = null

                for ((idx, streamUrl) in candidatesToTry.withIndex()) {
                    if (idx > 0) {
                        try {
                            android.util.Log.i("STREAM_DOWNLOAD", "Attempting same-quality fallback candidate index=$idx url=$streamUrl")
                        } catch (_: Throwable) {}
                    }
                    try {
                        if (streamUrl.contains(".m3u8")) {
                            downloadM3u8(notificationId, streamUrl, title, fileId, maxSegments)
                        } else {
                            downloadMp4(notificationId, streamUrl, title, fileId, maxSegments)
                        }
                        downloadSuccess = true
                        break
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        lastError = e
                        try {
                            android.util.Log.w("STREAM_DOWNLOAD", "Candidate $idx failed: ${e.message}")
                        } catch (_: Throwable) {}
                    }
                }

                if (!downloadSuccess && lastError != null) {
                    throw lastError
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    e.printStackTrace()
                    updateNotification(notificationId, title, getString(R.string.download_failed_with_msg, e.message ?: ""), 0, 0, false, fileId, false)
                }
            } finally {
                clearHeaders(fileId)
                runningDownloads.decrementAndGet()
                activeJobs.remove(fileId)
                if (activeJobs.isEmpty()) {
                    stopForeground(false)
                    stopSelf()
                }
            }
        }
        activeJobs[fileId] = job
        
        return START_NOT_STICKY
    }

    private fun getHeaders(fileId: String, url: String): Request.Builder {
        val reqBuilder = Request.Builder().url(url)
        reqBuilder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        reqBuilder.addHeader("Accept", "*/*")
        reqBuilder.addHeader("Connection", "keep-alive")

        // Priority 1: Headers explicitly supplied by DownloadSource
        val customHeaders = taskHeaders[fileId] ?: emptyMap()
        for ((key, value) in customHeaders) {
            if (value.isNotBlank() && !key.equals("cookie", ignoreCase = true) && !key.equals("set-cookie", ignoreCase = true)) {
                reqBuilder.header(key, value)
            }
        }

        // Sanitized diagnostics logging (Requirement 27: never log auth, cookies, tokens)
        try {
            val sanitized = customHeaders.filterNot { (k, _) ->
                k.equals("authorization", ignoreCase = true) ||
                k.equals("cookie", ignoreCase = true) ||
                k.equals("set-cookie", ignoreCase = true) ||
                k.contains("token", ignoreCase = true) ||
                k.contains("secret", ignoreCase = true)
            }
            android.util.Log.d("StreamDownloaderService", "Preserved headers for $fileId: $sanitized")
        } catch (_: Throwable) {}

        // Priority 2: Fallback referer only if Referer was not provided by DownloadSource
        val hasReferer = customHeaders.keys.any { it.equals("Referer", ignoreCase = true) }
        if (!hasReferer) {
            val uri = try { URI(url) } catch (e: Exception) { null }
            val host = uri?.host ?: ""
            val scheme = uri?.scheme ?: "https"
            val origin = "$scheme://$host"
            reqBuilder.header("Origin", origin)
            reqBuilder.header("Referer", "$origin/")
        } else {
            val hasOrigin = customHeaders.keys.any { it.equals("Origin", ignoreCase = true) }
            if (!hasOrigin) {
                val refVal = customHeaders.entries.first { it.key.equals("Referer", ignoreCase = true) }.value
                val refUri = try { URI(refVal) } catch (e: Exception) { null }
                if (refUri != null && !refUri.host.isNullOrBlank()) {
                    val origin = "${refUri.scheme ?: "https"}://${refUri.host}"
                    reqBuilder.header("Origin", origin)
                }
            }
        }

        return reqBuilder
    }

    private fun updateNotification(notificationId: Int, title: String, text: String, progress: Int, max: Int, ongoing: Boolean, fileId: String, isPaused: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isServiceActive = ongoing || isPaused
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(if (isServiceActive) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setOngoing(isServiceActive)
            .setOnlyAlertOnce(true)

        // Clicking notification body opens MainActivity and navigates directly to downloads screen
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "downloads")
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(openPendingIntent)
        builder.setAutoCancel(!isServiceActive)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        }
        
        if (fileId.isNotEmpty() && isServiceActive) {
            // Add Pause/Resume button - preserved whether downloading or paused!
            val pauseResumeIntent = Intent(this, StreamDownloaderService::class.java).apply {
                action = if (isPaused) "RESUME" else "PAUSE"
                putExtra("id", fileId)
                putExtra("title", title)
            }
            val prPendingIntent = PendingIntent.getService(this, notificationId + 1, pauseResumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) getString(R.string.resume) else getString(R.string.pause),
                prPendingIntent
            )
            
            // Add Cancel button
            val cancelIntent = Intent(this, StreamDownloaderService::class.java).apply {
                action = "CANCEL"
                putExtra("id", fileId)
            }
            val cancelPendingIntent = PendingIntent.getService(this, notificationId + 2, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel), cancelPendingIntent)
        }
        
        val notif = builder.build()
        if (isServiceActive) {
            if (currentForegroundId == null || currentForegroundId == notificationId) {
                currentForegroundId = notificationId
                startForeground(notificationId, notif)
            } else {
                notificationManager.notify(notificationId, notif)
            }
        } else {
            notificationManager.notify(notificationId, notif)
            if (currentForegroundId == notificationId) {
                val another = activeNotifications.values.firstOrNull { it != notificationId }
                if (another != null) {
                    currentForegroundId = another
                } else {
                    currentForegroundId = null
                }
            }
        }
    }

    private suspend fun updateDbProgress(fileId: String, progress: Float, isCompleted: Boolean = false, fileSizeBytes: Long = 0L) {
        try {
            val db = com.example.data.db.AppDatabase.getDatabase(this)
            val dao = db.downloadDao()
            val item = dao.getItemById(fileId)
            if (item != null) {
                val size = if (fileSizeBytes > 0L) fileSizeBytes else item.fileSizeBytes
                dao.updateItem(item.copy(progress = progress, isCompleted = isCompleted, fileSizeBytes = size))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun updateDbState(fileId: String, isPaused: Boolean) {
        try {
            val db = com.example.data.db.AppDatabase.getDatabase(this)
            val dao = db.downloadDao()
            val item = dao.getItemById(fileId)
            if (item != null) {
                dao.updateItem(item.copy(isPaused = isPaused))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun suspendIfPaused(fileId: String) {
        while (pausedFlags[fileId] == true) {
            delay(1000)
        }
    }

    private suspend fun downloadMp4(notificationId: Int, url: String, title: String, fileId: String, maxSegments: Int) {
        val urlClean = url.substringBefore('?').substringBefore('#')
        val detectedExt = urlClean.substringAfterLast('.', "mp4").lowercase()
        val validExt = if (detectedExt in listOf("mp4", "mkv", "webm", "avi", "mov", "flv", "m4v")) detectedExt else "mp4"
        val destFile = MediaStorageUtils.getDestinationFile(this, fileId, validExt)
        
        var headReq = getHeaders(fileId, url).head().build()
        var headRes = client.newCall(headReq).execute()
        var contentLength = headRes.header("Content-Length")?.toLongOrNull() ?: -1L
        var acceptRanges = headRes.header("Accept-Ranges") == "bytes"
        headRes.close()
        
        if (contentLength > 0 && acceptRanges && maxSegments > 1) {
            // Segmented download - omit for simplicity here to make it rock solid, fall back to single connection with Range support
            acceptRanges = true
        }

        var downloadedBytes = if (destFile.exists()) destFile.length() else 0L
        if (contentLength > 0 && downloadedBytes == contentLength) {
            updateNotification(notificationId, title, getString(R.string.download_complete_simple), 0, 0, false, fileId, false)
            updateDbProgress(fileId, 1.0f, true)
            return
        }

        var success = false
        while (!success && serviceScope.isActive) {
            suspendIfPaused(fileId)
            try {
                val reqBuilder = getHeaders(fileId, url)
                if (downloadedBytes > 0) {
                    reqBuilder.header("Range", "bytes=$downloadedBytes-")
                }
                
                val res = client.newCall(reqBuilder.build()).execute()
                if (!res.isSuccessful && res.code != 206) throw IOException("Failed to download file: ${res.code}")
                
                val stream = res.body?.byteStream()
                val output = FileOutputStream(destFile, downloadedBytes > 0)
                // 256KB buffer for optimized network & disk throughput
                val buffer = ByteArray(256 * 1024)
                var read: Int
                
                while (stream?.read(buffer).also { read = it ?: -1 } != -1 && serviceScope.isActive) {
                    suspendIfPaused(fileId)
                    output.write(buffer, 0, read)
                    downloadedBytes += read.toLong()
                    
                    if (contentLength > 0 && downloadedBytes % (1024 * 512) < 256 * 1024) {
                        val percent = (downloadedBytes * 100 / contentLength).toInt()
                        updateNotification(notificationId, title, getString(R.string.downloading_percent, percent), downloadedBytes.toInt(), contentLength.toInt(), true, fileId, false)
                        updateDbProgress(fileId, downloadedBytes.toFloat() / contentLength.toFloat())
                    }
                }
                output.close()
                res.close()
                
                if (contentLength > 0 && downloadedBytes < contentLength) {
                    throw IOException("Connection closed prematurely")
                }
                success = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                updateNotification(notificationId, title, getString(R.string.waiting_for_network), 0, 0, true, fileId, false)
                delay(5000)
            }
        }
        
        updateNotification(notificationId, title, getString(R.string.download_complete_simple), 0, 0, false, fileId, false)
        updateDbProgress(fileId, 1.0f, true, fileSizeBytes = destFile.length())
    }

    private suspend fun downloadM3u8(notificationId: Int, m3u8Url: String, title: String, fileId: String, maxConcurrent: Int) {
        val segments = mutableListOf<String>()
        var baseUrl = m3u8Url
        
        var content = ""
        var successFetch = false
        while (!successFetch && serviceScope.isActive) {
            suspendIfPaused(fileId)
            try {
                val req = getHeaders(fileId, m3u8Url).build()
                val res = client.newCall(req).execute()
                content = res.body?.string() ?: throw IOException("Empty body")
                successFetch = true
            } catch (e: Exception) {
                updateNotification(notificationId, title, "Waiting for network...", 0, 0, true, fileId, false)
                delay(5000)
            }
        }
        
        if (content.contains("EXT-X-STREAM-INF")) {
            val lines = content.split("\n")
            for (line in lines) {
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    baseUrl = URI(m3u8Url).resolve(line.trim()).toString()
                    successFetch = false
                    while (!successFetch && serviceScope.isActive) {
                        suspendIfPaused(fileId)
                        try {
                            val req = getHeaders(fileId, baseUrl).build()
                            val res = client.newCall(req).execute()
                            content = res.body?.string() ?: ""
                            successFetch = true
                        } catch (e: Exception) {
                            delay(3000)
                        }
                    }
                    break
                }
            }
        }
        
        val lines = content.split("\n")
        for (line in lines) {
            if (line.isNotEmpty() && !line.startsWith("#")) {
                segments.add(URI(baseUrl).resolve(line.trim()).toString())
            }
        }
        if (segments.isEmpty()) throw Exception("No video segments found")

        val dir = MediaStorageUtils.getMediaDirectory(this)
        val destFile = MediaStorageUtils.getDestinationFile(this, fileId, "mp4")
        if (destFile.exists()) destFile.delete()

        val downloadedCount = AtomicInteger(0)
        val semaphore = Semaphore(maxConcurrent)
        
        val tempDir = File(dir, "temp_$fileId")
        if (!tempDir.exists()) tempDir.mkdirs()
        
        // Count already downloaded segments
        for (i in segments.indices) {
            val tempFile = File(tempDir, "seg_$i.ts")
            if (tempFile.exists() && tempFile.length() > 0) {
                downloadedCount.incrementAndGet()
            }
        }
        
        val deferreds = segments.mapIndexed { index, segmentUrl ->
            serviceScope.async(Dispatchers.IO) {
                semaphore.withPermit {
                    val tempFile = File(tempDir, "seg_$index.ts")
                    if (tempFile.exists() && tempFile.length() > 0) return@withPermit
                    
                    var success = false
                    while (!success && serviceScope.isActive) {
                        suspendIfPaused(fileId)
                        try {
                            val sReq = getHeaders(fileId, segmentUrl).build()
                            val sRes = client.newCall(sReq).execute()
                            if (sRes.isSuccessful) {
                                val sStream = sRes.body?.byteStream()
                                val sOutput = FileOutputStream(tempFile)
                                val sBuffer = ByteArray(256 * 1024)
                                var sRead: Int
                                while (sStream?.read(sBuffer).also { sRead = it ?: -1 } != -1 && serviceScope.isActive) {
                                    suspendIfPaused(fileId)
                                    sOutput.write(sBuffer, 0, sRead)
                                }
                                sOutput.close()
                                sRes.close()
                                success = true
                                val c = downloadedCount.incrementAndGet()
                                val percentage = (c * 100) / segments.size
                                updateNotification(notificationId, title, getString(R.string.downloading_percent, percentage), c, segments.size, true, fileId, false)
                                updateDbProgress(fileId, c.toFloat() / segments.size.toFloat())
                            } else {
                                throw IOException("Bad response ${sRes.code}")
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            updateNotification(notificationId, title, getString(R.string.waiting_for_network), downloadedCount.get(), segments.size, true, fileId, false)
                            delay(5000)
                        }
                    }
                }
            }
        }
        deferreds.awaitAll()
        
        updateNotification(notificationId, title, getString(R.string.merging_video), segments.size, segments.size, true, fileId, false)
        val finalOutput = FileOutputStream(destFile, true)
        for (i in segments.indices) {
            val tempFile = File(tempDir, "seg_$i.ts")
            if (tempFile.exists()) {
                val input = tempFile.inputStream()
                val buffer = ByteArray(256 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    finalOutput.write(buffer, 0, read)
                }
                input.close()
                tempFile.delete()
            }
        }
        finalOutput.close()
        tempDir.delete()

        updateNotification(notificationId, title, getString(R.string.download_complete_simple), 0, 0, false, fileId, false)
        updateDbProgress(fileId, 1.0f, true, fileSizeBytes = destFile.length())
    }

    private fun createNotificationChannel() {
        com.example.data.notification.NotificationChannels.createAllChannels(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
