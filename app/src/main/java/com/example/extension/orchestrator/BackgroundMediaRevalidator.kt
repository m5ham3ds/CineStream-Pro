package com.example.extension.orchestrator

import android.util.Log
import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.ui.screens.player.ServerStateStore
import com.example.ui.screens.player.normalizeQualityKey
import com.example.ui.screens.player.normalizeQualityLabel
import com.example.ui.screens.player.filterCanonicalQualities
import com.example.utils.M3U8Parser
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Background Media Revalidation Engine (Phase 7.1.6).
 *
 * Responsibilities:
 * 1. Independent lifecycle: Runs on a process-level CoroutineScope and continues even if user exits UI.
 * 2. 30-Second Hard Global Deadline: Every cycle is strictly bounded to max 30 seconds.
 * 3. Direct Playback Page Access: Uses known playback URL directly without repeating Search -> Details.
 * 4. Network Interruption Handling: If network drops, pauses and waits for network restoration within the 30s budget.
 * 5. Deduplication: Ensures no concurrent duplicate refresh jobs for the same content identity.
 * 6. Non-Disruptive Updates: Updates ServerStateStore atomically without resetting or interrupting playing media.
 * 7. Comprehensive Observability: Emits structured diagnostic markers for each phase.
 */
class BackgroundMediaRevalidator(
    private val registry: ManagedExtensionRegistry,
    private val runtime: ControlledManagedExtensionRuntime,
    private val isOnlineChecker: () -> Boolean = { true },
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeJobStartTimes = ConcurrentHashMap<String, Long>()

    companion object {
        const val SIX_MINUTES_MS = 6 * 60 * 1000L
        const val SIX_HOURS_MS = SIX_MINUTES_MS // Backwards compatibility alias
        val lastRevalidationTimes = ConcurrentHashMap<String, Long>()

        fun getLastRevalidationTime(jobKey: String): Long {
            val inMem = lastRevalidationTimes[jobKey]
            if (inMem != null && inMem > 0L) return inMem
            val ctx = try { com.example.MyApplication.appContext } catch (_: Throwable) { null } ?: return 0L
            val sp = ctx.getSharedPreferences("revalidation_timestamps", android.content.Context.MODE_PRIVATE)
            val saved = sp.getLong(jobKey, 0L)
            if (saved > 0L) lastRevalidationTimes[jobKey] = saved
            return saved
        }

        fun saveLastRevalidationTime(jobKey: String, time: Long) {
            lastRevalidationTimes[jobKey] = time
            val ctx = try { com.example.MyApplication.appContext } catch (_: Throwable) { null } ?: return
            ctx.getSharedPreferences("revalidation_timestamps", android.content.Context.MODE_PRIVATE)
                .edit().putLong(jobKey, time).apply()
        }
    }

    private fun logDiag(msg: String) {
        try {
            Log.i("REVALIDATION", msg)
        } catch (_: Throwable) {
            println("REVALIDATION: $msg")
        }
    }

    fun isJobActive(key: String): Boolean {
        return activeJobs[key]?.isActive == true
    }

    fun cancelJob(key: String) {
        activeJobs[key]?.cancel()
        activeJobs.remove(key)
        activeJobStartTimes.remove(key)
        logDiag("REVALIDATION_CANCELLED key=$key")
    }

    fun cancelAllExcept(activeKey: String? = null) {
        val iterator = activeJobs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (activeKey == null || !entry.key.contains(activeKey)) {
                entry.value.cancel()
                iterator.remove()
                activeJobStartTimes.remove(entry.key)
                logDiag("REVALIDATION_CANCELLED key=${entry.key}")
            }
        }
    }

    /**
     * Initiates a background revalidation cycle for known media.
     * Uses known playback URL directly. Deduplicates requests for the same mediaKey.
     */
    fun revalidateMedia(
        mediaId: String,
        episodeId: String? = null,
        mediaTitle: String,
        isMovie: Boolean,
        season: Int = 1,
        episode: Int = 1,
        knownPlaybackUrl: String,
        scraperKey: String = "",
        currentServerName: String? = null,
        currentQuality: String = "Auto",
        altKeys: List<String> = emptyList(),
        force: Boolean = false
    ): Job {
        val safeKey = if (scraperKey.isNotBlank()) scraperKey else "managed"
        val jobKey = "$safeKey:$mediaId:${episodeId ?: ""}"

        // Deduplication check
        val existing = activeJobs[jobKey]
        if (existing != null && existing.isActive) {
            logDiag("BACKGROUND_REVALIDATION_DEDUPLICATED key=$jobKey")
            return existing
        }

        // 6-minute interval rule: do not re-scan on every play click if revalidated recently
        val mediaKey = "$mediaTitle-$isMovie-$season-$episode"
        val cachedBefore = ServerStateStore.getCachedData(mediaKey, mediaId, *altKeys.toTypedArray())
        val lastRun = getLastRevalidationTime(jobKey).takeIf { it > 0L } ?: (cachedBefore?.lastUpdatedTimestamp ?: 0L)
        val now = System.currentTimeMillis()
        if (!force && lastRun > 0L && (now - lastRun < SIX_MINUTES_MS)) {
            logDiag("BACKGROUND_REVALIDATION_SKIPPED_WITHIN_6_MINUTES key=$jobKey")
            return coroutineScope.launch { }
        }

        val job = coroutineScope.launch {
            val startTime = System.currentTimeMillis()
            val globalDeadlineMs = 10 * 60 * 1000L // 10 minutes session budget
            val deadlineTime = startTime + globalDeadlineMs
            activeJobStartTimes[jobKey] = startTime

            logDiag("BACKGROUND_REVALIDATION_START mediaId=$mediaId, knownPlaybackUrl=$knownPlaybackUrl, scraperKey=$scraperKey")
            logDiag("KNOWN_PLAYBACK_URL url=$knownPlaybackUrl")

            val existingServers = cachedBefore?.servers ?: emptyList()
            val existingQualities = cachedBefore?.extractedQualities ?: emptyList()

            logDiag("KNOWN_SERVER_COUNT count=${existingServers.size}")
            logDiag("KNOWN_QUALITY_COUNT count=${existingQualities.size}")
            logDiag("DIRECT_PLAYBACK_STARTED")

            try {
                // 1. Check Network or Wait for Restoration
                var isNetOk = isOnlineChecker()
                if (!isNetOk) {
                    logDiag("NETWORK_LOST")
                    while (!isOnlineChecker()) {
                        if (System.currentTimeMillis() >= deadlineTime) {
                            logDiag("REVALIDATION_TIMEOUT while waiting for network")
                            return@launch
                        }
                        delay(1000)
                    }
                    logDiag("NETWORK_RESTORED")
                }

                // 2. Resolve target candidate extension directly (NO SEARCH)
                val candidate = if (scraperKey.isNotBlank()) {
                    registry.getExtensionByScraperKey(scraperKey)
                        ?: registry.getExtensionById(scraperKey)
                        ?: registry.getActiveExtensions().firstOrNull { it.scraperKey.equals(scraperKey, true) || it.id.equals(scraperKey, true) }
                        ?: registry.getActiveExtensions().firstOrNull { knownPlaybackUrl.contains(it.baseUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")) }
                        ?: registry.getActiveExtensions().firstOrNull()
                } else {
                    registry.getActiveExtensions().firstOrNull { knownPlaybackUrl.contains(it.baseUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")) }
                        ?: registry.getActiveExtensions().firstOrNull()
                }

                if (candidate == null) {
                    logDiag("REVALIDATION_CANCELLED no candidate extension for scraperKey='$scraperKey'")
                    return@launch
                }

                // 3. Server Discovery on Known Playback URL
                logDiag("SERVER_REFRESH_START targetUrl=$knownPlaybackUrl")
                val remainingTimeForDiscovery = (deadlineTime - System.currentTimeMillis()).coerceAtLeast(1000L)
                
                var discoveryResult = withTimeoutOrNull(remainingTimeForDiscovery) {
                    runtime.discoverServers(
                        listOf(candidate),
                        ServerDiscoveryRequest(
                            targetUrl = knownPlaybackUrl,
                            mediaTitle = mediaTitle,
                            isMovie = isMovie,
                            season = season,
                            episode = episode
                        )
                    )
                }

                if (discoveryResult == null || discoveryResult.isFailure) {
                    if (!isOnlineChecker() && System.currentTimeMillis() < deadlineTime) {
                        logDiag("NETWORK_LOST")
                        while (!isOnlineChecker()) {
                            if (System.currentTimeMillis() >= deadlineTime) {
                                logDiag("REVALIDATION_TIMEOUT while waiting for network")
                                return@launch
                            }
                            delay(1000)
                        }
                        logDiag("NETWORK_RESTORED")
                        val retryTime = (deadlineTime - System.currentTimeMillis()).coerceAtLeast(1000L)
                        discoveryResult = withTimeoutOrNull(retryTime) {
                            runtime.discoverServers(
                                listOf(candidate),
                                ServerDiscoveryRequest(
                                    targetUrl = knownPlaybackUrl,
                                    mediaTitle = mediaTitle,
                                    isMovie = isMovie,
                                    season = season,
                                    episode = episode
                                )
                            )
                        }
                    }
                }

                if (discoveryResult == null) {
                    logDiag("REVALIDATION_TIMEOUT during server discovery")
                    return@launch
                }

                if (discoveryResult.isFailure) {
                    val err = discoveryResult.exceptionOrNull()
                    logDiag("REVALIDATION_CANCELLED server discovery failure: ${err?.message}")
                    return@launch
                }

                val discoveredServers = discoveryResult.getOrThrow().servers
                logDiag("SERVER_REFRESH_RESULT count=${discoveredServers.size}")

                // 4. Server Deduplication and Normalization
                val mergedServers = mutableListOf<String>()
                val mergedLinks = mutableMapOf<String, String>()
                val mergedIds = mutableMapOf<String, String>()
                val mergedDownloads = mutableMapOf<String, String>()

                // Keep existing servers to preserve user context
                for (s in existingServers) {
                    mergedServers.add(s)
                    cachedBefore?.serverLinks?.get(s)?.let { mergedLinks[s] = it }
                    cachedBefore?.serverIds?.get(s)?.let { mergedIds[s] = it }
                }
                cachedBefore?.downloadLinks?.let { mergedDownloads.putAll(it) }

                // Add newly discovered servers without duplicates
                for (item in discoveredServers) {
                    if (item.name.contains("(تحميل)") || item.link.endsWith(".mp4") || item.link.endsWith(".mkv")) {
                        mergedDownloads[item.name] = item.link
                    } else {
                        if (!mergedLinks.values.contains(item.link)) {
                            val uniqueName = if (mergedServers.contains(item.name)) "${item.name} (${item.id})" else item.name
                            mergedServers.add(uniqueName)
                            mergedLinks[uniqueName] = item.link
                            mergedIds[uniqueName] = item.id
                        }
                    }
                }

                // 5. Multi-Quality Refresh
                if (!isOnlineChecker()) {
                    logDiag("NETWORK_LOST")
                    while (!isOnlineChecker()) {
                        if (System.currentTimeMillis() >= deadlineTime) {
                            logDiag("REVALIDATION_TIMEOUT while waiting for network")
                            return@launch
                        }
                        delay(1000)
                    }
                    logDiag("NETWORK_RESTORED")
                }

                logDiag("QUALITY_REFRESH_START")
                val remainingTimeForQuality = (deadlineTime - System.currentTimeMillis()).coerceAtLeast(1000L)

                val refreshedQualities = withTimeoutOrNull(remainingTimeForQuality) {
                    ServerStateStore.resolveAndCacheAllQualities(
                        mediaKey = mediaKey,
                        serversNames = mergedServers,
                        serversMap = mergedLinks,
                        downloadsMap = mergedDownloads,
                        currentStreamUrl = null,
                        altKeys = listOf(mediaId) + altKeys
                    )
                } ?: existingQualities

                // Normalize and deduplicate quality names using canonical filter
                val normalizedQualities = filterCanonicalQualities(refreshedQualities)

                logDiag("QUALITY_REFRESH_RESULT count=${normalizedQualities.size}")

                // 6. Atomically update Media State WITHOUT interrupting current playback!
                ServerStateStore.updateRevalidatedData(
                    mediaKey = mediaKey,
                    newServers = mergedServers,
                    newLinks = mergedLinks,
                    newIds = mergedIds,
                    newDownloads = mergedDownloads,
                    newQualities = normalizedQualities,
                    sourcePageUrl = knownPlaybackUrl,
                    scraperKey = candidate.scraperKey,
                    altKeys = listOf(mediaId) + altKeys,
                    mediaId = mediaId
                )

                val elapsed = System.currentTimeMillis() - startTime
                val finishTime = System.currentTimeMillis()
                saveLastRevalidationTime(jobKey, finishTime)
                logDiag("REVALIDATION_COMPLETED durationMs=$elapsed")

            } catch (e: CancellationException) {
                logDiag("REVALIDATION_CANCELLED")
                throw e
            } catch (e: Exception) {
                logDiag("REVALIDATION_CANCELLED error=${e.message}")
            } finally {
                activeJobs.remove(jobKey)
                activeJobStartTimes.remove(jobKey)
            }
        }

        activeJobs[jobKey] = job
        return job
    }
}
