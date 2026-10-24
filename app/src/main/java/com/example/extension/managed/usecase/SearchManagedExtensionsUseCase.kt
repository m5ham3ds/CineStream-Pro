package com.example.extension.managed.usecase

import com.example.domain.models.CoverageStatus
import com.example.domain.models.MediaVariantMode
import com.example.domain.models.ProviderSourceCandidate
import com.example.domain.models.VariantLanguage
import com.example.domain.search.SearchTriggerParser
import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchMediaItem
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.SearchResult
import com.example.extension.managed.runtime.FallbackManager
import com.example.extension.managed.trace.Phase05HLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Use case executing search queries across eligible Managed Extensions.
 *
 * Implements Phase 09B-B Specifications:
 * 1. Strict trigger: 'دبلجة' as standalone word ONLY triggers Arabic Dubbed multi-provider search.
 * 2. Words like 'مدبلج', 'مدبلجة', 'dubbed' DO NOT trigger dubbed search.
 * 3. Anime isolation: Anime is strictly subtitle-only; never queries dubbed groups.
 * 4. Multi-provider Fan-Out: Never stops at first successful provider; queries ALL eligible providers in arabicDubbed.
 * 5. Candidate preservation: Retains provider ID, source page URL, and candidates across all providers.
 * 6. Deduplication without collapsing: Merges matching items into a single unified item preserving all candidate sources.
 * 7. Accurate error status categorization: FOUND, NOT_FOUND, NETWORK_ERROR, TIMEOUT, CHALLENGE.
 * 8. English dubbed group is NEVER invoked while in MAINTENANCE mode.
 */
class SearchManagedExtensionsUseCase(
    private val resolver: ManagedExtensionResolver,
    private val runtime: ControlledManagedExtensionRuntime,
    private val fallbackManager: FallbackManager,
    private val isOnlineChecker: () -> Boolean = { true }
) {

    suspend fun execute(
        query: String,
        contentType: ContentType? = null,
        page: Int = 1
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return@withContext Result.success(SearchResult(items = emptyList(), page = page, hasNextPage = false))
        }

        if (!isOnlineChecker()) {
            return@withContext Result.failure(ExtensionError.NoInternet())
        }

        // 1. Analyze search trigger for 'دبلجة'
        val triggerResult = SearchTriggerParser.parse(trimmedQuery)

        // 2. Handle Anime Exception (Anime is strictly subtitle-only)
        if (contentType == ContentType.ANIME && triggerResult.isArabicDubbedIntent) {
            // Anime is strictly subtitle-only. Reject dubbed intent and return empty or error.
            return@withContext Result.failure(
                ExtensionError.MediaNotFound("محتوى الأنمي مترجم فقط ولا تتوفر له دبلجة")
            )
        }

        // 3. Dubbed search vs Regular search
        if (triggerResult.isArabicDubbedIntent) {
            executeArabicDubbedSearch(
                titleQuery = triggerResult.cleanQuery,
                hasValidTitle = triggerResult.hasValidMediaTitle,
                contentType = contentType,
                page = page
            )
        } else {
            executeRegularSearch(
                query = trimmedQuery,
                contentType = contentType,
                page = page
            )
        }
    }

    private suspend fun executeArabicDubbedSearch(
        titleQuery: String,
        hasValidTitle: Boolean,
        contentType: ContentType?,
        page: Int
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        if (!hasValidTitle || titleQuery.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("يرجى كتابة اسم العمل للبحث عن النسخة المدبلجة")
            )
        }

        // Resolve eligible providers from arabicDubbed group
        val candidates = resolver.resolveEligibleDubbedExtensions(
            language = VariantLanguage.ARABIC,
            contentType = contentType,
            capability = ScraperCapability.SEARCH
        )

        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                ExtensionError.MediaNotFound("لا تتوفر إضافات دبلجة مؤهلة حالياً")
            )
        }

        val effectiveContentType = contentType ?: ContentType.MOVIE
        val coverageRepo = com.example.data.repository.SourceCoverageRepository.INSTANCE

        // Check if coverage is already complete
        val isAlreadyComplete = coverageRepo.isCoverageComplete(
            canonicalWorkId = titleQuery,
            contentType = effectiveContentType,
            season = 1,
            episode = 1,
            mode = MediaVariantMode.DUBBED,
            language = VariantLanguage.ARABIC,
            providerGroup = "arabicDubbed",
            currentEligibleProviders = candidates
        )

        val existingRound = coverageRepo.getRound(
            canonicalWorkId = titleQuery,
            contentType = effectiveContentType,
            season = 1,
            episode = 1,
            mode = MediaVariantMode.DUBBED,
            language = VariantLanguage.ARABIC,
            providerGroup = "arabicDubbed"
        )

        val providersToQuery = if (isAlreadyComplete) {
            emptyList()
        } else {
            coverageRepo.getIncompleteProviders(
                canonicalWorkId = titleQuery,
                contentType = effectiveContentType,
                season = 1,
                episode = 1,
                mode = MediaVariantMode.DUBBED,
                language = VariantLanguage.ARABIC,
                providerGroup = "arabicDubbed",
                currentEligibleProviders = candidates
            )
        }

        val collectedCandidates = mutableListOf<ProviderSourceCandidate>()
        val collectedItems = mutableListOf<SearchMediaItem>()

        // Seed with existing valid records from the coverage repository if any
        if (existingRound != null) {
            for ((pId, rec) in existingRound.records) {
                if (rec.coverageStatus == CoverageStatus.FOUND && !rec.sourcePageUrl.isNullOrBlank()) {
                    val candidate = candidates.firstOrNull { it.id == pId }
                    val scraperKey = candidate?.scraperKey ?: rec.scraperKey
                    val providerTitle = rec.providerTitle?.takeIf { it.isNotBlank() } ?: titleQuery
                    val existingCandidate = ProviderSourceCandidate(
                        providerId = pId,
                        scraperKey = scraperKey,
                        sourcePageUrl = rec.sourcePageUrl,
                        title = providerTitle,
                        mode = MediaVariantMode.DUBBED,
                        audioLanguage = VariantLanguage.ARABIC,
                        status = CoverageStatus.FOUND,
                        posterUrl = rec.providerPosterUrl,
                        contentType = effectiveContentType
                    )
                    collectedCandidates.add(existingCandidate)
                    collectedItems.add(
                        SearchMediaItem(
                            id = "dubbed_${pId}_${titleQuery.hashCode()}",
                            title = providerTitle,
                            posterUrl = rec.providerPosterUrl,
                            url = rec.sourcePageUrl,
                            contentType = effectiveContentType,
                            providerId = pId,
                            sourceCandidates = listOf(existingCandidate)
                        )
                    )
                }
            }
        }

        if (providersToQuery.isNotEmpty()) {
            val searchRequest = SearchRequest(
                query = titleQuery,
                contentType = contentType,
                page = page,
                isArabicDubbedIntent = true
            )

            // Fan-out concurrent queries across ONLY incomplete/new candidates (do not stop at first!)
            val deferredList = providersToQuery.map { candidate ->
                async {
                    val outcome = try {
                        runtime.search(listOf(candidate), searchRequest)
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                    candidate to outcome
                }
            }

            val allResults = deferredList.awaitAll()

            for ((candidate, result) in allResults) {
                if (result.isSuccess) {
                    val searchResult = result.getOrThrow()
                    val dubbedItems = searchResult.items.filter { item ->
                        val t = item.title.lowercase()
                        val u = item.url.lowercase()
                        val hasDubbedMarker = t.contains("مدبلج") || t.contains("مدبلجة") || t.contains("دبلجة") ||
                                t.contains("dubbed") || u.contains("dubbed") || u.contains("مدبلج") || u.contains("دبلجة")
                        val hasStructuredEvidence = item.extraMetadata["audio"]?.contains("dub", ignoreCase = true) == true ||
                                item.sourceCandidates.any { it.mode == MediaVariantMode.DUBBED }
                        val isDedicatedDubbedCandidate = candidate.audioCapabilities.contains(com.example.domain.models.AudioCapability.ARABIC_DUB) &&
                                !candidate.audioCapabilities.contains(com.example.domain.models.AudioCapability.ORIGINAL_AUDIO)
                        hasDubbedMarker || hasStructuredEvidence || isDedicatedDubbedCandidate
                    }

                    if (dubbedItems.isNotEmpty()) {
                        for (item in dubbedItems) {
                            val sourceCandidate = ProviderSourceCandidate(
                                providerId = candidate.id,
                                scraperKey = candidate.scraperKey,
                                sourcePageUrl = item.url,
                                title = item.title,
                                mode = MediaVariantMode.DUBBED,
                                audioLanguage = VariantLanguage.ARABIC,
                                status = CoverageStatus.FOUND,
                                posterUrl = item.posterUrl,
                                contentType = item.contentType,
                                extraMetadata = item.extraMetadata
                            )
                            collectedCandidates.add(sourceCandidate)

                            val itemWithCandidate = item.copy(
                                providerId = candidate.id,
                                sourceCandidates = listOf(sourceCandidate)
                            )
                            collectedItems.add(itemWithCandidate)

                            coverageRepo.recordProviderCoverage(
                                canonicalWorkId = titleQuery,
                                contentType = effectiveContentType,
                                season = 1,
                                episode = 1,
                                mode = MediaVariantMode.DUBBED,
                                language = VariantLanguage.ARABIC,
                                providerGroup = "arabicDubbed",
                                record = com.example.domain.models.ProviderCoverageRecord(
                                    providerId = candidate.id,
                                    scraperKey = candidate.scraperKey,
                                    sourcePageUrl = item.url,
                                    coverageStatus = CoverageStatus.FOUND,
                                    providerTitle = item.title,
                                    providerPosterUrl = item.posterUrl
                                )
                            )
                            Phase05HLogger.log(
                                "DUBBED_SEARCH",
                                candidate.id,
                                "query='$titleQuery', status=FOUND, title='${item.title}', url='${item.url}'"
                            )
                        }
                    } else {
                        val notFoundCandidate = ProviderSourceCandidate(
                            providerId = candidate.id,
                            scraperKey = candidate.scraperKey,
                            sourcePageUrl = "",
                            title = titleQuery,
                            mode = MediaVariantMode.DUBBED,
                            audioLanguage = VariantLanguage.ARABIC,
                            status = CoverageStatus.NOT_FOUND
                        )
                        collectedCandidates.add(notFoundCandidate)
                        coverageRepo.recordProviderCoverage(
                            canonicalWorkId = titleQuery,
                            contentType = effectiveContentType,
                            season = 1,
                            episode = 1,
                            mode = MediaVariantMode.DUBBED,
                            language = VariantLanguage.ARABIC,
                            providerGroup = "arabicDubbed",
                            record = com.example.domain.models.ProviderCoverageRecord(
                                providerId = candidate.id,
                                scraperKey = candidate.scraperKey,
                                sourcePageUrl = "",
                                coverageStatus = CoverageStatus.NOT_FOUND
                            )
                        )
                        Phase05HLogger.log(
                            "DUBBED_SEARCH",
                            candidate.id,
                            "query='$titleQuery', status=NOT_FOUND"
                        )
                    }
                } else {
                    val error = result.exceptionOrNull()
                    val status = when (error) {
                        is ExtensionError.MediaNotFound -> CoverageStatus.NOT_FOUND
                        is ExtensionError.Timeout,
                        is kotlinx.coroutines.TimeoutCancellationException,
                        is java.net.SocketTimeoutException -> CoverageStatus.TIMEOUT
                        is ExtensionError.CloudflareChallenge,
                        is ExtensionError.ChallengeFailed -> CoverageStatus.CHALLENGE
                        is ExtensionError.CapabilityUnsupported,
                        is ExtensionError.ContentTypeUnsupported,
                        is ExtensionError.UnknownScraperKey,
                        is ExtensionError.MissingBundledScraper -> CoverageStatus.UNKNOWN_CAPABILITY
                        is ExtensionError.NoInternet,
                        is java.io.IOException,
                        is java.net.UnknownHostException,
                        is java.net.ConnectException -> CoverageStatus.NETWORK_ERROR
                        else -> {
                            val msg = error?.message?.lowercase() ?: ""
                            when {
                                msg.contains("timeout") || msg.contains("timed out") -> CoverageStatus.TIMEOUT
                                msg.contains("cloudflare") || msg.contains("challenge") || msg.contains("turnstile") -> CoverageStatus.CHALLENGE
                                msg.contains("not found") || msg.contains("not_found") -> CoverageStatus.NOT_FOUND
                                msg.contains("capability") || msg.contains("unsupported") -> CoverageStatus.UNKNOWN_CAPABILITY
                                msg.contains("network") || msg.contains("connection") || msg.contains("host") -> CoverageStatus.NETWORK_ERROR
                                else -> CoverageStatus.NETWORK_ERROR
                            }
                        }
                    }
                    val failedCandidate = ProviderSourceCandidate(
                        providerId = candidate.id,
                        scraperKey = candidate.scraperKey,
                        sourcePageUrl = "",
                        title = titleQuery,
                        mode = MediaVariantMode.DUBBED,
                        audioLanguage = VariantLanguage.ARABIC,
                        status = status,
                        errorMessage = error?.message
                    )
                    collectedCandidates.add(failedCandidate)
                    coverageRepo.recordProviderCoverage(
                        canonicalWorkId = titleQuery,
                        contentType = effectiveContentType,
                        season = 1,
                        episode = 1,
                        mode = MediaVariantMode.DUBBED,
                        language = VariantLanguage.ARABIC,
                        providerGroup = "arabicDubbed",
                        record = com.example.domain.models.ProviderCoverageRecord(
                            providerId = candidate.id,
                            scraperKey = candidate.scraperKey,
                            sourcePageUrl = "",
                            coverageStatus = status,
                            errorMessage = error?.message
                        )
                    )
                    Phase05HLogger.log(
                        "DUBBED_SEARCH",
                        candidate.id,
                        "query='$titleQuery', status=$status, error=${error?.message}"
                    )
                }
            }

            coverageRepo.markRoundComplete(
                canonicalWorkId = titleQuery,
                contentType = effectiveContentType,
                season = 1,
                episode = 1,
                mode = MediaVariantMode.DUBBED,
                language = VariantLanguage.ARABIC,
                providerGroup = "arabicDubbed",
                eligibleProviderIds = candidates.map { it.id }
            )
        }

        if (collectedItems.isEmpty()) {
            return@withContext Result.failure(
                ExtensionError.MediaNotFound("لم يتم العثور على نتائج مدبلجة لـ '$titleQuery'")
            )
        }

        // Deduplicate and aggregate candidates for matching titles
        val mergedItems = mergeAndDeduplicateItems(collectedItems)
        Result.success(SearchResult(items = mergedItems, page = page, hasNextPage = false))
    }

    private suspend fun executeRegularSearch(
        query: String,
        contentType: ContentType?,
        page: Int
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        val candidates = resolver.resolveEligibleExtensions(
            capability = ScraperCapability.SEARCH,
            contentType = contentType,
            allowDeprecated = false
        )

        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                ExtensionError.MediaNotFound("No eligible managed extensions available for search")
            )
        }

        val searchRequest = SearchRequest(
            query = query,
            contentType = contentType,
            page = page,
            isArabicDubbedIntent = false
        )

        // Query candidates concurrently across eligible providers
        val deferredList = candidates.map { candidate ->
            async {
                val outcome = try {
                    runtime.search(listOf(candidate), searchRequest)
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                candidate to outcome
            }
        }

        val allResults = deferredList.awaitAll()
        val collectedItems = mutableListOf<SearchMediaItem>()

        for ((candidate, result) in allResults) {
            if (result.isSuccess) {
                val searchResult = result.getOrThrow()
                for (item in searchResult.items) {
                    val t = item.title.lowercase()
                    val u = item.url.lowercase()
                    val hasDubbedMarker = t.contains("مدبلج") || t.contains("مدبلجة") || t.contains("دبلجة") ||
                            t.contains("dubbed") || u.contains("dubbed") || u.contains("مدبلج") || u.contains("دبلجة")
                    val candidateMode = if (hasDubbedMarker) MediaVariantMode.DUBBED else MediaVariantMode.SUBTITLED
                    val candidateLang = if (hasDubbedMarker) VariantLanguage.ARABIC else VariantLanguage.ORIGINAL
                    val sourceCandidate = ProviderSourceCandidate(
                        providerId = candidate.id,
                        scraperKey = candidate.scraperKey,
                        sourcePageUrl = item.url,
                        title = item.title,
                        mode = candidateMode,
                        audioLanguage = candidateLang,
                        status = CoverageStatus.FOUND,
                        posterUrl = item.posterUrl,
                        contentType = item.contentType,
                        extraMetadata = item.extraMetadata
                    )
                    collectedItems.add(
                        item.copy(
                            providerId = candidate.id,
                            sourceCandidates = listOf(sourceCandidate)
                        )
                    )
                }
            }
        }

        if (collectedItems.isEmpty()) {
            return@withContext Result.failure(
                ExtensionError.MediaNotFound("No results found for '$query'")
            )
        }

        val mergedItems = mergeAndDeduplicateItems(collectedItems)
        Result.success(SearchResult(items = mergedItems, page = page, hasNextPage = false))
    }

    private fun mergeAndDeduplicateItems(items: List<SearchMediaItem>): List<SearchMediaItem> {
        val grouped = items.groupBy { normalizeTitleForComparison(it.title) to it.contentType }
        return grouped.values.map { group ->
            val primary = group.first()
            val allCandidates = group.flatMap { it.sourceCandidates }.distinctBy { it.providerId to it.sourcePageUrl }
            val bestPoster = group.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl ?: primary.posterUrl
            primary.copy(posterUrl = bestPoster, sourceCandidates = allCandidates)
        }
    }

    private fun normalizeTitleForComparison(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace(Regex("[^a-zA-Z0-9\u0600-\u06FF]"), "")
    }
}
