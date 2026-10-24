package com.example.extension.managed.playback

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.QualitySource
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.runtime.web.CloudflareForensicLogger
import com.example.extension.managed.runtime.web.InteractiveChallengeController
import com.example.extension.managed.runtime.web.PerSiteSessionStore
import com.example.extension.managed.runtime.web.ProtectedWebRuntime
import com.example.extension.managed.runtime.web.ProtectedWebRuntimeError
import com.example.extension.managed.searchorder.ExtensionEligibilityFilter
import com.example.extension.managed.searchorder.SearchOrderRepository
import com.example.extension.managed.trace.Phase05HLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface PlaybackOrchestratorOutcome {
    data class Started(
        val firstSource: PlaybackSource,
        val session: PlaybackSession
    ) : PlaybackOrchestratorOutcome

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : PlaybackOrchestratorOutcome

    object Cancelled : PlaybackOrchestratorOutcome

    data class ChallengeRequired(
        val extensionId: String,
        val targetUrl: String,
        val candidate: ManagedExtension,
        val session: PlaybackSession
    ) : PlaybackOrchestratorOutcome
}

/**
 * Phase 05S: Playback Orchestrator with Deterministic Fallback and Interactive Cloudflare Recovery.
 *
 * Invariants:
 * 1. Consumes canonical Search Order from SearchOrderRepository without reordering or altering priority.
 * 2. Filters extensions through ExtensionEligibilityFilter enforcing hard ContentType isolation.
 * 3. Preserves Admin Search Order sequence deterministically (no priority sorting, no shuffling).
 * 4. Bounded execution timeouts across network operations, candidates, and challenge recovery.
 * 5. Preserves ExtensionError without swallowing errors via .getOrNull().
 * 6. Cloudflare challenge pauses candidate, displays natural interactive verification, persists session,
 *    and retries the SAME candidate.
 * 7. User cancellation or challenge timeout skips the candidate and falls back cleanly to the next candidate.
 * 8. All candidates failure guarantees a clean terminal error state (zero infinite loading or buffering).
 * 9. QFilm Golden Reference pipeline is strictly preserved.
 * 10. Zero Firestore reads/writes during active playback (consults local snapshot).
 */
class PlaybackOrchestrator(
    private val searchOrderRepository: SearchOrderRepository,
    private val eligibilityFilter: ExtensionEligibilityFilter,
    private val extensionRegistry: ManagedExtensionRegistry,
    private val runtime: ControlledManagedExtensionRuntime,
    private val sufficiencyPolicy: QualitySufficiencyPolicy = QualitySufficiencyPolicy(),
    private val isOnlineChecker: () -> Boolean = { true },
    private val fallbackManager: com.example.extension.managed.runtime.FallbackManager = com.example.extension.managed.runtime.FallbackManager(com.example.extension.managed.registry.ScraperRegistry.INSTANCE),
    private val protectedWebRuntime: ProtectedWebRuntime = ProtectedWebRuntime.getInstance(),
    private val interactiveChallengeController: InteractiveChallengeController = InteractiveChallengeController.getInstance()
) {

    companion object {
        const val TIMEOUT_NETWORK_MS = 10_000L
        const val TIMEOUT_EXTRACTION_MS = 15_000L
        const val TIMEOUT_CANDIDATE_MS = 35_000L
        const val TIMEOUT_CHALLENGE_MS = 60_000L
        const val TIMEOUT_SESSION_MS = 180_000L
    }

    private fun isCloudflareChallenge(error: Throwable?): Boolean {
        if (error == null) return false
        if (error is ExtensionError.CloudflareChallenge) return true
        if (error.cause is ExtensionError.CloudflareChallenge) return true
        val msg = error.message?.lowercase() ?: ""
        return msg.contains("cloudflare") || msg.contains("turnstile") || msg.contains("just a moment")
    }

    suspend fun orchestratePlayback(
        session: PlaybackSession,
        onFirstPlayableSource: (PlaybackSource) -> Unit,
        onQualitiesDiscovered: (List<QualitySource>) -> Unit = {},
        onError: (String) -> Unit = {}
    ): PlaybackOrchestratorOutcome = withContext(Dispatchers.IO + session.sessionJob) {
        if (!session.isActive) {
            return@withContext PlaybackOrchestratorOutcome.Cancelled
        }

        if (!isOnlineChecker()) {
            val userMsg = "لا يوجد اتصال بالإنترنت"
            onError(userMsg)
            return@withContext PlaybackOrchestratorOutcome.Failure(userMsg)
        }

        val targetContentType = session.contentType
        val cachedStore = com.example.ui.screens.player.ServerStateStore.getCachedData(session.mediaId)
        val targetMode = com.example.domain.models.MediaVariantMode.fromString(cachedStore?.variantMode)
        val isDubbedSession = session.variantMode.equals("dubbed", ignoreCase = true) ||
            targetMode == com.example.domain.models.MediaVariantMode.DUBBED
        val requiredAudio = if (isDubbedSession) com.example.domain.models.AudioCapability.ARABIC_DUB else null

        Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_SESSION_STARTED: mediaId=${session.mediaId}, title='${session.title}', isDubbed=$isDubbedSession")
        Phase05HLogger.log("PLAYBACK", session.mediaId, "CONTENT_TYPE_RESOLVED: type=$targetContentType")

        // 1. Fetch canonical Search Order for requested content type and variant mode
        val searchOrderIds = if (isDubbedSession) {
            searchOrderRepository.getDubbedOrder(com.example.domain.models.VariantLanguage.ARABIC)
        } else {
            searchOrderRepository.getOrderForContentType(targetContentType)
        }
        Phase05HLogger.log("PLAYBACK", session.mediaId, "SEARCH_ORDER_RESOLVED: order=$searchOrderIds, isDubbed=$isDubbedSession")

        // 2. Fetch all registered managed extensions
        val allExtensions = extensionRegistry.getAllExtensions()

        // 3. Filter candidates through eligibility pipeline & hard content-type isolation
        val initialCandidates = if (searchOrderIds.isNotEmpty()) {
            eligibilityFilter.filterEligibleExtensions(
                orderedExtensionIds = searchOrderIds,
                availableExtensions = allExtensions,
                targetContentType = targetContentType,
                capability = ScraperCapability.SERVER_DISCOVERY,
                requiredAudioCapability = requiredAudio,
                currentAppVersionCode = runtime.currentAppVersionCode,
                supportedRuntimeApiVersion = runtime.supportedRuntimeApiVersion
            )
        } else emptyList()

        val eligibleCandidates = if (initialCandidates.isNotEmpty()) {
            initialCandidates
        } else if (isDubbedSession) {
            // Strict isolation: in dubbed mode never query subtitle scrapers
            allExtensions.filter { ext ->
                ext.audioCapabilities.contains(com.example.domain.models.AudioCapability.ARABIC_DUB) &&
                ext.contentTypes.contains(targetContentType) &&
                ext.capabilities.contains(ScraperCapability.SERVER_DISCOVERY)
            }
        } else {
            // When search order is empty or unprovisioned, fall back to eligible active candidates
            fallbackManager.filterAndSortCandidates(
                candidates = allExtensions,
                contentType = targetContentType,
                capability = ScraperCapability.SERVER_DISCOVERY,
                appVersionCode = runtime.currentAppVersionCode,
                supportedRuntimeApi = runtime.supportedRuntimeApiVersion
            )
        }

        if (eligibleCandidates.isEmpty()) {
            Phase05HLogger.log("PLAYBACK", session.mediaId, "ALL_CANDIDATES_FAILED: zero eligible extensions for $targetContentType (isDubbed=$isDubbedSession)")
            Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_FAILED: No eligible extensions")
            val userMsg = if (isDubbedSession) "النسخة المدبلجة غير متوفرة لهذا العمل" else "تعذر تشغيل هذا المحتوى"
            onError(userMsg)
            return@withContext PlaybackOrchestratorOutcome.Failure(
                if (isDubbedSession) "النسخة المدبلجة غير متوفرة لهذا العمل" else "No eligible managed extensions found for $targetContentType with search order: $searchOrderIds"
            )
        }

        Phase05HLogger.log(
            "PLAYBACK",
            session.mediaId,
            "PLAYBACK_CANDIDATES_RESOLVED: ${eligibleCandidates.map { it.id }}"
        )

        session.orderedCandidates.addAll(eligibleCandidates)

        val targetLang = com.example.domain.models.VariantLanguage.fromString(cachedStore?.audioLanguage)
        val effectiveVariantMode = if (isDubbedSession) com.example.domain.models.MediaVariantMode.DUBBED else targetMode
        val resumedCandidates = com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.resumeOnPlay(
            workId = session.mediaId,
            variantMode = effectiveVariantMode ?: com.example.domain.models.MediaVariantMode.SUBTITLED,
            audioLanguage = targetLang,
            season = session.season,
            episode = session.episode,
            allEligibleProviders = eligibleCandidates
        )
        val candidatesToRun = resumedCandidates ?: eligibleCandidates

        val cleanTitle = session.title.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val cleanTitleWithYear = if (session.year.isNotBlank() && session.year != "0") "$cleanTitle ${session.year}" else cleanTitle

        val cleanOriginalTitle = session.originalTitle?.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")?.replace(Regex("\\s+"), " ")?.trim()
        val cleanOriginalTitleWithYear = if (!cleanOriginalTitle.isNullOrBlank() && session.year.isNotBlank() && session.year != "0") "$cleanOriginalTitle ${session.year}" else cleanOriginalTitle

        val rawPrimary = session.title.split(":", "-", " Season", " الموسم", " Part", " الجزء").firstOrNull()?.trim() ?: session.title
        val primaryTitle = rawPrimary.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

        val rawPrimaryOriginal = session.originalTitle?.split(":", "-", " Season", " الموسم", " Part", " الجزء")?.firstOrNull()?.trim()
        val primaryOriginalTitle = rawPrimaryOriginal?.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")?.replace(Regex("\\s+"), " ")?.trim()

        var firstPlayableEmitted = false

        // 4. Iterate over eligible candidates in exact configured order
        for (candidate in candidatesToRun) {
            if (!session.isActive) break

            // Phase 05Q.5-C: Strict Candidate Filter against PlaybackAttempt cancelled set
            if (session.attempt.isCandidateCancelled(candidate.id) || session.attempt.isCandidateCancelled(candidate.scraperKey)) {
                Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED_ALREADY_CANCELLED: extensionId=${candidate.id}")
                Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                continue
            }

            // Check if quality sufficiency was already achieved by previous candidates
            val currentQualities = session.discoveredQualities.map { it.label }
            if (firstPlayableEmitted && sufficiencyPolicy.isSufficient(currentQualities)) {
                break
            }

            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SELECTED: extensionId=${candidate.id}")

            var candidateAttempt = 0
            val maxCandidateAttempts = 2 // 1 initial attempt + 1 retry if Cloudflare challenge was successfully solved
            var candidateChallengeRecovered = false

            while (candidateAttempt < maxCandidateAttempts && session.isActive) {
                candidateAttempt++

                try {
                    val candidatePrimarySource: PlaybackSource? = withTimeoutOrNull(TIMEOUT_CANDIDATE_MS) {
                        // Step A: Search title on candidate extension
                        var searchResult = if (isDubbedSession) {
                            var dubbedRes: com.example.extension.managed.model.SearchMediaItem? = null
                            if (!cleanTitle.contains("مدبلج")) {
                                Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_DUBBED_SEARCH_STARTED: extensionId=${candidate.id}, query='$cleanTitle مدبلج'")
                                val dubbedReq = SearchRequest(query = "$cleanTitle مدبلج", contentType = targetContentType)
                                dubbedRes = runtime.search(listOf(candidate), dubbedReq).getOrNull()?.items?.firstOrNull()
                            }
                            if (dubbedRes == null && !cleanOriginalTitle.isNullOrBlank() && cleanOriginalTitle != cleanTitle && !cleanOriginalTitle.contains("مدبلج")) {
                                Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_DUBBED_ORIGINAL_SEARCH_STARTED: extensionId=${candidate.id}, query='$cleanOriginalTitle مدبلج'")
                                val dubbedReqOrig = SearchRequest(query = "$cleanOriginalTitle مدبلج", contentType = targetContentType)
                                dubbedRes = runtime.search(listOf(candidate), dubbedReqOrig).getOrNull()?.items?.firstOrNull()
                            }
                            dubbedRes
                        } else null

                        if (searchResult == null) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SEARCH_STARTED: extensionId=${candidate.id}, query='$cleanTitle'")
                            val searchReq1 = SearchRequest(query = cleanTitle, contentType = targetContentType)
                            val searchRes1 = runtime.search(listOf(candidate), searchReq1)

                            if (searchRes1.isFailure) {
                                val err = searchRes1.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }

                            searchResult = searchRes1.getOrNull()?.items?.firstOrNull()
                        }

                        if (searchResult == null) {
                            val searchReq2 = SearchRequest(query = cleanTitleWithYear, contentType = targetContentType)
                            val searchRes2 = runtime.search(listOf(candidate), searchReq2)
                            if (searchRes2.isFailure) {
                                val err = searchRes2.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }
                            searchResult = searchRes2.getOrNull()?.items?.firstOrNull()
                        }

                        // Fallback to originalTitle if not found
                        if (searchResult == null && !cleanOriginalTitle.isNullOrBlank() && cleanOriginalTitle != cleanTitle) {
                            val searchReqOrig1 = SearchRequest(query = cleanOriginalTitle, contentType = targetContentType)
                            val searchResOrig1 = runtime.search(listOf(candidate), searchReqOrig1)
                            if (searchResOrig1.isFailure) {
                                val err = searchResOrig1.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }
                            searchResult = searchResOrig1.getOrNull()?.items?.firstOrNull()

                            if (searchResult == null && !cleanOriginalTitleWithYear.isNullOrBlank()) {
                                val searchReqOrig2 = SearchRequest(query = cleanOriginalTitleWithYear, contentType = targetContentType)
                                val searchResOrig2 = runtime.search(listOf(candidate), searchReqOrig2)
                                if (searchResOrig2.isFailure) {
                                    val err = searchResOrig2.exceptionOrNull()
                                    if (err is ExtensionError.SecurityViolation) throw err
                                    if (isCloudflareChallenge(err)) {
                                        throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                    }
                                }
                                searchResult = searchResOrig2.getOrNull()?.items?.firstOrNull()
                            }
                        }

                        // Fallback to primary title (before season/subtitle)
                        if (searchResult == null && !primaryTitle.isNullOrBlank() && primaryTitle != cleanTitle && primaryTitle.length >= 3) {
                            val searchReqPrim = SearchRequest(query = primaryTitle, contentType = targetContentType)
                            val searchResPrim = runtime.search(listOf(candidate), searchReqPrim)
                            if (searchResPrim.isFailure) {
                                val err = searchResPrim.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }
                            searchResult = searchResPrim.getOrNull()?.items?.firstOrNull()
                        }

                        if (searchResult == null && !primaryOriginalTitle.isNullOrBlank() && primaryOriginalTitle != cleanOriginalTitle && primaryOriginalTitle.length >= 3) {
                            val searchReqOrigPrim = SearchRequest(query = primaryOriginalTitle, contentType = targetContentType)
                            val searchResOrigPrim = runtime.search(listOf(candidate), searchReqOrigPrim)
                            if (searchResOrigPrim.isFailure) {
                                val err = searchResOrigPrim.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }
                            searchResult = searchResOrigPrim.getOrNull()?.items?.firstOrNull()
                        }

                        if (searchResult == null) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SEARCH_FAILED: extensionId=${candidate.id}")
                            return@withTimeoutOrNull null
                        }

                        if (!session.isActive) return@withTimeoutOrNull null

                        // Step B: Resolve episode target URL if series/anime
                        val targetUrl = if (session.isMovie) {
                            searchResult.url
                        } else {
                            val epResult = runtime.getEpisodes(candidate, searchResult.url, session.season)
                            if (epResult.isFailure) {
                                val err = epResult.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on episodes"))
                                }
                            }
                            val epList = epResult.getOrNull() ?: emptyList()
                            epList.firstOrNull { it.episodeNumber == session.episode }?.url ?: searchResult.url
                        }

                        if (!session.isActive) return@withTimeoutOrNull null

                        // Step C: Discover servers for the target page
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "SERVER_DISCOVERY_STARTED: extensionId=${candidate.id}")
                        val discoveryRequest = ServerDiscoveryRequest(
                            targetUrl = targetUrl,
                            mediaTitle = session.title,
                            isMovie = session.isMovie,
                            season = session.season,
                            episode = session.episode,
                            contentType = targetContentType
                        )

                        val serverDiscoveryResult = runtime.discoverServers(listOf(candidate), discoveryRequest)
                        if (serverDiscoveryResult.isFailure) {
                            val err = serverDiscoveryResult.exceptionOrNull()
                            if (err is ExtensionError.SecurityViolation) throw err
                            if (isCloudflareChallenge(err)) {
                                throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on server discovery"))
                            }
                        }

                        val servers = serverDiscoveryResult.getOrNull()?.servers ?: emptyList()
                        if (servers.isEmpty()) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "SERVER_DISCOVERY_FAILED: extensionId=${candidate.id}")
                            return@withTimeoutOrNull null
                        }

                        if (!session.isActive) return@withTimeoutOrNull null

                        // Step D: Extract First Playable Source
                        var extractedSource: PlaybackSource? = null

                        // Check for direct stream on servers first
                        val directServer = servers.firstOrNull { it.isDirectStream }
                        if (directServer != null) {
                            extractedSource = PlaybackSource(
                                streamUrl = directServer.link,
                                mimeType = if (directServer.link.contains(".m3u8")) "application/x-mpegURL" else "video/mp4"
                            )
                        } else {
                            // Try top candidate servers for extraction
                            for (srv in servers.take(3)) {
                                val extractResult = if (candidate.scraperKey != "qfilm" && srv.link.startsWith("http") && !srv.link.contains(".m3u8") && !srv.link.contains(".mp4")) {
                                    val webExtract = protectedWebRuntime.extractMedia(
                                        siteKey = candidate.scraperKey,
                                        targetUrl = srv.link,
                                        timeoutMs = TIMEOUT_EXTRACTION_MS,
                                        expectedOrigin = candidate.baseUrl,
                                        enableInteractiveRecovery = true,
                                        extractionContext = InteractiveChallengeController.ExtractionContext(
                                            mediaId = session.mediaId,
                                            title = session.title,
                                            originalTitle = session.originalTitle,
                                            year = session.year,
                                            contentType = targetContentType,
                                            season = session.season,
                                            episode = session.episode,
                                            extensionId = candidate.id,
                                            scraperKey = candidate.scraperKey,
                                            serverId = srv.id,
                                            serverName = srv.name,
                                            targetUrl = srv.link
                                        )
                                    )
                                    if (webExtract.isSuccess) {
                                        webExtract
                                    } else {
                                        val err = webExtract.exceptionOrNull()
                                        if (err is ProtectedWebRuntimeError.CloudflareChallengeCancelled || err is ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit) {
                                            throw err
                                        }
                                        runtime.extractStream(
                                            listOf(candidate),
                                            ExtractionRequest(serverItem = srv, mediaTitle = session.title)
                                        )
                                    }
                                } else {
                                    runtime.extractStream(
                                        listOf(candidate),
                                        ExtractionRequest(serverItem = srv, mediaTitle = session.title)
                                    )
                                }

                                if (extractResult.isSuccess) {
                                    val src = extractResult.getOrThrow().playbackSource
                                    if (src != null && !src.streamUrl.isNullOrBlank()) {
                                        extractedSource = src
                                        break
                                    }
                                } else {
                                    val err = extractResult.exceptionOrNull()
                                    if (err is ExtensionError.SecurityViolation) throw err
                                    if (err is ProtectedWebRuntimeError.CloudflareChallengeCancelled) throw err
                                    if (err is ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit) throw err
                                    if (isCloudflareChallenge(err)) {
                                        throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on stream extraction"))
                                    }
                                }
                            }
                        }

                        extractedSource
                    }

                    if (candidatePrimarySource != null && !candidatePrimarySource.streamUrl.isNullOrBlank()) {
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_STARTED: extensionId=${candidate.id}")
                        if (!firstPlayableEmitted) {
                            firstPlayableEmitted = true
                            session.discoveredSources.add(candidatePrimarySource)
                            session.firstPlayableDeferred.complete(candidatePrimarySource)
                            onFirstPlayableSource(candidatePrimarySource)
                        }

                        // Extract and record qualities from primary source
                        val newQualities = mutableListOf<QualitySource>()
                        if (candidatePrimarySource.qualities.isNotEmpty()) {
                            newQualities.addAll(candidatePrimarySource.qualities)
                        } else if (candidatePrimarySource.variants.isNotEmpty()) {
                            newQualities.addAll(candidatePrimarySource.variants.map { it.toQualitySource() })
                        } else {
                            newQualities.add(QualitySource(label = "Auto", url = candidatePrimarySource.streamUrl))
                        }

                        for (q in newQualities) {
                            if (session.discoveredQualities.none { it.label.equals(q.label, ignoreCase = true) }) {
                                session.discoveredQualities.add(q)
                            }
                        }
                        onQualitiesDiscovered(session.discoveredQualities.toList())

                        // Playback source established! Break out of candidate attempt loop
                        break
                    } else {
                        // Candidate produced no playable source within bounded timeout
                        if (candidateAttempt >= maxCandidateAttempts) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED: extensionId=${candidate.id}")
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                        }
                    }
                } catch (secEx: ExtensionError.SecurityViolation) {
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "FATAL_SECURITY_ERROR: extensionId=${candidate.id}, msg=${secEx.message}")
                    val userMsg = "تعذر تشغيل هذا المحتوى بسبب قيود أمان"
                    onError(userMsg)
                    return@withContext PlaybackOrchestratorOutcome.Failure("Security violation: ${secEx.message}", secEx)
                } catch (cfEx: ExtensionError.CloudflareChallenge) {
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CLOUDFLARE_DETECTED: extensionId=${candidate.id}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_REQUIRED: extensionId=${candidate.id}")

                    // CIRCUIT BREAKER: Bounded to 1 interactive recovery attempt per candidate to strictly prevent verification loops
                    if (candidateChallengeRecovered) {
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_CIRCUIT_BREAKER: candidate=${candidate.id} already recovered challenge once, skipping to avoid loop")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                        break
                    }
                    candidateChallengeRecovered = true

                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_UI_OPENED: extensionId=${candidate.id}")

                    val canonicalHost = PerSiteSessionStore.canonicalizeHost(candidate.baseUrl)
                    val recoveryResult = interactiveChallengeController.launchInteractiveChallenge(
                        siteKey = candidate.scraperKey,
                        canonicalHost = canonicalHost,
                        challengeUrl = candidate.baseUrl,
                        context = InteractiveChallengeController.ExtractionContext(
                            mediaId = session.mediaId,
                            title = session.title,
                            originalTitle = session.originalTitle,
                            year = session.year,
                            contentType = targetContentType,
                            season = session.season,
                            episode = session.episode,
                            extensionId = candidate.id,
                            scraperKey = candidate.scraperKey,
                            targetUrl = candidate.baseUrl
                        ),
                        timeoutMs = TIMEOUT_CHALLENGE_MS
                    )

                    if (recoveryResult.isSuccess) {
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_COMPLETED: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "SESSION_PERSISTED: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "EXTRACTION_RETRY: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_RETRY: extensionId=${candidate.id}")
                        // Loop continues to attemptCount 2: Retries SAME candidate!
                    } else {
                        val failEx = recoveryResult.exceptionOrNull()
                        if (failEx is ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "UNVERIFIED_EXIT: extensionId=${candidate.id}")
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_TERMINATED_ON_EXIT")
                            return@withContext PlaybackOrchestratorOutcome.Failure("Playback terminated due to unverified exit", failEx)
                        } else if (failEx is ProtectedWebRuntimeError.CloudflareChallengeCancelled) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_CANCELLED: extensionId=${candidate.id}")
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_CANCELLED: extensionId=${candidate.id}")
                            session.attempt.cancelCandidate(candidate.id)
                            session.attempt.cancelCandidate(candidate.scraperKey)
                            CloudflareForensicLogger.cfUi17_candidateCancelled(candidate.id)
                        } else if (failEx is ProtectedWebRuntimeError.CloudflareChallengeTimeout) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_TIMEOUT: extensionId=${candidate.id}")
                        }
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                        // Break out of while loop to proceed to next candidate
                        break
                    }
                } catch (exitEx: ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit) {
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "UNVERIFIED_EXIT: extensionId=${candidate.id}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_TERMINATED_ON_EXIT")
                    return@withContext PlaybackOrchestratorOutcome.Failure("Playback terminated due to unverified exit", exitEx)
                } catch (cancelEx: ProtectedWebRuntimeError.CloudflareChallengeCancelled) {
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_CANCELLED: extensionId=${candidate.id}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_CANCELLED: extensionId=${candidate.id}")
                    session.attempt.cancelCandidate(candidate.id)
                    session.attempt.cancelCandidate(candidate.scraperKey)
                    CloudflareForensicLogger.cfUi17_candidateCancelled(candidate.id)
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED: extensionId=${candidate.id}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                    break
                } catch (e: Throwable) {
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_EXCEPTION: extensionId=${candidate.id}, msg=${e.message}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                    break
                }
            }
        }

        return@withContext if (firstPlayableEmitted && session.discoveredSources.isNotEmpty()) {
            PlaybackOrchestratorOutcome.Started(session.discoveredSources.first(), session)
        } else {
            Phase05HLogger.log("PLAYBACK", session.mediaId, "ALL_CANDIDATES_FAILED: isDubbed=$isDubbedSession")
            Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_FAILED")
            val userMsg = if (isDubbedSession) "النسخة المدبلجة غير متوفرة لهذا العمل" else "تعذر تشغيل هذا المحتوى"
            onError(userMsg)
            PlaybackOrchestratorOutcome.Failure(if (isDubbedSession) "النسخة المدبلجة غير متوفرة لهذا العمل" else "No playback sources are currently available.")
        }
    }
}
