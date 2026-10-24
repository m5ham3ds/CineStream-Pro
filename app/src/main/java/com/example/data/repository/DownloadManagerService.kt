package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.db.AppDatabase
import com.example.data.model.DownloadItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object DownloadManagerService {
    private var isInitialized = false
    private lateinit var appContext: Context
    private lateinit var downloadRepo: DownloadRepository
    private lateinit var userPrefs: UserPreferencesRepository
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder().build()
    private val activeDownloads = mutableMapOf<String, Job>()
    
    private var downloadSemaphore = Semaphore(3)
    private var currentMaxConcurrent = 3
    
    private lateinit var dir: File

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        appContext = context.applicationContext
        downloadRepo = DownloadRepository(appContext)
        userPrefs = UserPreferencesRepository(appContext)
        dir = File(appContext.filesDir, "downloads").apply { if (!exists()) mkdirs() }
        
        
    }
    
    private suspend fun processQueue() {
        val maxConn = userPrefs.maxConcurrentDownloads.first()
        if (maxConn != currentMaxConcurrent) {
            currentMaxConcurrent = maxConn
            downloadSemaphore = Semaphore(currentMaxConcurrent)
        }
        
        val networkAllowed = checkNetworkConstraint()
        if (!networkAllowed) {
            return
        }

        val allItems = downloadRepo.getAllItemsSync()
        val pending = allItems.filter { !it.isCompleted && !it.isPaused }
        
        for (item in pending) {
            if (!activeDownloads.containsKey(item.id)) {
                val job = scope.launch {
                    downloadSemaphore.withPermit {
                        try {
                            if (checkNetworkConstraint() && !item.isPaused) {
                                executeDownload(item.id)
                            }
                        } finally {
                            activeDownloads.remove(item.id)
                        }
                    }
                }
                activeDownloads[item.id] = job
            }
        }
    }
    
    fun pauseDownload(id: String) {
        activeDownloads[id]?.cancel()
        activeDownloads.remove(id)
    }

    private suspend fun checkNetworkConstraint(): Boolean {
        val allowedNet = userPrefs.downloadNetwork.first()
        if (allowedNet == 0) return true // All

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false

        return when (allowedNet) {
            1 -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            2 -> caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            else -> true
        }
    }

    private suspend fun executeDownload(id: String) {
        var item = downloadRepo.getItemByIdSync(id) ?: return
        
        val partsUrl = item.quality.split("||")
        val videoUrl = if (partsUrl.size > 1) partsUrl[1] else "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
        
        val maxSegments = userPrefs.maxSegments.first()
        val finalFile = File(dir, "${id}.mp4")
        
        if (finalFile.exists() && item.isCompleted) return

        // 1. Get file size and check range support
        val headReq = Request.Builder().url(videoUrl).head().build()
        val headRes = try { client.newCall(headReq).execute() } catch (e: Exception) { null }
        
        val contentLength = headRes?.header("Content-Length")?.toLongOrNull() ?: -1L
        val acceptsRanges = headRes?.header("Accept-Ranges") == "bytes"
        
        val actualSegments = if (contentLength > 0 && acceptsRanges && maxSegments > 1) maxSegments else 1
        
        var totalDownloaded = 0L
        val chunkJobs = mutableListOf<Job>()
        
        val chunkSize = if (contentLength > 0) contentLength / actualSegments else -1L
        
        coroutineScope {
            for (i in 0 until actualSegments) {
                val startByte = if (chunkSize > 0) i * chunkSize else 0L
                val endByte = if (chunkSize > 0) {
                    if (i == actualSegments - 1) contentLength - 1 else startByte + chunkSize - 1
                } else -1L
                
                val partFile = File(dir, "${id}.part$i")
                val expectedPartSize = if (chunkSize > 0) (endByte - startByte + 1) else -1L
                
                val job = launch(Dispatchers.IO) {
                    downloadChunk(videoUrl, partFile, startByte, endByte, expectedPartSize) { bytesRead ->
                        totalDownloaded += bytesRead
                        if (contentLength > 0) {
                            val progress = (totalDownloaded.toFloat() / contentLength.toFloat()).coerceAtMost(0.99f)
                            // Throttle DB updates to avoid UI stuttering
                            if (progress - item.progress > 0.01f || progress == 0.99f) {
                                item = item.copy(progress = progress)
                                scope.launch { downloadRepo.updateDownload(item) }
                            }
                        }
                    }
                }
                chunkJobs.add(job)
            }
            
            totalDownloaded = 0L
            for (i in 0 until actualSegments) {
                val partFile = File(dir, "${id}.part$i")
                if (partFile.exists()) {
                    totalDownloaded += partFile.length()
                }
            }
            
            try {
                chunkJobs.joinAll()
            } catch (e: CancellationException) {
                throw e
            }
        }
        
        // Merge
        val mergeSuccess = mergeParts(id, actualSegments, finalFile)
        if (mergeSuccess) {
            val finalLength = finalFile.length()
            val finalSize = if (finalLength > 0L) finalLength else if (contentLength > 0L) contentLength else 0L
            item = item.copy(progress = 1f, isCompleted = true, fileSizeBytes = finalSize)
            downloadRepo.updateDownload(item)
            com.example.utils.NotificationHelper.showDownloadCompleted(appContext, item.title)
        }
    }
    
    private suspend fun downloadChunk(url: String, partFile: File, startByte: Long, endByte: Long, expectedSize: Long, onProgress: (Int) -> Unit) {
        if (!checkNetworkConstraint()) throw CancellationException("Network constraint")
        
        val currentSize = if (partFile.exists()) partFile.length() else 0L
        if (expectedSize > 0 && currentSize >= expectedSize) return // Done
        
        val requestBuilder = Request.Builder().url(url)
        if (expectedSize > 0) {
            val rangeStart = startByte + currentSize
            requestBuilder.addHeader("Range", "bytes=$rangeStart-$endByte")
        }
        
        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) return
        
        val body = response.body ?: return
        val inputStream: InputStream = body.byteStream()
        val outputStream = FileOutputStream(partFile, true)
        
        // High-performance 256KB buffer for fast network streaming & disk writing
        val buffer = ByteArray(256 * 1024)
        var bytesRead: Int
        
        inputStream.use { input ->
            outputStream.use { output ->
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    if (!checkNetworkConstraint()) throw CancellationException("Network constraint")
                    
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    
                    output.write(buffer, 0, bytesRead)
                    onProgress(bytesRead)
                }
            }
        }
    }
    
    private fun mergeParts(id: String, segments: Int, finalFile: File): Boolean {
        try {
            val outputStream = FileOutputStream(finalFile)
            outputStream.use { out ->
                for (i in 0 until segments) {
                    val partFile = File(dir, "${id}.part$i")
                    if (partFile.exists()) {
                        val input = partFile.inputStream()
                        input.copyTo(out, bufferSize = 256 * 1024)
                        input.close()
                        partFile.delete()
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
