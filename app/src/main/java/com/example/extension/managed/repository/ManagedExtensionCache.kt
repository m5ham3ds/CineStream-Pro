package com.example.extension.managed.repository

import com.example.domain.models.AudioCapability
import com.example.domain.models.GroupStatus
import com.example.extension.managed.model.*
import com.example.extension.managed.searchorder.SearchOrder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Contract for caching verified ManagedExtension definitions and complete Runtime Snapshots.
 */
interface ManagedExtensionCache {
    fun getCached(): List<ManagedExtension>?
    fun getLastKnownGood(): List<ManagedExtension>? = getCached()
    fun saveCache(extensions: List<ManagedExtension>)
    fun saveSnapshot(snapshot: ExtensionRuntimeSnapshot) {}
    fun loadSnapshot(): ExtensionRuntimeSnapshot? = null
    fun clear()
    fun clearCache() = clear()
    fun isExpired(): Boolean
    fun getCachedTimestamp(): Long
    fun recordTombstones(disabledIds: Set<String>, removedIds: Set<String>) {}
    fun getDisabledTombstones(): Set<String> = emptySet()
    fun getRemovedTombstones(): Set<String> = emptySet()
    fun clearTombstones() {}
}

/**
 * Thread-safe persistent local metadata cache with configurable TTL (default: 30 minutes).
 * Enforces Phase 05M Requirements:
 * 1. Persistent disk storage surviving process death and application restarts.
 * 2. Atomic disk storage (write temporary -> flush -> atomic replace).
 * 3. Re-validation upon cache retrieval to prevent corrupted/tampered metadata exposure.
 * 4. Strict preservation of Admin DISABLED status across restarts and offline mode.
 * 5. Full ExtensionRuntimeSnapshot persistence (extensions + searchOrder + revision + syncedAt).
 * 6. F-017 Tombstone safety: locally removed and disabled extensions cannot be resurrected by LKG or restarts.
 */
class SafeLocalMetadataCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val storageDir: File? = null
) : ManagedExtensionCache {

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 30 * 60 * 1000L // 30 minutes
        const val CACHE_FILE_NAME = "managed_extensions_lkg.json"
        const val SNAPSHOT_FILE_NAME = "runtime_snapshot_lkg.json"
        const val TOMBSTONES_FILE_NAME = "extension_tombstones.json"
    }

    private val cachedData = AtomicReference<List<ManagedExtension>?>(null)
    private val cachedSnapshot = AtomicReference<ExtensionRuntimeSnapshot?>(null)
    private val cachedTimestamp = AtomicLong(0L)
    private val disabledTombstones = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val removedTombstones = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        // Attempt immediate restoration from persistent disk cache and tombstones on startup
        loadTombstonesFromDisk()
        loadFromDisk()
    }

    override fun recordTombstones(disabledIds: Set<String>, removedIds: Set<String>) {
        if (disabledIds.isNotEmpty()) {
            disabledTombstones.addAll(disabledIds.map { it.lowercase() })
        }
        if (removedIds.isNotEmpty()) {
            removedTombstones.addAll(removedIds.map { it.lowercase() })
        }
        persistTombstonesToDisk()

        // Immediately reconcile in-memory cache against new tombstones
        cachedData.get()?.let { list ->
            val reconciled = list
                .filterNot { removedTombstones.contains(it.id.lowercase()) }
                .map { ext ->
                    if (disabledTombstones.contains(ext.id.lowercase())) {
                        ext.copy(status = ExtensionLifecycleStatus.DISABLED)
                    } else ext
                }
            cachedData.set(reconciled)
        }
    }

    override fun getDisabledTombstones(): Set<String> = disabledTombstones.toSet()

    override fun getRemovedTombstones(): Set<String> = removedTombstones.toSet()

    override fun clearTombstones() {
        disabledTombstones.clear()
        removedTombstones.clear()
        val dir = resolveDir() ?: return
        try {
            val file = File(dir, TOMBSTONES_FILE_NAME)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    private fun loadTombstonesFromDisk() {
        val dir = resolveDir() ?: return
        val file = File(dir, TOMBSTONES_FILE_NAME)
        if (!file.exists() || !file.canRead()) return
        try {
            val jsonText = file.readText()
            if (jsonText.isNotBlank()) {
                val root = JSONObject(jsonText)
                val disArr = root.optJSONArray("disabled")
                if (disArr != null) {
                    for (i in 0 until disArr.length()) {
                        disabledTombstones.add(disArr.getString(i).lowercase())
                    }
                }
                val remArr = root.optJSONArray("removed")
                if (remArr != null) {
                    for (i in 0 until remArr.length()) {
                        removedTombstones.add(remArr.getString(i).lowercase())
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun persistTombstonesToDisk() {
        val dir = resolveDir() ?: return
        val root = JSONObject()
        root.put("disabled", JSONArray(disabledTombstones.toList()))
        root.put("removed", JSONArray(removedTombstones.toList()))
        writeAtomically(dir, TOMBSTONES_FILE_NAME, root.toString())
    }

    private fun resolveDir(): File? {
        return storageDir ?: try {
            com.example.MyApplication.appContext.filesDir
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadFromDisk() {
        val dir = resolveDir() ?: return
        
        // 1. Try reading full runtime snapshot file
        val snapshotFile = File(dir, SNAPSHOT_FILE_NAME)
        if (snapshotFile.exists() && snapshotFile.canRead()) {
            try {
                val jsonText = snapshotFile.readText()
                if (jsonText.isNotBlank()) {
                    val root = JSONObject(jsonText)
                    val rev = root.optLong("revision", 0L)
                    val syncedAt = root.optLong("syncedAt", snapshotFile.lastModified())
                    val stateStr = root.optString("state", GlobalExtensionConfigState.READY.name)
                    val state = try {
                        GlobalExtensionConfigState.valueOf(stateStr.uppercase())
                    } catch (_: Exception) {
                        GlobalExtensionConfigState.READY
                    }
                    
                    val soObj = root.optJSONObject("searchOrder")
                    val searchOrder = if (soObj != null) {
                        fun jsonToStringList(key: String): List<String> {
                            val arr = soObj.optJSONArray(key) ?: return emptyList()
                            val l = mutableListOf<String>()
                            for (i in 0 until arr.length()) {
                                val s = arr.getString(i).trim()
                                if (s.isNotEmpty()) l.add(s)
                            }
                            return l
                        }
                        val rawArabicStatus = soObj.optString("arabicDubbedStatus").ifBlank {
                            soObj.optString("arabicDubStatus").ifBlank { soObj.optString("arabic_dub_status") }
                        }
                        val arabicStatus = if (rawArabicStatus.isNotBlank()) GroupStatus.fromString(rawArabicStatus) else GroupStatus.ACTIVE
                        val rawEnglishStatus = soObj.optString("englishDubbedStatus").ifBlank {
                            soObj.optString("englishDubStatus").ifBlank { soObj.optString("english_dub_status") }
                        }
                        val englishStatus = if (rawEnglishStatus.isNotBlank()) GroupStatus.fromString(rawEnglishStatus) else GroupStatus.MAINTENANCE
                        val arabicDubbedList = jsonToStringList("arabicDubbed").ifEmpty {
                            jsonToStringList("arabicDub").ifEmpty { jsonToStringList("arabic_dub") }
                        }
                        val englishDubbedList = jsonToStringList("englishDubbed").ifEmpty {
                            jsonToStringList("englishDub").ifEmpty { jsonToStringList("english_dub") }
                        }
                        SearchOrder(
                            movie = jsonToStringList("movie"),
                            tv = if (soObj.has("tv")) jsonToStringList("tv") else null,
                            series = jsonToStringList("series"),
                            anime = jsonToStringList("anime"),
                            arabicDubbed = arabicDubbedList,
                            englishDubbed = englishDubbedList,
                            arabicDubbedStatus = arabicStatus,
                            englishDubbedStatus = englishStatus,
                            updatedAt = soObj.optLong("updatedAt").takeIf { it > 0 },
                            updatedBy = soObj.optString("updatedBy").takeIf { it.isNotBlank() }
                        )
                    } else SearchOrder()

                    val jsonArray = root.optJSONArray("extensions") ?: JSONArray()
                    val list = mutableListOf<ManagedExtension>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val ext = jsonToExtension(obj)
                        if (ext != null && !removedTombstones.contains(ext.id.lowercase()) && ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid) {
                            val resolved = if (disabledTombstones.contains(ext.id.lowercase())) {
                                ext.copy(status = ExtensionLifecycleStatus.DISABLED)
                            } else ext
                            list.add(resolved)
                        }
                    }

                    if (list.isNotEmpty() || !searchOrder.isEmpty) {
                        val snapshot = ExtensionRuntimeSnapshot(
                            revision = rev,
                            extensions = list,
                            searchOrder = searchOrder,
                            syncedAt = syncedAt,
                            source = SnapshotSource.LOCAL_LKG,
                            state = state
                        )
                        cachedSnapshot.set(snapshot)
                        cachedData.set(list)
                        cachedTimestamp.set(syncedAt)
                        return
                    }
                }
            } catch (_: Throwable) {}
        }

        // 2. Fallback: try reading legacy managed_extensions_lkg.json
        val file = File(dir, CACHE_FILE_NAME)
        if (!file.exists() || !file.canRead()) return

        try {
            val jsonText = file.readText()
            if (jsonText.isBlank()) return
            val jsonArray = JSONArray(jsonText)
            val list = mutableListOf<ManagedExtension>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ext = jsonToExtension(obj)
                if (ext != null && !removedTombstones.contains(ext.id.lowercase()) && ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid) {
                    val resolved = if (disabledTombstones.contains(ext.id.lowercase())) {
                        ext.copy(status = ExtensionLifecycleStatus.DISABLED)
                    } else ext
                    list.add(resolved)
                }
            }
            if (list.isNotEmpty()) {
                cachedData.set(list)
                cachedTimestamp.set(file.lastModified())
                cachedSnapshot.set(
                    ExtensionRuntimeSnapshot(
                        revision = file.lastModified(),
                        extensions = list,
                        searchOrder = SearchOrder(),
                        syncedAt = file.lastModified(),
                        source = SnapshotSource.LOCAL_LKG,
                        state = GlobalExtensionConfigState.READY
                    )
                )
            }
        } catch (_: Throwable) {}
    }

    /**
     * Atomically writes string content to target file via temp file + flush + atomic move/replace.
     */
    private fun writeAtomically(dir: File, targetFileName: String, content: String) {
        if (!dir.exists()) dir.mkdirs()
        val tempFile = File(dir, "$targetFileName.tmp_${System.currentTimeMillis()}")
        val targetFile = File(dir, targetFileName)

        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {}
            }

            // Atomic replace
            var replaced = tempFile.renameTo(targetFile)
            if (!replaced) {
                try {
                    java.nio.file.Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    replaced = true
                } catch (_: Throwable) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                    replaced = true
                }
            }
        } catch (_: Throwable) {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {}
        }
    }

    private fun persistToDisk(extensions: List<ManagedExtension>) {
        val dir = resolveDir() ?: return
        val jsonArray = JSONArray()
        for (ext in extensions) {
            jsonArray.put(extensionToJson(ext))
        }
        writeAtomically(dir, CACHE_FILE_NAME, jsonArray.toString())
    }

    override fun saveSnapshot(snapshot: ExtensionRuntimeSnapshot) {
        val validatedExtensions = snapshot.extensions.filter { ext ->
            !removedTombstones.contains(ext.id.lowercase()) &&
            ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid
        }.map { ext ->
            if (disabledTombstones.contains(ext.id.lowercase())) {
                ext.copy(status = ExtensionLifecycleStatus.DISABLED)
            } else ext
        }
        val cleanSnapshot = snapshot.copy(extensions = validatedExtensions)
        cachedSnapshot.set(cleanSnapshot)
        cachedData.set(validatedExtensions)
        val now = clock()
        cachedTimestamp.set(now)

        val dir = resolveDir() ?: return

        // 1. Persist full snapshot JSON
        val root = JSONObject()
        root.put("revision", cleanSnapshot.revision)
        root.put("syncedAt", cleanSnapshot.syncedAt)
        root.put("source", cleanSnapshot.source.name)
        root.put("state", cleanSnapshot.state.name)

        val soObj = JSONObject()
        soObj.put("movie", JSONArray(cleanSnapshot.searchOrder.movie))
        if (cleanSnapshot.searchOrder.tv != null) {
            soObj.put("tv", JSONArray(cleanSnapshot.searchOrder.tv))
        }
        soObj.put("series", JSONArray(cleanSnapshot.searchOrder.series))
        soObj.put("anime", JSONArray(cleanSnapshot.searchOrder.anime))
        soObj.put("arabicDubbed", JSONArray(cleanSnapshot.searchOrder.arabicDubbed))
        soObj.put("englishDubbed", JSONArray(cleanSnapshot.searchOrder.englishDubbed))
        soObj.put("arabicDubbedStatus", cleanSnapshot.searchOrder.arabicDubbedStatus.name)
        soObj.put("englishDubbedStatus", cleanSnapshot.searchOrder.englishDubbedStatus.name)
        if (cleanSnapshot.searchOrder.updatedAt != null) {
            soObj.put("updatedAt", cleanSnapshot.searchOrder.updatedAt)
        }
        if (cleanSnapshot.searchOrder.updatedBy != null) {
            soObj.put("updatedBy", cleanSnapshot.searchOrder.updatedBy)
        }
        root.put("searchOrder", soObj)

        val jsonArray = JSONArray()
        for (ext in validatedExtensions) {
            jsonArray.put(extensionToJson(ext))
        }
        root.put("extensions", jsonArray)

        writeAtomically(dir, SNAPSHOT_FILE_NAME, root.toString())

        // 2. Also write legacy file for backward compatibility
        writeAtomically(dir, CACHE_FILE_NAME, jsonArray.toString())
    }

    override fun loadSnapshot(): ExtensionRuntimeSnapshot? {
        if (cachedSnapshot.get() == null) {
            loadFromDisk()
        }
        return cachedSnapshot.get()
    }

    override fun getCached(): List<ManagedExtension>? {
        if (cachedData.get() == null) {
            loadFromDisk()
        }
        val data = cachedData.get() ?: return null
        if (isExpired()) {
            return null
        }

        // Re-validate cached items to ensure integrity and respect tombstones
        val validItems = data
            .filterNot { removedTombstones.contains(it.id.lowercase()) }
            .map { ext ->
                if (disabledTombstones.contains(ext.id.lowercase())) {
                    ext.copy(status = ExtensionLifecycleStatus.DISABLED)
                } else ext
            }
            .filter { ext ->
                ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid
            }

        return if (validItems.isNotEmpty()) validItems else null
    }

    override fun getLastKnownGood(): List<ManagedExtension>? {
        if (cachedData.get() == null) {
            loadFromDisk()
        }
        val data = cachedData.get() ?: return null

        // Re-validate cached items to ensure integrity (regardless of TTL expiration) and respect tombstones
        val validItems = data
            .filterNot { removedTombstones.contains(it.id.lowercase()) }
            .map { ext ->
                if (disabledTombstones.contains(ext.id.lowercase())) {
                    ext.copy(status = ExtensionLifecycleStatus.DISABLED)
                } else ext
            }
            .filter { ext ->
                ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid
            }

        return if (validItems.isNotEmpty()) validItems else null
    }

    override fun saveCache(extensions: List<ManagedExtension>) {
        // Only save validated extensions
        val validated = extensions.filter { ext ->
            ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid
        }
        cachedData.set(validated)
        val now = clock()
        cachedTimestamp.set(now)
        persistToDisk(validated)

        // Also update snapshot
        val current = cachedSnapshot.get()
        val updatedSnapshot = if (current != null) {
            current.copy(extensions = validated, syncedAt = now)
        } else {
            ExtensionRuntimeSnapshot(
                revision = now,
                extensions = validated,
                syncedAt = now,
                source = SnapshotSource.LOCAL_LKG,
                state = GlobalExtensionConfigState.READY
            )
        }
        cachedSnapshot.set(updatedSnapshot)
        val dir = resolveDir()
        if (dir != null) {
            saveSnapshot(updatedSnapshot)
        }
    }

    override fun clear() {
        cachedData.set(null)
        cachedSnapshot.set(null)
        cachedTimestamp.set(0L)
        val dir = resolveDir()
        if (dir != null) {
            try {
                val file = File(dir, CACHE_FILE_NAME)
                if (file.exists()) file.delete()
                val snapshotFile = File(dir, SNAPSHOT_FILE_NAME)
                if (snapshotFile.exists()) snapshotFile.delete()
            } catch (_: Throwable) {}
        }
    }

    override fun isExpired(): Boolean {
        val timestamp = cachedTimestamp.get()
        if (timestamp == 0L) return true
        return (clock() - timestamp) > ttlMillis
    }

    override fun getCachedTimestamp(): Long {
        return cachedTimestamp.get()
    }

    private fun extensionToJson(ext: ManagedExtension): JSONObject {
        val obj = JSONObject()
        obj.put("id", ext.id)
        obj.put("name", ext.name)
        obj.put("description", ext.description)
        obj.put("baseUrl", ext.baseUrl)
        obj.put("iconUrl", ext.iconUrl)
        obj.put("scraperKey", ext.scraperKey)
        obj.put("definitionVersion", ext.definitionVersion)
        obj.put("minAppVersionCode", ext.minAppVersionCode)
        obj.put("runtimeApiVersion", ext.runtimeApiVersion)
        obj.put("priority", ext.priority)
        obj.put("language", ext.language)
        val ctArr = JSONArray()
        for (ct in ext.contentTypes) {
            ctArr.put(ct.name)
        }
        obj.put("contentTypes", ctArr)

        val capArr = JSONArray()
        for (cap in ext.capabilities) {
            capArr.put(cap.name)
        }
        obj.put("capabilities", capArr)

        val audArr = JSONArray()
        for (aud in ext.audioCapabilities) {
            audArr.put(aud.name)
        }
        obj.put("audioCapabilities", audArr)

        if (ext.searchUrl != null) {
            obj.put("searchUrl", ext.searchUrl)
        }
        obj.put("enabled", ext.enabled)
        obj.put("status", ext.status.name)
        obj.put("updatedAt", ext.updatedAt)
        obj.put("userEnabled", ext.userEnabled)
        return obj
    }

    private fun jsonToExtension(obj: JSONObject): ManagedExtension? {
        return try {
            val id = obj.getString("id")
            val name = obj.getString("name")
            val desc = obj.optString("description", "")
            val baseUrl = obj.getString("baseUrl")
            val iconUrl = obj.optString("iconUrl", "")
            val scraperKey = obj.getString("scraperKey")
            val defVer = obj.optInt("definitionVersion", 1)
            val minAppVer = obj.optLong("minAppVersionCode", 1L)
            val runtimeVer = obj.optInt("runtimeApiVersion", 1)
            val priority = obj.optInt("priority", 0)
            val lang = obj.optString("language", "ar")
            val ctArr = obj.optJSONArray("contentTypes")
            val contentTypes = mutableSetOf<ContentType>()
            if (ctArr != null) {
                for (i in 0 until ctArr.length()) {
                    val str = ctArr.getString(i)
                    try {
                        contentTypes.add(ContentType.valueOf(str.uppercase()))
                    } catch (_: Exception) {}
                }
            }

            val capArr = obj.optJSONArray("capabilities")
            val capabilities = mutableSetOf<ScraperCapability>()
            if (capArr != null) {
                for (i in 0 until capArr.length()) {
                    val str = capArr.getString(i)
                    try {
                        capabilities.add(ScraperCapability.valueOf(str.uppercase()))
                    } catch (_: Exception) {}
                }
            }

            val audArr = obj.optJSONArray("audioCapabilities")
            val audioCapabilities = mutableSetOf<AudioCapability>()
            if (audArr != null) {
                for (i in 0 until audArr.length()) {
                    val str = audArr.getString(i)
                    AudioCapability.fromString(str)?.let { audioCapabilities.add(it) }
                }
            }

            val searchUrl = obj.optString("searchUrl").trim().takeIf { it.isNotBlank() }
            val enabled = obj.optBoolean("enabled", true)

            val statusStr = obj.optString("status", ExtensionLifecycleStatus.ACTIVE.name)
            val status = try {
                ExtensionLifecycleStatus.valueOf(statusStr.uppercase())
            } catch (_: Exception) {
                ExtensionLifecycleStatus.ACTIVE
            }
            val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
            val userEnabled = obj.optBoolean("userEnabled", true)

            ManagedExtension(
                id = id,
                name = name,
                description = desc,
                baseUrl = baseUrl,
                iconUrl = iconUrl,
                scraperKey = scraperKey,
                definitionVersion = defVer,
                minAppVersionCode = minAppVer,
                runtimeApiVersion = runtimeVer,
                priority = priority,
                language = lang,
                contentTypes = contentTypes,
                capabilities = capabilities,
                audioCapabilities = audioCapabilities,
                searchUrl = searchUrl,
                enabled = enabled,
                status = status,
                updatedAt = updatedAt,
                userEnabled = userEnabled
            )
        } catch (_: Exception) {
            null
        }
    }
}
