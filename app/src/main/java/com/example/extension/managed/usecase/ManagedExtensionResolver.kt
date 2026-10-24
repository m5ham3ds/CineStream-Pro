package com.example.extension.managed.usecase

import com.example.domain.models.AudioCapability
import com.example.domain.models.VariantLanguage
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.trace.Phase05HLogger

/**
 * Result of evaluating an extension's eligibility for execution.
 */
sealed class ExtensionEligibilityResult {
    data class Eligible(val extension: ManagedExtension) : ExtensionEligibilityResult()
    data class Ineligible(
        val extensionId: String,
        val reason: IneligibilityReason,
        val details: String
    ) : ExtensionEligibilityResult()
}

enum class IneligibilityReason {
    LIFECYCLE_DISABLED,
    LIFECYCLE_MAINTENANCE,
    LIFECYCLE_DEPRECATED,
    USER_DISABLED,
    INCOMPATIBLE_APP_VERSION,
    INCOMPATIBLE_RUNTIME_API,
    UNSUPPORTED_CONTENT_TYPE,
    MISSING_CAPABILITY,
    MISSING_AUDIO_CAPABILITY,
    INVALID_CONFIGURATION,
    UNKNOWN_SCRAPER
}

/**
 * Resolves eligible Managed Extensions according to canonical security and lifecycle rules:
 * - ACTIVE allowed
 * - DISABLED rejected
 * - MAINTENANCE rejected for normal execution
 * - DEPRECATED excluded from automatic execution/fallback
 * - local userEnabled enforced (global ACTIVE + user disabled = unusable)
 * - appVersionCode & runtimeApiVersion checked
 * - scraperKey resolved against ScraperRegistry (never dynamic loading)
 * - sorted by priority descending (HIGHER NUMBER = HIGHER PRIORITY)
 */
class ManagedExtensionResolver(
    private val registry: ManagedExtensionRegistry = ManagedExtensionRegistry.INSTANCE,
    private val scraperRegistry: ScraperRegistry = ScraperRegistry.INSTANCE,
    private val currentAppVersionCode: Long = 1L,
    private val supportedRuntimeApiVersion: Int = 1
) {

    /**
     * Evaluates a single extension for execution eligibility under given constraints.
     */
    fun evaluateEligibility(
        extension: ManagedExtension,
        capability: ScraperCapability? = null,
        contentType: ContentType? = null,
        requiredAudioCapability: AudioCapability? = null,
        allowDeprecated: Boolean = false
    ): ExtensionEligibilityResult {
        // 1. Structural and configuration validation
        val validation = ManagedExtensionValidator.validate(extension)
        if (validation is ManagedExtensionValidator.ValidationResult.Invalid) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.INVALID_CONFIGURATION,
                details = validation.error.message ?: "Invalid configuration"
            )
        }

        // 2. Lifecycle status enforcement
        when (extension.status) {
            ExtensionLifecycleStatus.DISABLED -> {
                return ExtensionEligibilityResult.Ineligible(
                    extensionId = extension.id,
                    reason = IneligibilityReason.LIFECYCLE_DISABLED,
                    details = "Extension is globally disabled in configuration"
                )
            }
            ExtensionLifecycleStatus.MAINTENANCE -> {
                return ExtensionEligibilityResult.Ineligible(
                    extensionId = extension.id,
                    reason = IneligibilityReason.LIFECYCLE_MAINTENANCE,
                    details = "Extension is in maintenance mode"
                )
            }
            ExtensionLifecycleStatus.DEPRECATED -> {
                if (!allowDeprecated) {
                    return ExtensionEligibilityResult.Ineligible(
                        extensionId = extension.id,
                        reason = IneligibilityReason.LIFECYCLE_DEPRECATED,
                        details = "Extension is deprecated and excluded from automatic execution"
                    )
                }
            }
            ExtensionLifecycleStatus.ACTIVE -> {
                // ACTIVE is allowed
            }
        }

        // Check enabled flag
        if (!extension.enabled) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.LIFECYCLE_DISABLED,
                details = "Extension is disabled"
            )
        }

        // 3. App version compatibility
        if (extension.minAppVersionCode > currentAppVersionCode) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.INCOMPATIBLE_APP_VERSION,
                details = "Requires app version ${extension.minAppVersionCode}, current is $currentAppVersionCode"
            )
        }

        // 5. Runtime API compatibility
        if (extension.runtimeApiVersion > supportedRuntimeApiVersion) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.INCOMPATIBLE_RUNTIME_API,
                details = "Requires runtime API ${extension.runtimeApiVersion}, supported is $supportedRuntimeApiVersion"
            )
        }

        // 6. Content type check
        if (contentType != null && !extension.contentTypes.contains(contentType)) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.UNSUPPORTED_CONTENT_TYPE,
                details = "Does not support content type $contentType"
            )
        }

        // 7. Scraper resolution check (Strictly through ScraperRegistry - never reflection)
        val scraper = scraperRegistry.getScraper(extension.scraperKey)
        if (scraper == null) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.UNKNOWN_SCRAPER,
                details = "No bundled scraper implementation registered for scraperKey '${extension.scraperKey}'"
            )
        }

        // 8. Capability check
        if (capability != null && !scraper.supportedCapabilities.contains(capability)) {
            return ExtensionEligibilityResult.Ineligible(
                extensionId = extension.id,
                reason = IneligibilityReason.MISSING_CAPABILITY,
                details = "Bundled scraper '${extension.scraperKey}' does not support capability $capability"
            )
        }

        // 9. Audio Capability check
        if (requiredAudioCapability != null) {
            if (!extension.audioCapabilities.contains(requiredAudioCapability)) {
                return ExtensionEligibilityResult.Ineligible(
                    extensionId = extension.id,
                    reason = IneligibilityReason.MISSING_AUDIO_CAPABILITY,
                    details = "Extension '${extension.id}' does not support audio capability $requiredAudioCapability"
                )
            }
        } else {
            // In standard/subtitled mode: dubbed-only extensions are excluded
            val isDubbedOnly = extension.audioCapabilities.contains(AudioCapability.ARABIC_DUB) &&
                !extension.audioCapabilities.contains(AudioCapability.ORIGINAL_AUDIO)
            if (isDubbedOnly) {
                return ExtensionEligibilityResult.Ineligible(
                    extensionId = extension.id,
                    reason = IneligibilityReason.MISSING_AUDIO_CAPABILITY,
                    details = "Extension '${extension.id}' is dubbed-only and excluded from subtitled mode"
                )
            }
        }

        return ExtensionEligibilityResult.Eligible(extension)
    }

    /**
     * Resolves all eligible candidate extensions, sorted by priority descending (HIGHER NUMBER = HIGHER PRIORITY).
     */
    fun resolveEligibleExtensions(
        capability: ScraperCapability? = null,
        contentType: ContentType? = null,
        requiredAudioCapability: AudioCapability? = null,
        allowDeprecated: Boolean = false
    ): List<ManagedExtension> {
        val all = registry.getAllExtensions()
        return all
            .filter { evaluateEligibility(it, capability, contentType, requiredAudioCapability, allowDeprecated) is ExtensionEligibilityResult.Eligible }
            .sortedByDescending { it.priority }
    }

    /**
     * Resolves eligible extensions for Dubbed search from the authoritative SearchOrder group.
     * Strictly blocks network calls when the group is in MAINTENANCE or DISABLED.
     * Never infers audio capabilities from domain or site name.
     */
    fun resolveEligibleDubbedExtensions(
        language: VariantLanguage,
        contentType: ContentType? = null,
        capability: ScraperCapability = ScraperCapability.SEARCH
    ): List<ManagedExtension> {
        val searchOrder = registry.getSearchOrder()
        if (!searchOrder.isDubbedAllowed(language)) {
            Phase05HLogger.log("RESOLVER", "dubbed", "DUBBED_GROUP_BLOCKED: group for $language is ${searchOrder.getDubbedStatus(language)}")
            return emptyList()
        }

        val requiredAudio = when (language) {
            VariantLanguage.ARABIC -> AudioCapability.ARABIC_DUB
            VariantLanguage.ENGLISH -> AudioCapability.ENGLISH_DUB
            VariantLanguage.ORIGINAL -> null
        }

        val orderedIds = searchOrder.getDubbedOrder(language)
        if (orderedIds.isEmpty()) {
            Phase05HLogger.log("RESOLVER", "dubbed", "DUBBED_GROUP_EMPTY: No providers configured in searchOrder for $language")
            return emptyList()
        }

        val all = registry.getAllExtensions()
        val extensionMap = all.associateBy { it.id.trim().lowercase() }

        val result = mutableListOf<ManagedExtension>()
        val seen = mutableSetOf<String>()

        // Process extensions in authoritative searchOrder order strictly
        for (rawId in orderedIds) {
            val normId = rawId.trim().lowercase()
            if (normId.isBlank() || seen.contains(normId)) continue
            seen.add(normId)
            val ext = extensionMap[normId]
            if (ext == null) {
                Phase05HLogger.log("RESOLVER", normId, "CONFIGURED_PROVIDER_NOT_REGISTERED: Provider in searchOrder.$language not found in registry")
                continue
            }

            // Check eligibility:
            val eligibility = evaluateEligibility(ext, capability, contentType, requiredAudio)
            if (eligibility is ExtensionEligibilityResult.Eligible) {
                result.add(ext)
                Phase05HLogger.log("RESOLVER", ext.id, "DUBBED_PROVIDER_ELIGIBLE: Provider is eligible for $language dubbed search")
            } else if (eligibility is ExtensionEligibilityResult.Ineligible &&
                eligibility.reason == IneligibilityReason.MISSING_AUDIO_CAPABILITY
            ) {
                // Reconcile: Admin explicitly declared this extension belongs to the dedicated dubbed group.
                // Verify all other criteria (lifecycle, scraper, capabilities, version)
                val baseEligibility = evaluateEligibility(ext, capability, contentType, null)
                if (baseEligibility is ExtensionEligibilityResult.Eligible) {
                    result.add(ext)
                    Phase05HLogger.log("RESOLVER", ext.id, "DUBBED_PROVIDER_RECONCILED: Provider membership in searchOrder.$language authoritative despite missing duplicate audioCapability")
                } else {
                    Phase05HLogger.log("RESOLVER", ext.id, "DUBBED_PROVIDER_INELIGIBLE: Provider excluded: ${(baseEligibility as? ExtensionEligibilityResult.Ineligible)?.details}")
                }
            } else {
                Phase05HLogger.log("RESOLVER", ext.id, "DUBBED_PROVIDER_INELIGIBLE: Provider excluded: ${(eligibility as? ExtensionEligibilityResult.Ineligible)?.details}")
            }
        }

        // Do NOT append unrelated extensions outside the authoritative searchOrder
        return result
    }

    /**
     * Finds an eligible extension by ID.
     */
    fun resolveExtensionById(
        extensionId: String,
        capability: ScraperCapability? = null,
        contentType: ContentType? = null,
        requiredAudioCapability: AudioCapability? = null,
        allowDeprecated: Boolean = false
    ): ManagedExtension? {
        val ext = registry.getExtension(extensionId) ?: return null
        return if (evaluateEligibility(ext, capability, contentType, requiredAudioCapability, allowDeprecated) is ExtensionEligibilityResult.Eligible) {
            ext
        } else {
            null
        }
    }
}
