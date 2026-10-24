package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.model.DownloadItem
import com.example.utils.NotificationHelper
import kotlinx.coroutines.flow.Flow
import java.io.File

class DownloadRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val downloadDao = db.downloadDao()

    fun getDownloadItems(): Flow<List<DownloadItem>> {
        return downloadDao.getAllItems()
    }
    
    suspend fun getAllItemsSync(): List<DownloadItem> {
        return downloadDao.getAllItemsSync()
    }
    
    fun getDownloadItemById(id: String): Flow<DownloadItem?> {
        return downloadDao.getItemByIdFlow(id)
    }

    suspend fun getItemByIdSync(id: String): DownloadItem? {
        return downloadDao.getItemById(id)
    }

    suspend fun addCompletedDownload(item: DownloadItem) {
        downloadDao.insertItem(item)
        com.example.utils.DownloadedPostersManager.savePosterLocally(context, item.mediaId, item.posterUrl)
    }

    suspend fun addToDownloads(item: DownloadItem) {
        downloadDao.insertItem(item)
        NotificationHelper.showDownloadStarted(context, item.title)
        com.example.utils.DownloadedPostersManager.savePosterLocally(context, item.mediaId, item.posterUrl)
    }

    suspend fun updateDownload(item: DownloadItem) {
        downloadDao.updateItem(item)
        if (item.posterUrl.isNotBlank()) {
            com.example.utils.DownloadedPostersManager.savePosterLocally(context, item.mediaId, item.posterUrl)
        }
        if (item.isPaused) {
            DownloadManagerService.pauseDownload(item.id)
        }
    }

    suspend fun removeFromDownloads(item: DownloadItem) {
        downloadDao.deleteItem(item)
        DownloadManagerService.pauseDownload(item.id)
        val file = com.example.utils.MediaStorageUtils.findMediaFile(context, item.id)
        if (file != null && file.exists()) {
            file.delete()
        }
        // Only delete the local poster file if no other download uses the same mediaId
        val remaining = downloadDao.getAllItemsSync().filter { it.mediaId == item.mediaId }
        if (remaining.isEmpty()) {
            com.example.utils.DownloadedPostersManager.removePoster(context, item.mediaId)
        }
    }

    suspend fun removeFromDownloads(id: String) {
        val item = getItemByIdSync(id)
        if (item != null) {
            removeFromDownloads(item)
        } else {
            DownloadManagerService.pauseDownload(id)
            val file = com.example.utils.MediaStorageUtils.findMediaFile(context, id)
            if (file != null && file.exists()) {
                file.delete()
            }
        }
    }
}