package com.example.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.data.model.DownloadItem
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

object DeviceStorageExporter {

    data class ExportResult(
        val success: Boolean,
        val filePath: String = "",
        val errorMessage: String? = null
    )

    data class SeriesInfo(
        val seriesName: String,
        val episodeLabel: String
    )

    fun sanitizeFilename(name: String): String {
        return name
            .replace(":", " -")
            .replace(Regex("[\\\\/*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "Media" }
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase(Locale.ROOT)) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
    }

    fun extractSeriesInfo(item: DownloadItem): SeriesInfo {
        val title = item.title.trim()

        // 1. Check for " - S..E.."
        val sMatch = Regex("(.+?)\\s*-\\s*(S\\d+E\\d+.*)", RegexOption.IGNORE_CASE).find(title)
        if (sMatch != null) {
            val series = sMatch.groupValues[1].trim()
            val ep = sMatch.groupValues[2].trim()
            return SeriesInfo(sanitizeFilename(series), ep)
        }

        // 2. Check for " - الحلقة" (Arabic episode)
        val arIndex = title.indexOf(" - الحلقة", ignoreCase = true)
        if (arIndex != -1) {
            val series = title.substring(0, arIndex).trim()
            val ep = title.substring(arIndex).trim().removePrefix("-").trim()
            return SeriesInfo(sanitizeFilename(series), ep)
        }

        // 3. Check for " - Episode"
        val epIndex = title.indexOf(" - Episode", ignoreCase = true)
        if (epIndex != -1) {
            val series = title.substring(0, epIndex).trim()
            val ep = title.substring(epIndex).trim().removePrefix("-").trim()
            return SeriesInfo(sanitizeFilename(series), ep)
        }

        // 4. Check for generic " - "
        if (title.contains(" - ")) {
            val series = title.substringBefore(" - ").trim()
            val ep = title.substringAfter(" - ").trim()
            return SeriesInfo(sanitizeFilename(series), ep)
        }

        return SeriesInfo(sanitizeFilename(title), title)
    }

    fun getDisplayEpisodeName(item: DownloadItem, seriesName: String = ""): String {
        val title = item.title.trim()
        val cleanSeries = seriesName.ifBlank { extractSeriesInfo(item).seriesName }

        val sMatch = Regex("(.+?)\\s*-\\s*S(\\d+)E(\\d+)(.*)", RegexOption.IGNORE_CASE).find(title)
        if (sMatch != null) {
            val sName = sMatch.groupValues[1].trim()
            val sNum = sMatch.groupValues[2].toIntOrNull() ?: 1
            val eNum = sMatch.groupValues[3].toIntOrNull() ?: 1
            val extra = sMatch.groupValues[4].trim().removePrefix("-").trim()
            val formattedEp = String.format(Locale.US, "S%02dE%02d", sNum, eNum)
            val finalTitle = if (extra.isNotBlank()) "$sName - $formattedEp - $extra" else "$sName - $formattedEp"
            return sanitizeFilename(finalTitle)
        }

        val sOnly = Regex("S(\\d+)E(\\d+)(.*)", RegexOption.IGNORE_CASE).find(title)
        if (sOnly != null) {
            val sNum = sOnly.groupValues[1].toIntOrNull() ?: 1
            val eNum = sOnly.groupValues[2].toIntOrNull() ?: 1
            val extra = sOnly.groupValues[3].trim().removePrefix("-").trim()
            val formattedEp = String.format(Locale.US, "S%02dE%02d", sNum, eNum)
            val prefix = if (cleanSeries.isNotBlank()) "$cleanSeries - " else ""
            val finalTitle = if (extra.isNotBlank()) "$prefix$formattedEp - $extra" else "$prefix$formattedEp"
            return sanitizeFilename(finalTitle)
        }

        if (title.contains(" - ")) {
            return sanitizeFilename(title)
        }

        if (cleanSeries.isNotBlank() && !title.startsWith(cleanSeries, ignoreCase = true)) {
            return sanitizeFilename("$cleanSeries - $title")
        }

        return sanitizeFilename(title.ifBlank { "Episode_${item.id}" })
    }

    fun getDisplayMovieName(item: DownloadItem): String {
        return sanitizeFilename(item.title.ifBlank { "Movie_${item.mediaId}" })
    }

    fun extractEpisodeNumber(item: DownloadItem): Int {
        val regex = Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(item.title)
        if (regex != null) {
            return regex.groupValues[1].toIntOrNull() ?: 0
        }
        val digitRegex = Regex("\\d+").find(item.title)
        return digitRegex?.value?.toIntOrNull() ?: 0
    }

    suspend fun exportMovie(
        context: Context,
        item: DownloadItem,
        onProgress: ((Float) -> Unit)? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        val sourceFile = MediaStorageUtils.findMediaFile(context, item.id)
            ?: return@withContext ExportResult(false, errorMessage = context.getString(R.string.source_file_not_found))
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return@withContext ExportResult(false, errorMessage = context.getString(R.string.file_incomplete_or_empty))
        }

        val rawExt = sourceFile.extension.ifBlank { "mp4" }
        val ext = if (rawExt.all { it.isDigit() }) "mp4" else rawExt
        val cleanTitle = getDisplayMovieName(item)
        val fileName = "$cleanTitle.$ext"
        val relativePath = "Movies/CineStream"

        exportFileToPhone(context, sourceFile, relativePath, fileName, ext, onProgress)
    }

    suspend fun exportEpisode(
        context: Context,
        seriesName: String,
        item: DownloadItem,
        onProgress: ((Float) -> Unit)? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        val sourceFile = MediaStorageUtils.findMediaFile(context, item.id)
            ?: return@withContext ExportResult(false, errorMessage = context.getString(R.string.source_file_not_found))
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return@withContext ExportResult(false, errorMessage = context.getString(R.string.file_incomplete_or_empty))
        }

        val rawExt = sourceFile.extension.ifBlank { "mp4" }
        val ext = if (rawExt.all { it.isDigit() }) "mp4" else rawExt
        val cleanSeries = sanitizeFilename(seriesName)
        val epDisplayName = getDisplayEpisodeName(item, cleanSeries)
        val fileName = "$epDisplayName.$ext"
        val relativePath = "Movies/CineStream/$cleanSeries"

        exportFileToPhone(context, sourceFile, relativePath, fileName, ext, onProgress)
    }

    private fun exportFileToPhone(
        context: Context,
        sourceFile: File,
        relativePath: String,
        fileName: String,
        extension: String,
        onProgress: ((Float) -> Unit)?
    ): ExportResult {
        val mimeType = getMimeType(extension)
        val fullExpectedPath = "/storage/emulated/0/$relativePath/$fileName"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+ (Q, R, S, T, U, V...)
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val collectionUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = context.contentResolver.insert(collectionUri, contentValues)
                    ?: throw IOException("Failed to create media file in MediaStore")

                context.contentResolver.openOutputStream(itemUri)?.use { outStream ->
                    sourceFile.inputStream().use { inStream ->
                        val buffer = ByteArray(128 * 1024)
                        var read: Int
                        var totalRead = 0L
                        val totalBytes = sourceFile.length()
                        while (inStream.read(buffer).also { read = it } != -1) {
                            outStream.write(buffer, 0, read)
                            totalRead += read
                            if (totalBytes > 0) {
                                onProgress?.invoke(totalRead.toFloat() / totalBytes.toFloat())
                            }
                        }
                        outStream.flush()
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(itemUri, contentValues, null, null)

                try {
                    MediaScannerConnection.scanFile(context, arrayOf(fullExpectedPath), arrayOf(mimeType), null)
                } catch (_: Exception) {}

                return ExportResult(true, filePath = fullExpectedPath)
            } else {
                // Android 9 and below: direct public directory write
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val cleanSubPath = relativePath.removePrefix("Movies/").removePrefix("Movies")
                val targetDir = if (cleanSubPath.isNotBlank()) File(publicDir, cleanSubPath) else publicDir
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val targetFile = File(targetDir, fileName)
                sourceFile.inputStream().use { inStream ->
                    targetFile.outputStream().use { outStream ->
                        val buffer = ByteArray(128 * 1024)
                        var read: Int
                        var totalRead = 0L
                        val totalBytes = sourceFile.length()
                        while (inStream.read(buffer).also { read = it } != -1) {
                            outStream.write(buffer, 0, read)
                            totalRead += read
                            if (totalBytes > 0) {
                                onProgress?.invoke(totalRead.toFloat() / totalBytes.toFloat())
                            }
                        }
                        outStream.flush()
                    }
                }
                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
                return ExportResult(true, filePath = targetFile.absolutePath)
            }
        } catch (e: Exception) {
            // If MediaStore fails on custom ROMs, attempt direct fallback
            try {
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val cleanSubPath = relativePath.removePrefix("Movies/").removePrefix("Movies")
                val targetDir = if (cleanSubPath.isNotBlank()) File(publicDir, cleanSubPath) else publicDir
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                sourceFile.copyTo(targetFile, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
                return ExportResult(true, filePath = targetFile.absolutePath)
            } catch (fallbackEx: Exception) {
                return ExportResult(false, errorMessage = e.localizedMessage ?: context.getString(R.string.unexpected_error_saving))
            }
        }
    }
}
