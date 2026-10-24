package com.example.extension.managed.searchorder

import com.example.domain.models.AudioCapability
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.registry.ScraperRegistry

/**
 * Evaluates candidate extensions against the canonical eligibility pipeline and hard content-type isolation rules.
 *
 * Rules:
 * 1. Extension exists in ManagedExtension catalog.
 * 2. Local bundled scraper implementation exists in ScraperRegistry.
 * 3. Extension status == ACTIVE (Global Admin status has sole authority; userEnabled removed).
 * 4. HARD CONTENT-TYPE ISOLATION:
 *    - Anime-only NEVER executes for MOVIE or TV/SERIES.
 *    - Movie-only NEVER executes for TV/SERIES or ANIME.
 *    - TV-only NEVER executes for MOVIE or ANIME.
 *    - Multi-category executes only for explicitly supported categories.
 * 5. Scraper supports requested capability (SERVER_DISCOVERY / SEARCH).
 * 6. Audio capabilities match requested capability if specified (ARABIC_DUB / ENGLISH_DUB).
 * 7. Runtime API version is compatible (extension.runtimeApiVersion <= supportedRuntimeApiVersion).
 * 8. App version is compatible (extension.minAppVersionCode <= currentAppVersionCode).
 * 9. Remote configuration is valid (ManagedExtensionValidator passes).
 *
 * Order preservation:
 * Input order from SearchOrder is preserved EXACTLY.
 * Never sorted by local priority, never shuffled, never reordered.
 */
class ExtensionEligibilityFilter(
    private val scraperRegistry: ScraperRegistry = ScraperRegistry.INSTANCE,
    private val validator: ManagedExtensionValidator = ManagedExtensionValidator
) {

    fun filterEligibleExtensions(
        orderedExtensionIds: List<String>,
        availableExtensions: List<ManagedExtension>,
        targetContentType: ContentType,
        capability: ScraperCapability = ScraperCapability.SERVER_DISCOVERY,
        currentAppVersionCode: Long = 100L,
        supportedRuntimeApiVersion: Int = 1,
        requiredAudioCapability: AudioCapability? = null
    ): List<ManagedExtension> {
        if (orderedExtensionIds.isEmpty()) return emptyList()

        val extensionMap = availableExtensions.associateBy { it.id.trim().lowercase() }
        val eligibleList = mutableListOf<ManagedExtension>()
        val seenIds = mutableSetOf<String>()

        for (rawId in orderedExtensionIds) {
            val normalizedId = rawId.trim().lowercase()
            if (normalizedId.isBlank() || seenIds.contains(normalizedId)) {
                continue
            }
            seenIds.add(normalizedId)

            // 1. Exists in catalog
            val extension = extensionMap[normalizedId] ?: continue

            // 2. Local bundled scraper implementation exists
            val scraper = scraperRegistry.getScraper(extension.scraperKey) ?: continue

            // 3. Operational status must be ACTIVE and enabled == true
            if (extension.status != ExtensionLifecycleStatus.ACTIVE || !extension.enabled) {
                continue
            }

            // 4. HARD CONTENT-TYPE ISOLATION:
            // Check both extension remote metadata AND bundled scraper code
            if (!extension.contentTypes.contains(targetContentType)) {
                continue
            }
            if (!scraper.supportedContentTypes.contains(targetContentType)) {
                continue
            }

            // 5. Capability check
            if (!scraper.supportedCapabilities.contains(capability)) {
                continue
            }

            // 6. Audio capability check if requested (e.g. ARABIC_DUB)
            if (requiredAudioCapability != null) {
                if (!extension.audioCapabilities.contains(requiredAudioCapability)) {
                    // Do not assume capability from language or site name
                    continue
                }
            }

            // 7. Runtime API compatibility
            if (extension.runtimeApiVersion > supportedRuntimeApiVersion) {
                continue
            }

            // 8. App version compatibility
            if (extension.minAppVersionCode > currentAppVersionCode) {
                continue
            }

            // 9. Configuration validity
            val validationResult = validator.validate(extension)
            if (validationResult !is ManagedExtensionValidator.ValidationResult.Valid) {
                continue
            }

            eligibleList.add(extension)
        }

        return eligibleList
    }
}
