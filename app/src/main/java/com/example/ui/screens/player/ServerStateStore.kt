package com.example.ui.screens.player

import android.util.Log
import com.example.extension.managed.model.QualityAggregator
import com.example.extension.managed.model.QualityCandidate
import com.example.extension.managed.model.QualityEvidence
import com.example.extension.managed.web.StaticMediaExtractor
import com.example.utils.M3U8Parser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class MediaServerData(
    val mediaKey: String = "",
    val mediaId: String = "",
    val servers: List<String> = emptyList(),
    val serverLinks: Map<String, String> = emptyMap(),
    val serverIds: Map<String, String> = emptyMap(),
    val downloadLinks: Map<String, String> = emptyMap(),
    val serverQualities: Map<String, List<M3U8Parser.QualityInfo>> = emptyMap(),
    val extractedQualities: List<M3U8Parser.QualityInfo> = emptyList(),
    val internalCandidates: Map<String, List<QualityCandidate>> = emptyMap(),
    val website: String = "",
    val playbackPageUrl: String? = null,
    val scraperKey: String? = null,
    val directStreamUrl: String? = null,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis(),
    val variantMode: String = "subtitled",
    val audioLanguage: String = "ar"
)

fun buildMediaKey(
    title: String,
    isMovie: Boolean,
    season: Int = 1,
    episode: Int = 1,
    variantMode: String = "subtitled",
    audioLang: String = "ar"
): String {
    val cleanTitle = title.trim()
    val cleanMode = variantMode.trim().lowercase()
    val cleanLang = audioLang.trim().lowercase()
    return "$cleanTitle-$isMovie-$season-$episode-$cleanMode-$cleanLang"
}

fun isAutoQuality(rawName: String?): Boolean {
    if (rawName.isNullOrBlank()) return true
    val trimmed = rawName.trim()
    val lower = trimmed.lowercase()

    // 1. If it contains an explicit standard numeric resolution, prioritize resolution
    val hasStandardRes = Regex("""(?i)\b(1080|720|480|360|240|144)p?\b""").containsMatchIn(lower) ||
        Regex("""(?i)(?:^|[\s_\-/\.x])(1080|720|480|360|240|144)p?(?:[\s_\-/\.]|$)""").containsMatchIn(lower) ||
        lower.contains("1920x1080") || lower.contains("1280x720") || lower.contains("854x480") || lower.contains("640x360") ||
        Regex("""(?i)\b(fhd|4k|2160|1440|2k)\b""").containsMatchIn(lower)

    if (hasStandardRes) {
        return false
    }

    // 2. Fast path exact matches & common Auto / AutoP variations
    if (lower == "auto" || lower == "autop" || lower == "auto p" || lower == "auto-p" || 
        lower == "auto_p" || lower == "auto.p" || lower == "auto(p)" || lower == "auto (p)" || lower == "auto [p]" ||
        lower == "autop." || lower == "autop-" || lower == "autop_" || lower == "(autop)" || lower == "[autop]" ||
        lower == "automatic" || lower == "تلقائي" || lower == "تلقائى" || lower == "الجودة التلقائية" ||
        lower == "auto quality" || lower == "default" || lower == "adaptive") {
        return true
    }

    // 3. Catch all AutoP variants without standard resolution (e.g. "AutoP", "auto p", "autop", etc.)
    if (lower.contains("autop") || lower.contains("auto p") || lower.contains("auto-p") || lower.contains("auto_p") || lower.contains("auto.p")) {
        return true
    }

    // 4. Check for auto / autop / تلقائي tokens
    val stripped = lower.replace(Regex("""[\[\]\(\)\{\}\"\'\-_:;|/\\]"""), " ").trim()
    val tokens = stripped.split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (tokens.any { it == "auto" || it == "autop" || it == "automatic" || it == "تلقائي" || it == "تلقائى" || it == "default" || it == "adaptive" }) {
        return true
    }

    return lower.startsWith("auto")
}

fun normalizeQualityKey(rawName: String?): String? {
    if (rawName.isNullOrBlank()) return null
    if (isAutoQuality(rawName)) return null
    val trimmed = rawName.trim()
    val lower = trimmed.lowercase()

    // 1. Explicitly reject arbitrary/non-evidence descriptors
    if (lower in listOf("high", "best", "medium", "low", "hd1", "quality-1", "quality-2", "unknown", "custom", "default", "none") ||
        (lower.startsWith("server") && !lower.contains("4k") && !lower.contains("2160") && !lower.contains("1440") && !lower.contains("1080") && !lower.contains("720") && !lower.contains("480") && !lower.contains("360") && !lower.contains("240") && !lower.contains("144")) ||
        lower.contains("quality-") ||
        lower.matches(Regex("""hd\d+""")) ||
        lower.endsWith("-custom")
    ) {
        return null
    }

    // 2. Resolution attribute matching (e.g. RESOLUTION=3840x2160 or 1920x1080)
    val resMatch = Regex("""(?i)(?:resolution\s*=\s*)?(\d{3,4})\s*x\s*(\d{3,4})""").find(trimmed)
    if (resMatch != null) {
        val h = resMatch.groupValues[2].toIntOrNull()
            ?: resMatch.groupValues[1].toIntOrNull()
        if (h != null) {
            return when {
                h >= 2000 -> "4K"
                h in 1300..1999 && h !in 900..1299 -> "1440"
                h in 900..1299 -> "1080"
                h in 600..899 -> "720"
                h in 400..599 -> "480"
                h in 300..399 -> "360"
                h in 200..299 -> "240"
                h in 100..199 -> "144"
                else -> null
            }
        }
    }

    // 3. Exact standard primary resolution tokens (4K, 2160, 1440, 1080, 720, 480, 360, 240, 144) with word boundaries
    val pMatch = Regex("""(?i)\b(4k|2160|1440|1080|720|480|360|240|144)p?\b""").find(trimmed)
    if (pMatch != null) {
        val raw = pMatch.groupValues[1].lowercase()
        return when (raw) {
            "4k", "2160" -> "4K"
            "1440" -> "1440"
            else -> raw
        }
    }

    // 4. Delimiter-bounded resolution matching (e.g., _720p_, -1080p., /480/, _4k_)
    val delimMatch = Regex("""(?i)(?:^|[\s_\-/\.x])(4k|2160|1440|1080|720|480|360|240|144)p?(?:[\s_\-/\.]|$)""").find(trimmed)
    if (delimMatch != null) {
        val raw = delimMatch.groupValues[1].lowercase()
        return when (raw) {
            "4k", "2160" -> "4K"
            "1440" -> "1440"
            else -> raw
        }
    }

    // 5. Arbitrary \d{3,4}p format mapped to standard tiers
    val arbMatch = Regex("""(?i)\b(\d{3,4})p\b""").find(trimmed)
    if (arbMatch != null) {
        val num = arbMatch.groupValues[1].toIntOrNull()
        if (num != null) {
            return when {
                num >= 2000 -> "4K"
                num in 1300..1999 && num !in 900..1299 -> "1440"
                num in 900..1299 -> "1080"
                num in 600..899 -> "720"
                num in 400..599 -> "480"
                num in 300..399 -> "360"
                num in 200..299 -> "240"
                num in 100..199 -> "144"
                else -> null
            }
        }
    }

    // 6. Standard alias tokens with word boundaries (reject blind substring matches on URLs, timestamps, or hashes)
    return when {
        Regex("""(?i)\b(4k|2160|3840|uhd)\b""").containsMatchIn(lower) -> "4K"
        Regex("""(?i)\b(1440|2560|2k|qhd)\b""").containsMatchIn(lower) -> "1440"
        Regex("""(?i)\b(1080|1920|fhd)\b""").containsMatchIn(lower) -> "1080"
        Regex("""(?i)\b(720|1280)\b""").containsMatchIn(lower) -> "720"
        Regex("""(?i)\bhd\b""").containsMatchIn(lower) && !lower.contains("fhd") && !lower.contains("server") && !lower.contains("hd1") && !lower.contains("hd-") -> "720"
        Regex("""(?i)\b(480|854|576|sd)\b""").containsMatchIn(lower) -> "480"
        Regex("""(?i)\b(360|640)\b""").containsMatchIn(lower) -> "360"
        Regex("""(?i)\b(240|426)\b""").containsMatchIn(lower) -> "240"
        Regex("""(?i)\b(144|256)\b""").containsMatchIn(lower) -> "144"
        else -> null
    }
}

fun normalizeQualityLabel(rawName: String?): String? {
    if (rawName == null) return null
    if (isAutoQuality(rawName)) return "Auto"
    val key = normalizeQualityKey(rawName) ?: return null
    return if (key == "4K") "4K" else "${key}p"
}

fun normalizeCanonicalQualityName(rawName: String?): String? {
    if (rawName.isNullOrBlank()) return "Auto"
    if (isAutoQuality(rawName)) return "Auto"
    val key = normalizeQualityKey(rawName) ?: return null
    return when (key) {
        "4K", "2160" -> "4K"
        "1440" -> "1440p"
        "1080" -> "1080p"
        "720" -> "720p"
        "480" -> "480p"
        "360" -> "360p"
        "240" -> "240p"
        "144" -> "144p"
        else -> null
    }
}

fun filterCanonicalQualities(qualities: Collection<com.example.utils.M3U8Parser.QualityInfo>): List<com.example.utils.M3U8Parser.QualityInfo> {
    val canonicalOrder = listOf("4K", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p", "Auto")
    val standardResolutions = setOf("4K", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p")
    val map = LinkedHashMap<String, com.example.utils.M3U8Parser.QualityInfo>()
    var autoEntry: com.example.utils.M3U8Parser.QualityInfo? = null

    for (q in qualities) {
        if (isAutoQuality(q.name)) {
            if (autoEntry == null || (autoEntry.url.isBlank() && q.url.isNotBlank())) {
                autoEntry = q.copy(name = "Auto")
            }
            continue
        }
        val canonicalName = normalizeCanonicalQualityName(q.name) ?: continue
        if (canonicalName in standardResolutions) {
            val existing = map[canonicalName]
            if (existing == null || (existing.url.isBlank() && q.url.isNotBlank())) {
                map[canonicalName] = q.copy(name = canonicalName)
            }
        }
    }
    if (autoEntry != null && !map.containsKey("Auto")) {
        map["Auto"] = autoEntry.copy(name = "Auto")
    }
    return map.values.sortedWith(Comparator { a, b ->
        val idxA = canonicalOrder.indexOf(a.name).let { if (it == -1) 99 else it }
        val idxB = canonicalOrder.indexOf(b.name).let { if (it == -1) 99 else it }
        idxA.compareTo(idxB)
    })
}

object ServerStateStore {
    fun buildMediaKey(
        title: String,
        isMovie: Boolean,
        season: Int = 1,
        episode: Int = 1,
        variantMode: String = "subtitled",
        audioLang: String = "ar"
    ): String = com.example.ui.screens.player.buildMediaKey(title, isMovie, season, episode, variantMode, audioLang)

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeExtractionJobs = ConcurrentHashMap<String, Job>()
    private val activeInspectionDeferreds = ConcurrentHashMap<String, Deferred<MediaServerData?>>()

    var currentMediaKey: String? = null
    var currentMediaId: String? = null
    var extractedServers: List<String> = emptyList()
    var extractedServerLinks: Map<String, String> = emptyMap()
    var extractedServerIds: Map<String, String> = emptyMap()
    var extractedDownloadLinks: Map<String, String> = emptyMap()
    
    // Server Name -> List of Qualities
    var serverQualities: MutableMap<String, List<M3U8Parser.QualityInfo>> = mutableMapOf()
    
    // Extracted and deduplicated qualities across ALL servers (canonical, ordered)
    var extractedQualities: List<M3U8Parser.QualityInfo> = emptyList()

    // Internal fallback candidates per quality level
    var internalCandidates: Map<String, List<QualityCandidate>> = emptyMap()

    private val _extractedQualitiesFlow = MutableStateFlow<List<M3U8Parser.QualityInfo>>(emptyList())
    val extractedQualitiesFlow: StateFlow<List<M3U8Parser.QualityInfo>> = _extractedQualitiesFlow.asStateFlow()

    private val _serversFlow = MutableStateFlow<List<String>>(emptyList())
    val serversFlow: StateFlow<List<String>> = _serversFlow.asStateFlow()

    private val cache = ConcurrentHashMap<String, MediaServerData>()

    init {
        try {
            val ctx = try { com.example.MyApplication.appContext } catch (_: Throwable) { null }
            if (ctx != null) {
                val dir = File(ctx.filesDir, "server_state_cache")
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun logDiag(msg: String) {
        try {
            Log.i("MULTI_QUALITY", msg)
        } catch (_: Throwable) {
            println(msg)
        }
    }

    fun isExtractionJobActive(key: String): Boolean {
        return activeExtractionJobs[key]?.isActive == true
    }

    fun cancelBackgroundExtractionsExcept(activeKey: String) {
        val iterator = activeExtractionJobs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.key.contains(activeKey)) {
                entry.value.cancel()
                iterator.remove()
            }
        }
    }

    fun saveForMedia(
        mediaKey: String,
        servers: List<String>,
        links: Map<String, String>,
        ids: Map<String, String>,
        downloads: Map<String, String>,
        qualities: Map<String, List<M3U8Parser.QualityInfo>> = emptyMap(),
        extractedQ: List<M3U8Parser.QualityInfo> = emptyList(),
        internalCand: Map<String, List<QualityCandidate>> = emptyMap(),
        website: String = "",
        playbackPageUrl: String? = null,
        scraperKey: String? = null,
        altKeys: List<String> = emptyList(),
        directStreamUrl: String? = null,
        mediaId: String = "",
        variantMode: String = "subtitled",
        audioLanguage: String = "ar"
    ) {
        val targetMediaId = if (mediaId.isNotBlank()) mediaId else altKeys.firstOrNull { it.matches(Regex("""\d+""")) } ?: ""
        val isForCurrent = currentMediaKey == null || currentMediaKey == mediaKey || (targetMediaId.isNotBlank() && currentMediaId == targetMediaId)
        
        if (isForCurrent) {
            currentMediaKey = mediaKey
            if (targetMediaId.isNotBlank()) currentMediaId = targetMediaId
            extractedServers = servers
            extractedServerLinks = links
            extractedServerIds = ids
            extractedDownloadLinks = downloads
            serverQualities = qualities.toMutableMap()
            internalCandidates = internalCand
            if (servers.isNotEmpty()) {
                _serversFlow.value = servers
            }
            if (extractedQ.isNotEmpty()) {
                extractedQualities = extractedQ
                _extractedQualitiesFlow.value = extractedQ
            }
        }

        val existing = cache[mediaKey]
        val resolvedMode = if (mediaKey.contains("-dubbed-", ignoreCase = true) || altKeys.any { it.contains("-dubbed-", ignoreCase = true) }) "dubbed" else variantMode
        val data = MediaServerData(
            mediaKey = mediaKey,
            mediaId = if (targetMediaId.isNotBlank()) targetMediaId else existing?.mediaId ?: "",
            servers = servers,
            serverLinks = links,
            serverIds = ids,
            downloadLinks = downloads,
            serverQualities = qualities,
            extractedQualities = if (extractedQ.isNotEmpty()) extractedQ else (existing?.extractedQualities ?: emptyList()),
            internalCandidates = if (internalCand.isNotEmpty()) internalCand else (existing?.internalCandidates ?: emptyMap()),
            website = website.ifBlank { existing?.website ?: "" },
            playbackPageUrl = playbackPageUrl ?: existing?.playbackPageUrl,
            scraperKey = scraperKey ?: existing?.scraperKey,
            directStreamUrl = directStreamUrl ?: existing?.directStreamUrl,
            variantMode = resolvedMode,
            audioLanguage = audioLanguage
        )

        cache[mediaKey] = data
        for (alt in altKeys) {
            if (alt.isNotBlank()) {
                cache[alt] = data
            }
        }
        val isSeriesEpisode = mediaKey.contains("-false-")
        if (targetMediaId.isNotBlank()) {
            cache["$targetMediaId-$resolvedMode"] = data
            if (!isSeriesEpisode && resolvedMode != "dubbed") {
                cache[targetMediaId] = data
            }
        }
    }

    /**
     * Atomically merges revalidated servers and qualities into state and cache
     * WITHOUT disrupting or restarting the current active playback.
     */
    fun updateRevalidatedData(
        mediaKey: String,
        newServers: List<String>,
        newLinks: Map<String, String>,
        newIds: Map<String, String>,
        newDownloads: Map<String, String>,
        newQualities: List<M3U8Parser.QualityInfo>,
        newCandidates: Map<String, List<QualityCandidate>> = emptyMap(),
        sourcePageUrl: String? = null,
        scraperKey: String? = null,
        altKeys: List<String> = emptyList(),
        directStreamUrl: String? = null,
        mediaId: String = ""
    ) {
        val targetMediaId = if (mediaId.isNotBlank()) mediaId else altKeys.firstOrNull { it.matches(Regex("""\d+""")) } ?: ""
        val isForCurrent = currentMediaKey == null || currentMediaKey == mediaKey || (targetMediaId.isNotBlank() && currentMediaId == targetMediaId)

        if (isForCurrent) {
            if (newServers.isNotEmpty()) {
                extractedServers = newServers
                extractedServerLinks = newLinks
                extractedServerIds = newIds
                _serversFlow.value = newServers
            }
            if (newDownloads.isNotEmpty()) {
                extractedDownloadLinks = newDownloads
            }
            if (newQualities.isNotEmpty()) {
                extractedQualities = newQualities
                _extractedQualitiesFlow.value = newQualities
            }
            if (newCandidates.isNotEmpty()) {
                internalCandidates = newCandidates
            }
        }

        val existing = cache[mediaKey]
        val resolvedMode = if (mediaKey.contains("-dubbed-", ignoreCase = true) || altKeys.any { it.contains("-dubbed-", ignoreCase = true) } || existing?.variantMode == "dubbed") "dubbed" else (existing?.variantMode ?: "subtitled")
        val updated = (existing ?: MediaServerData()).copy(
            mediaKey = mediaKey,
            mediaId = if (targetMediaId.isNotBlank()) targetMediaId else existing?.mediaId ?: "",
            servers = if (newServers.isNotEmpty()) newServers else existing?.servers ?: emptyList(),
            serverLinks = if (newLinks.isNotEmpty()) newLinks else existing?.serverLinks ?: emptyMap(),
            serverIds = if (newIds.isNotEmpty()) newIds else existing?.serverIds ?: emptyMap(),
            downloadLinks = if (newDownloads.isNotEmpty()) newDownloads else existing?.downloadLinks ?: emptyMap(),
            extractedQualities = if (newQualities.isNotEmpty()) newQualities else existing?.extractedQualities ?: emptyList(),
            internalCandidates = if (newCandidates.isNotEmpty()) newCandidates else existing?.internalCandidates ?: emptyMap(),
            website = existing?.website ?: scraperKey ?: "",
            playbackPageUrl = sourcePageUrl ?: existing?.playbackPageUrl,
            scraperKey = scraperKey ?: existing?.scraperKey,
            directStreamUrl = directStreamUrl ?: existing?.directStreamUrl,
            variantMode = resolvedMode,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        cache[mediaKey] = updated
        for (alt in altKeys) {
            if (alt.isNotBlank()) {
                cache[alt] = updated
            }
        }
        val isSeriesEpisode = mediaKey.contains("-false-")
        if (targetMediaId.isNotBlank()) {
            cache["$targetMediaId-$resolvedMode"] = updated
            if (!isSeriesEpisode && resolvedMode != "dubbed") {
                cache[targetMediaId] = updated
            }
        }
    }

    fun startBackgroundQualityExtraction(
        mediaKey: String,
        serversNames: List<String>,
        serversMap: Map<String, String>,
        downloadsMap: Map<String, String> = emptyMap(),
        currentStreamUrl: String? = null,
        altKeys: List<String> = emptyList(),
        scraperKey: String? = null,
        sourcePageUrl: String? = null
    ) {
        val jobKey = "${scraperKey ?: "scraper"}:$mediaKey:${currentStreamUrl ?: "all"}"
        val existing = activeExtractionJobs[jobKey]
        if (existing != null && existing.isActive) {
            logDiag("[MULTI_QUALITY] job_deduplicated key=$jobKey")
            return
        }

        val job = storeScope.launch {
            try {
                resolveAndCacheAllQualities(
                    mediaKey = mediaKey,
                    serversNames = serversNames,
                    serversMap = serversMap,
                    downloadsMap = downloadsMap,
                    currentStreamUrl = currentStreamUrl,
                    altKeys = altKeys,
                    sourcePageUrl = sourcePageUrl
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                activeExtractionJobs.remove(jobKey)
            }
        }
        activeExtractionJobs[jobKey] = job
    }

    fun sortQualities(list: Collection<M3U8Parser.QualityInfo>): List<M3U8Parser.QualityInfo> {
        val order = listOf("4320", "2160", "1440", "1080", "720", "576", "480", "360", "240", "144")
        val withoutAuto = list.filter { it.name != "Auto" }.sortedWith(Comparator { a, b ->
            val keyA = normalizeQualityKey(a.name) ?: ""
            val keyB = normalizeQualityKey(b.name) ?: ""
            val idxA = order.indexOf(keyA).let { if (it == -1) 999 else it }
            val idxB = order.indexOf(keyB).let { if (it == -1) 999 else it }
            idxA.compareTo(idxB)
        })
        val autoItem = list.firstOrNull { it.name == "Auto" }
        return if (autoItem != null) withoutAuto + autoItem else withoutAuto
    }

    suspend fun resolveAndCacheAllQualities(
        mediaKey: String,
        serversNames: List<String>,
        serversMap: Map<String, String>,
        downloadsMap: Map<String, String> = emptyMap(),
        currentStreamUrl: String? = null,
        altKeys: List<String> = emptyList(),
        sourcePageUrl: String? = null
    ): List<M3U8Parser.QualityInfo> = withContext(Dispatchers.IO) {
        val allCandidates = mutableListOf<QualityCandidate>()

        logDiag("[MULTI_QUALITY] servers_received=${serversNames.size}")

        // 1. Direct download links
        for ((dlName, dlUrl) in downloadsMap) {
            if (dlUrl.isNotBlank()) {
                val key = normalizeQualityKey(dlName) ?: normalizeQualityKey(dlUrl)
                if (key != null) {
                    allCandidates.add(
                        QualityCandidate(
                            qualityKey = key,
                            label = "${key}p",
                            streamUrl = dlUrl,
                            sourceUrl = dlUrl,
                            evidence = QualityEvidence.EXPLICIT_LABEL
                        )
                    )
                }
            }
        }

        // 2. Current active stream if provided
        if (!currentStreamUrl.isNullOrBlank()) {
            try {
                logDiag("[MULTI_QUALITY] server_started=current_active")
                val isHls = currentStreamUrl.contains(".m3u8") || currentStreamUrl.contains("akamaized.net")
                logDiag("[MULTI_QUALITY] server_type=${if (isHls) "DIRECT" else "DIRECT_FILE"}")
                logDiag("[MULTI_QUALITY] media_source=$currentStreamUrl")
                if (isHls) {
                    logDiag("[MULTI_QUALITY] master_playlist_fetch=START")
                    val headers = mapOf("Referer" to (sourcePageUrl ?: currentStreamUrl), "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    var streamQualities = emptyList<M3U8Parser.QualityInfo>()
                    try {
                        streamQualities = M3U8Parser.getQualities(currentStreamUrl, headers)
                    } catch (_: Exception) {}
                    logDiag("[MULTI_QUALITY] master_playlist_fetch=200")
                    logDiag("[MULTI_QUALITY] variants_found=${streamQualities.size}")
                    for (q in streamQualities) {
                        val key = normalizeQualityKey(q.name)
                        if (key != null) {
                            logDiag("[MULTI_QUALITY] quality_detected=${key}p")
                            allCandidates.add(
                                QualityCandidate(
                                    qualityKey = key,
                                    label = "${key}p",
                                    width = q.width,
                                    height = q.height,
                                    streamUrl = q.url,
                                    headers = q.headers,
                                    sourceUrl = currentStreamUrl,
                                    evidence = QualityEvidence.RESOLUTION
                                )
                            )
                        } else {
                            logDiag("[MULTI_QUALITY] quality_rejected=no_evidence_${q.name}")
                        }
                    }
                    if (streamQualities.isEmpty()) {
                        // Single direct stream or media playlist without sub-variants
                        val key = normalizeQualityKey(currentStreamUrl)
                        if (key != null) {
                            allCandidates.add(
                                QualityCandidate(
                                    qualityKey = key,
                                    label = "${key}p",
                                    streamUrl = currentStreamUrl,
                                    headers = headers,
                                    sourceUrl = currentStreamUrl,
                                    evidence = QualityEvidence.OTHER_VERIFIED
                                )
                            )
                        }
                    }
                } else if (currentStreamUrl.endsWith(".mp4") || currentStreamUrl.endsWith(".mkv")) {
                    val key = normalizeQualityKey(currentStreamUrl)
                    if (key != null) {
                        allCandidates.add(
                            QualityCandidate(
                                qualityKey = key,
                                label = "${key}p",
                                streamUrl = currentStreamUrl,
                                sourceUrl = currentStreamUrl,
                                evidence = QualityEvidence.OTHER_VERIFIED
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                logDiag("[MULTI_QUALITY] server_failed=${e.message}")
            }
        }

        // 3. Scan each remaining server in serversNames / serversMap
        for (server in serversNames) {
            val link = serversMap[server] ?: ""
            if (link.isNotBlank() && link != currentStreamUrl) {
                logDiag("[MULTI_QUALITY] server_started=$server")
                val isDirect = link.contains(".m3u8") || link.contains(".mp4") || link.contains(".mkv") || link.contains("akamaized.net")
                val serverType = if (isDirect) "DIRECT" else "EMBED"
                logDiag("[MULTI_QUALITY] server_type=$serverType")

                try {
                    if (isDirect) {
                        logDiag("[MULTI_QUALITY] media_source=$link")
                        if (link.contains(".m3u8") || link.contains("akamaized.net")) {
                            logDiag("[MULTI_QUALITY] master_playlist_fetch=START")
                            val headers = mapOf("Referer" to link, "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            val streamQualities = M3U8Parser.getQualities(link, headers)
                            logDiag("[MULTI_QUALITY] master_playlist_fetch=200")
                            logDiag("[MULTI_QUALITY] master_playlist_status=200")
                            logDiag("[MULTI_QUALITY] variants_found=${streamQualities.size}")
                            for (q in streamQualities) {
                                val key = normalizeQualityKey(q.name)
                                if (key != null) {
                                    logDiag("[MULTI_QUALITY] quality_detected=${key}p")
                                    allCandidates.add(
                                        QualityCandidate(
                                            qualityKey = key,
                                            label = "${key}p",
                                            width = q.width,
                                            height = q.height,
                                            streamUrl = q.url,
                                            headers = headers,
                                            serverName = server,
                                            sourceUrl = link,
                                            evidence = QualityEvidence.RESOLUTION
                                        )
                                    )
                                } else {
                                    logDiag("[MULTI_QUALITY] quality_rejected=no_evidence_${q.name}")
                                }
                            }
                        } else {
                            val key = normalizeQualityKey(server) ?: normalizeQualityKey(link)
                            if (key != null) {
                                allCandidates.add(
                                    QualityCandidate(
                                        qualityKey = key,
                                        label = "${key}p",
                                        streamUrl = link,
                                        serverName = server,
                                        sourceUrl = link,
                                        evidence = QualityEvidence.OTHER_VERIFIED
                                    )
                                )
                            }
                        }
                    } else {
                        // Embed Page: Extract actual media source using StaticMediaExtractor
                        var extractedMedia: String? = null
                        try {
                            extractedMedia = StaticMediaExtractor.extract(link, referer = sourcePageUrl)
                        } catch (e: Exception) {
                            logDiag("[MULTI_QUALITY] server_failed=${e.message}")
                        }

                        if (!extractedMedia.isNullOrBlank()) {
                            logDiag("[MULTI_QUALITY] media_source=$extractedMedia")
                            val isHls = extractedMedia.contains(".m3u8") || extractedMedia.contains("akamaized.net")
                            val headers = mapOf("Referer" to link, "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            if (isHls) {
                                logDiag("[MULTI_QUALITY] master_playlist_fetch=START")
                                val streamQualities = M3U8Parser.getQualities(extractedMedia, headers)
                                logDiag("[MULTI_QUALITY] master_playlist_fetch=200")
                                logDiag("[MULTI_QUALITY] master_playlist_status=200")
                                logDiag("[MULTI_QUALITY] variants_found=${streamQualities.size}")
                                for (q in streamQualities) {
                                    val key = normalizeQualityKey(q.name)
                                    if (key != null) {
                                        logDiag("[MULTI_QUALITY] quality_detected=${key}p")
                                        allCandidates.add(
                                            QualityCandidate(
                                                qualityKey = key,
                                                label = "${key}p",
                                                width = q.width,
                                                height = q.height,
                                                streamUrl = q.url,
                                                headers = headers,
                                                serverName = server,
                                                sourceUrl = link,
                                                evidence = QualityEvidence.RESOLUTION
                                            )
                                        )
                                    } else {
                                        logDiag("[MULTI_QUALITY] quality_rejected=no_evidence_${q.name}")
                                    }
                                }
                            } else {
                                val key = normalizeQualityKey(server) ?: normalizeQualityKey(extractedMedia)
                                if (key != null) {
                                    allCandidates.add(
                                        QualityCandidate(
                                            qualityKey = key,
                                            label = "${key}p",
                                            streamUrl = extractedMedia,
                                            headers = headers,
                                            serverName = server,
                                            sourceUrl = link,
                                            evidence = QualityEvidence.OTHER_VERIFIED
                                        )
                                    )
                                }
                            }
                        } else {
                            logDiag("[MULTI_QUALITY] server_failed=media_not_found_on_$server")
                        }
                    }
                } catch (e: Exception) {
                    // Server failure isolation
                    logDiag("[MULTI_QUALITY] server_failed=${e.message}")
                }
            }
        }

        // Centrally aggregate all candidates using QualityAggregator
        val aggregated = QualityAggregator.aggregate(allCandidates)
        val groupedMap = allCandidates.groupBy { it.qualityKey }

        val finalQualityList = if (aggregated.isNotEmpty()) {
            QualityAggregator.toQualityInfoList(
                aggregated = aggregated,
                defaultStreamUrl = currentStreamUrl ?: allCandidates.firstOrNull()?.streamUrl
            )
        } else {
            listOf(M3U8Parser.QualityInfo("Auto", currentStreamUrl.orEmpty()))
        }

        logDiag("[MULTI_QUALITY] aggregation_complete=${finalQualityList.size}")

        // Atomic non-disruptive state update: strictly verify this still belongs to currently active media
        val targetMediaId = altKeys.firstOrNull { it.matches(Regex("""\d+""")) } ?: ""
        val isForCurrent = currentMediaKey == mediaKey || (targetMediaId.isNotBlank() && currentMediaId == targetMediaId)
        if (isForCurrent) {
            extractedQualities = finalQualityList
            internalCandidates = groupedMap
            _extractedQualitiesFlow.value = finalQualityList
        }

        val existing = cache[mediaKey]
        val updated = (existing ?: MediaServerData()).copy(
            mediaKey = mediaKey,
            mediaId = if (targetMediaId.isNotBlank()) targetMediaId else existing?.mediaId ?: "",
            servers = if (serversNames.isNotEmpty()) serversNames else existing?.servers ?: emptyList(),
            serverLinks = if (serversMap.isNotEmpty()) serversMap else existing?.serverLinks ?: emptyMap(),
            downloadLinks = if (downloadsMap.isNotEmpty()) downloadsMap else existing?.downloadLinks ?: emptyMap(),
            extractedQualities = finalQualityList,
            internalCandidates = groupedMap,
            directStreamUrl = currentStreamUrl ?: existing?.directStreamUrl
        )
        cache[mediaKey] = updated
        for (alt in altKeys) {
            if (alt.isNotBlank()) {
                cache[alt] = updated
            }
        }
        val isSeriesEpisode = mediaKey.contains("-false-")
        if (targetMediaId.isNotBlank() && !isSeriesEpisode) {
            cache[targetMediaId] = updated
        }

        finalQualityList
    }

    fun hasExtractedServers(vararg keys: String?): Boolean {
        for (k in keys) {
            if (k != null) {
                val cached = cache[k]
                if (cached != null && (cached.servers.isNotEmpty() || cached.extractedQualities.isNotEmpty())) {
                    return true
                }
            }
        }
        return false
    }

    fun hasRealQualities(qualities: List<M3U8Parser.QualityInfo>): Boolean {
        return qualities.any { it.name.isNotBlank() && it.name != "Auto" && it.url.isNotBlank() }
    }

    fun hasRealQualities(vararg keys: String?): Boolean {
        val cached = getCachedData(*keys) ?: return false
        return hasRealQualities(cached.extractedQualities)
    }

    fun loadForMedia(mediaKey: String): Boolean {
        val data = cache[mediaKey]
        if (data != null && (data.servers.isNotEmpty() || data.extractedQualities.isNotEmpty())) {
            currentMediaKey = mediaKey
            currentMediaId = data.mediaId.ifBlank { null }
            extractedServers = data.servers
            extractedServerLinks = data.serverLinks
            extractedServerIds = data.serverIds
            extractedDownloadLinks = data.downloadLinks
            serverQualities = data.serverQualities.toMutableMap()
            extractedQualities = data.extractedQualities
            internalCandidates = data.internalCandidates
            _serversFlow.value = data.servers
            _extractedQualitiesFlow.value = data.extractedQualities
            return true
        }
        return false
    }

    fun mergeDiscoveredQualities(mediaKey: String?, mediaId: String?, newQualities: List<M3U8Parser.QualityInfo>) {
        if (newQualities.isEmpty()) return
        val filtered = filterCanonicalQualities(newQualities)
        if (filtered.isEmpty()) return

        val isCurrent = (mediaKey != null && currentMediaKey == mediaKey) || 
                        (mediaId != null && mediaId.isNotBlank() && currentMediaId == mediaId)
        if (isCurrent) {
            val merged = (extractedQualities + filtered).distinctBy { it.name }
            val canonicalList = filterCanonicalQualities(merged)
            extractedQualities = canonicalList
            _extractedQualitiesFlow.value = canonicalList
        }

        if (!mediaKey.isNullOrBlank()) {
            val existing = cache[mediaKey]
            if (existing != null) {
                val merged = (existing.extractedQualities + filtered).distinctBy { it.name }
                val updated = existing.copy(extractedQualities = filterCanonicalQualities(merged))
                cache[mediaKey] = updated
            }
        }
        if (!mediaId.isNullOrBlank()) {
            val existing = cache[mediaId]
            if (existing != null) {
                val merged = (existing.extractedQualities + filtered).distinctBy { it.name }
                val updated = existing.copy(extractedQualities = filterCanonicalQualities(merged))
                cache[mediaId] = updated
            }
        }
    }

    /**
     * Prepares store for a specific media item.
     * Cancels any pending background jobs from previous media.
     * If the media is in cache, active state is populated from cache.
     * If NOT in cache, active variables and flows are strictly cleared to prevent
     * data from the previous movie or series episode leaking into the current media.
     */
    fun prepareForMedia(mediaKey: String, vararg altKeys: String?) {
        val cleanAlt = altKeys.filterNotNull().filter { it.isNotBlank() }
        val targetMediaId = cleanAlt.firstOrNull { it.matches(Regex("""\d+""")) }

        // Cancel background extractions belonging to prior media
        cancelBackgroundExtractionsExcept(mediaKey)

        val cached = getCachedData(mediaKey, *cleanAlt.toTypedArray())
        if (cached != null) {
            currentMediaKey = mediaKey
            currentMediaId = targetMediaId ?: cached.mediaId.ifBlank { null }
            extractedServers = cached.servers
            extractedServerLinks = cached.serverLinks
            extractedServerIds = cached.serverIds
            extractedDownloadLinks = cached.downloadLinks
            serverQualities = cached.serverQualities.toMutableMap()
            extractedQualities = cached.extractedQualities
            internalCandidates = cached.internalCandidates
            _serversFlow.value = cached.servers
            _extractedQualitiesFlow.value = cached.extractedQualities
            return
        }

        // Not in cache: strictly reset active variables and flows
        currentMediaKey = mediaKey
        currentMediaId = targetMediaId
        extractedServers = emptyList()
        extractedServerLinks = emptyMap()
        extractedServerIds = emptyMap()
        extractedDownloadLinks = emptyMap()
        serverQualities.clear()
        extractedQualities = emptyList()
        internalCandidates = emptyMap()
        _serversFlow.value = emptyList()
        _extractedQualitiesFlow.value = emptyList()
    }

    fun invalidateStreamUrl(key: String) {
        val existing = cache[key]
        if (existing != null) {
            val updated = existing.copy(
                directStreamUrl = null,
                extractedQualities = emptyList()
            )
            cache[key] = updated
        }
        if (currentMediaKey == key || currentMediaId == key) {
            extractedQualities = emptyList()
            _extractedQualitiesFlow.value = emptyList()
        }
    }

    fun getCachedData(vararg keys: String?): MediaServerData? {
        val cleanKeys = keys.filterNotNull().filter { it.isNotBlank() }
        if (cleanKeys.isEmpty()) return null

        val requestedNumericId = cleanKeys.firstOrNull { it.matches(Regex("""\d+""")) }
        val requestedHyphenKey = cleanKeys.firstOrNull { it.contains("-") }
        val maxAgeMs = 2 * 60 * 60 * 1000L

        for (k in cleanKeys) {
            val rawData = cache[k]
            if (rawData != null && (rawData.servers.isNotEmpty() || rawData.extractedQualities.isNotEmpty() || !rawData.directStreamUrl.isNullOrBlank())) {
                val isStreamExpired = rawData.lastUpdatedTimestamp > 0L && (System.currentTimeMillis() - rawData.lastUpdatedTimestamp > maxAgeMs)
                val data = if (isStreamExpired && (!rawData.directStreamUrl.isNullOrBlank() || rawData.extractedQualities.isNotEmpty())) {
                    rawData.copy(directStreamUrl = null, extractedQualities = emptyList())
                } else rawData
                // Strict variant isolation (Phase 09B-B Section 16 & Phase 19.7):
                val isRequestedDubbed = cleanKeys.any { it.contains("-dubbed-", ignoreCase = true) || it.endsWith("-dubbed", ignoreCase = true) || it.contains("_dubbed", ignoreCase = true) }
                val isDataDubbed = data.mediaKey.contains("-dubbed-", ignoreCase = true) || data.mediaKey.endsWith("-dubbed", ignoreCase = true) || data.mediaKey.contains("_dubbed", ignoreCase = true) || data.variantMode.equals("dubbed", ignoreCase = true)
                if (isRequestedDubbed != isDataDubbed) {
                    continue // Strictly prevent collision between dubbed and subtitled cache
                }

                // Strict isolation: if numeric TMDB ID was requested and data has a mediaId, ensure it matches
                if (requestedNumericId != null && data.mediaId.isNotBlank()) {
                    if (data.mediaId == requestedNumericId) {
                        return data
                    } else {
                        continue
                    }
                }
                if (requestedHyphenKey != null && data.mediaKey.isNotBlank()) {
                    if (data.mediaKey == requestedHyphenKey) {
                        return data
                    }
                }
                return data
            }
        }
        return null
    }

    fun getDataForMedia(mediaKey: String): MediaServerData? = cache[mediaKey]

    /**
     * Unified inspection method: discovers servers, extracts embed streams,
     * resolves real quality variants, and caches everything in ServerStateStore
     * so that all components on the details page (download dialog, player, etc.)
     * share the exact same inspection results without duplicate scans.
     */
    suspend fun inspectAndCacheMedia(
        mediaKey: String,
        title: String,
        year: String = "",
        isMovie: Boolean = true,
        season: Int = 1,
        episode: Int = 1,
        mediaId: String = "",
        altKeys: List<String> = emptyList(),
        context: android.content.Context,
        contentType: com.example.extension.managed.model.ContentType? = null,
        originalTitle: String? = null,
        playbackAttempt: com.example.extension.managed.playback.PlaybackAttempt? = null
    ): MediaServerData? = withContext(Dispatchers.IO) {
        val allAltKeys = (altKeys + listOf(mediaId)).filter { it.isNotBlank() }.distinct()

        // 1. Return immediately if cached data exists (never block UI or player)
        val existingCached = getCachedData(mediaKey, *allAltKeys.toTypedArray())
        val now = System.currentTimeMillis()
        val SIX_HOURS_MS = 6 * 3600 * 1000L
        if (existingCached != null && (existingCached.extractedQualities.isNotEmpty() || !existingCached.directStreamUrl.isNullOrBlank())) {
            val lastUpdated = existingCached.lastUpdatedTimestamp
            if (lastUpdated == 0L || (now - lastUpdated >= SIX_HOURS_MS)) {
                try {
                    val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
                    managedOrchestrator.revalidateMediaInBackground(
                        mediaId = mediaId,
                        episodeId = if (!isMovie) "${season}_$episode" else null,
                        mediaTitle = title,
                        isMovie = isMovie,
                        season = season,
                        episode = episode,
                        knownPlaybackUrl = existingCached.playbackPageUrl ?: "",
                        scraperKey = existingCached.scraperKey ?: "",
                        altKeys = allAltKeys
                    )
                } catch (_: Throwable) {}
            }
            return@withContext existingCached
        }

        // Deduplicate in-flight inspection for this exact mediaKey
        val inFlight = activeInspectionDeferreds[mediaKey]
        if (inFlight != null && inFlight.isActive) {
            return@withContext inFlight.await()
        }

        val deferred = storeScope.async {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                doInspectAndCacheMedia(
                    mediaKey = mediaKey,
                    title = title,
                    year = year,
                    isMovie = isMovie,
                    season = season,
                    episode = episode,
                    mediaId = mediaId,
                    allAltKeys = allAltKeys,
                    context = context,
                    contentType = contentType,
                    originalTitle = originalTitle,
                    playbackAttempt = playbackAttempt
                )
            } ?: existingCached
        }
        activeInspectionDeferreds[mediaKey] = deferred
        try {
            deferred.await()
        } finally {
            activeInspectionDeferreds.remove(mediaKey)
        }
    }

    private suspend fun doInspectAndCacheMedia(
        mediaKey: String,
        title: String,
        year: String,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        mediaId: String,
        allAltKeys: List<String>,
        context: android.content.Context,
        contentType: com.example.extension.managed.model.ContentType? = null,
        originalTitle: String? = null,
        playbackAttempt: com.example.extension.managed.playback.PlaybackAttempt? = null
    ): MediaServerData? {
        val existingCached = getCachedData(mediaKey, *allAltKeys.toTypedArray())
        val serversNames: List<String>
        val serversMap: Map<String, String>
        val serversIds: Map<String, String>
        val downloadsMap: Map<String, String>
        val sourceUrl: String?
        val website: String
        var directStreamUrl: String?
        var serverItems: List<com.example.extension.managed.model.ServerItem> = emptyList()

        if (existingCached != null && existingCached.servers.isNotEmpty()) {
            serversNames = existingCached.servers
            serversMap = existingCached.serverLinks
            serversIds = existingCached.serverIds
            downloadsMap = existingCached.downloadLinks
            sourceUrl = existingCached.playbackPageUrl
            website = existingCached.website
            directStreamUrl = existingCached.directStreamUrl

            // FIX 4: Deterministically reconstruct serverItems from cached server definitions
            serverItems = existingCached.servers.mapNotNull { name ->
                val link = existingCached.serverLinks[name] ?: return@mapNotNull null
                val id = existingCached.serverIds[name] ?: name
                val isDirect = link.endsWith(".mp4") || link.endsWith(".m3u8") || link.contains("akamaized.net")
                com.example.extension.managed.model.ServerItem(
                    id = id,
                    name = name,
                    link = link,
                    isDirectStream = isDirect,
                    sourceUrl = existingCached.playbackPageUrl ?: link,
                    serverType = if (isDirect) com.example.extension.managed.model.ServerType.DIRECT else com.example.extension.managed.model.ServerType.EMBED,
                    requiresWebView = !isDirect
                )
            }
            com.example.extension.managed.trace.Phase05GLogger.log(
                "CACHE",
                mediaId,
                "Cache hit: ${serversNames.size} servers cached, reconstructed ${serverItems.size} serverItems, cachedDirectStream=${!directStreamUrl.isNullOrBlank()}"
            )
        } else {
            val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
            val targetType = contentType ?: when {
                isMovie -> com.example.extension.managed.model.ContentType.MOVIE
                title.contains("anime", ignoreCase = true) || title.contains("أنمي", ignoreCase = true) -> com.example.extension.managed.model.ContentType.ANIME
                else -> com.example.extension.managed.model.ContentType.SERIES
            }
            com.example.extension.managed.trace.Phase05GLogger.log(
                "DISCOVERY",
                mediaId,
                "TargetContentType=$targetType, hasActiveExtensions=${managedOrchestrator.hasActiveExtensions(targetType)}"
            )
            if (!managedOrchestrator.hasActiveExtensions(targetType)) {
                com.example.extension.managed.trace.Phase05GLogger.log(
                    "DISCOVERY",
                    mediaId,
                    "Awaiting runtime readiness for targetType=$targetType, initialActive=false"
                )
                managedOrchestrator.awaitRuntimeReady(timeoutMillis = 2500L, contentType = targetType)
            }

            if (!managedOrchestrator.hasActiveExtensions(targetType)) {
                com.example.extension.managed.trace.Phase05GLogger.log(
                    "DISCOVERY",
                    mediaId,
                    "No active extensions for targetType=$targetType, ensuring bundled fallback"
                )
                val reg = com.example.extension.managed.registry.ManagedExtensionRegistry.INSTANCE
                if (reg.getAllExtensions().isEmpty()) {
                    reg.setExtensions(com.example.extension.orchestrator.ManagedMediaOrchestrator.BUNDLED_DEFAULT_EXTENSIONS)
                }
            }

            if (!managedOrchestrator.hasActiveExtensions(targetType)) {
                com.example.extension.managed.trace.Phase05GLogger.log(
                    "DISCOVERY",
                    mediaId,
                    "Early return: no active extensions for targetType=$targetType after runtime check"
                )
                return null
            }

            val isDubbedMedia = mediaKey.contains("-dubbed-", ignoreCase = true) || mediaKey.endsWith("-dubbed", ignoreCase = true)
            val effectiveVariantMode = if (isDubbedMedia) "dubbed" else "subtitled"

            val outcome = managedOrchestrator.discoverServers(
                title = title,
                year = year,
                isMovie = isMovie,
                season = season,
                episode = episode,
                mediaId = mediaId,
                altKeys = allAltKeys,
                contentType = targetType,
                originalTitle = originalTitle,
                playbackAttempt = playbackAttempt,
                variantMode = effectiveVariantMode,
                audioLanguage = "ar"
            )

            if (outcome !is com.example.extension.orchestrator.ManagedDiscoveryOutcome.Success) {
                com.example.extension.managed.trace.Phase05GLogger.log(
                    "DISCOVERY",
                    mediaId,
                    "discoverServers outcome: ${outcome.javaClass.simpleName}"
                )
                return null
            }

            serverItems = outcome.servers
            val isPureDownloadOnly = { s: com.example.extension.managed.model.ServerItem ->
                s.name.contains("(تحميل)") && !s.link.endsWith(".mp4") && !s.link.endsWith(".m3u8")
            }
            val streamServers = outcome.servers.filter { !isPureDownloadOnly(it) }
            serversNames = if (streamServers.isNotEmpty()) streamServers.map { it.name } else outcome.servers.map { it.name }
            serversMap = outcome.servers.associate { it.name to it.link }
            serversIds = outcome.servers.associate { it.name to it.id }
            downloadsMap = outcome.servers.filter {
                it.name.contains("(تحميل)") || it.link.endsWith(".mp4") || it.link.endsWith(".mkv")
            }.associate { it.name to it.link }
            sourceUrl = outcome.sourceUrl
            website = outcome.website
            directStreamUrl = outcome.directStream?.streamUrl?.takeIf { com.example.ui.components.isValidPlayableMediaUrl(it) }

            com.example.extension.managed.trace.Phase05GLogger.log(
                "SERVERS",
                mediaId,
                "Discovered ${serverItems.size} servers (${serversNames.joinToString(", ")}), website=$website"
            )
        }

        // If direct stream URL is not yet resolved, check direct links in serversMap / downloadsMap (ensuring valid media URL)
        if (directStreamUrl.isNullOrBlank()) {
            val directLink = serversMap.values.firstOrNull {
                it.contains(".m3u8") || it.contains(".mp4") || it.contains("akamaized.net")
            } ?: downloadsMap.values.firstOrNull {
                it.contains(".m3u8") || it.contains(".mp4") || it.contains("akamaized.net")
            }
            if (!directLink.isNullOrBlank() && com.example.ui.components.isValidPlayableMediaUrl(directLink)) {
                directStreamUrl = directLink
                com.example.extension.managed.trace.Phase05GLogger.log("VARIANT", mediaId, "Direct playable stream link found in server map: $directStreamUrl")
            }
        }

        // If still blank, extract playback source from candidate servers using managed runtime
        if (directStreamUrl.isNullOrBlank() && serverItems.isNotEmpty()) {
            val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
            val isPureDownloadOnly = { s: com.example.extension.managed.model.ServerItem ->
                s.name.contains("(تحميل)") && !s.link.endsWith(".mp4") && !s.link.endsWith(".m3u8")
            }
            val candidateServers = serverItems.filter { !isPureDownloadOnly(it) }.take(3)
            for (srv in candidateServers) {
                try {
                    com.example.extension.managed.trace.Phase05GLogger.log(
                        "EXTRACTION",
                        mediaId,
                        "Starting extraction for candidate server: ${srv.name} (${srv.link})"
                    )
                    val extractResult = managedOrchestrator.extractPlaybackSource(srv, title)
                    if (extractResult.isSuccess) {
                        val stream = extractResult.getOrThrow().streamUrl
                        if (stream.isNotBlank() && com.example.ui.components.isValidPlayableMediaUrl(stream)) {
                            directStreamUrl = stream
                            com.example.extension.managed.trace.Phase05GLogger.log(
                                "EXTRACTION",
                                mediaId,
                                "Extraction success on server ${srv.name}: $stream"
                            )
                            break
                        }
                    } else {
                        com.example.extension.managed.trace.Phase05GLogger.log(
                            "EXTRACTION",
                            mediaId,
                            "Extraction failed on server ${srv.name}: ${extractResult.exceptionOrNull()?.message}"
                        )
                    }
                } catch (e: Throwable) {
                    com.example.extension.managed.trace.Phase05GLogger.log(
                        "EXTRACTION",
                        mediaId,
                        "Extraction exception on server ${srv.name}: ${e.message}"
                    )
                }
            }
        }

        // Resolve and cache all qualities using resolved direct stream and candidate servers
        val resolvedQualities = resolveAndCacheAllQualities(
            mediaKey = mediaKey,
            serversNames = serversNames,
            serversMap = serversMap,
            downloadsMap = downloadsMap,
            currentStreamUrl = directStreamUrl,
            altKeys = allAltKeys,
            sourcePageUrl = sourceUrl
        )

        saveForMedia(
            mediaKey = mediaKey,
            servers = serversNames,
            links = serversMap,
            ids = serversIds,
            downloads = downloadsMap,
            extractedQ = resolvedQualities,
            website = website,
            playbackPageUrl = sourceUrl,
            scraperKey = website,
            altKeys = allAltKeys,
            directStreamUrl = directStreamUrl,
            mediaId = mediaId
        )

        com.example.extension.managed.trace.Phase05GLogger.log(
            "VARIANT",
            mediaId,
            "Resolved directStreamUrl=${directStreamUrl ?: "none"}, qualitiesCount=${resolvedQualities.size}"
        )

        return getCachedData(mediaKey, *allAltKeys.toTypedArray())
    }

    fun clear() {
        currentMediaKey = null
        currentMediaId = null
        activeExtractionJobs.values.forEach { it.cancel() }
        activeExtractionJobs.clear()
        activeInspectionDeferreds.values.forEach { it.cancel() }
        activeInspectionDeferreds.clear()
        extractedServers = emptyList()
        extractedServerLinks = emptyMap()
        extractedServerIds = emptyMap()
        extractedDownloadLinks = emptyMap()
        extractedQualities = emptyList()
        internalCandidates = emptyMap()
        _extractedQualitiesFlow.value = emptyList()
        _serversFlow.value = emptyList()
        serverQualities.clear()
        cache.clear()
        try {
            val ctx = try { com.example.MyApplication.appContext } catch (_: Throwable) { null }
            if (ctx != null) {
                val dir = java.io.File(ctx.filesDir, "server_state_cache")
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        } catch (_: Throwable) {}
    }
}
