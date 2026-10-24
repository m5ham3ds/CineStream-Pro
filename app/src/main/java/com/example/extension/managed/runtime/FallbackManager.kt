package com.example.extension.managed.runtime

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.registry.ScraperRegistry

/**
 * Manages deterministic priority-based fallback ordering and filtering across candidate extensions.
 */
class FallbackManager(
    private val scraperRegistry: ScraperRegistry = ScraperRegistry.INSTANCE
) {

    fun filterAndSortCandidates(
        candidates: List<ManagedExtension>,
        contentType: ContentType,
        capability: ScraperCapability,
        appVersionCode: Long,
        supportedRuntimeApi: Int
    ): List<ManagedExtension> {
        return candidates.filter { ext ->
            // 1. Snapshot structural validation
            if (ManagedExtensionValidator.validate(ext) !is ManagedExtensionValidator.ValidationResult.Valid) {
                return@filter false
            }

            // 2. Lifecycle status check (DEPRECATED is excluded from auto-fallback, MAINTENANCE & DISABLED excluded)
            if (ext.status != ExtensionLifecycleStatus.ACTIVE) {
                return@filter false
            }

            // 3. Version compatibility
            if (appVersionCode < ext.minAppVersionCode) {
                return@filter false
            }

            if (ext.runtimeApiVersion > supportedRuntimeApi) {
                return@filter false
            }

            // 5. Content type check
            if (!ext.contentTypes.contains(contentType)) {
                return@filter false
            }

            // 6. Capability check against bundled scraper
            val scraper = scraperRegistry.getScraper(ext.scraperKey) ?: return@filter false
            if (!scraper.supportedCapabilities.contains(capability)) {
                return@filter false
            }
            if (!scraper.supportedContentTypes.contains(contentType)) {
                return@filter false
            }

            true
        }.sortedByDescending { it.priority } // Higher number = higher priority
    }

    fun isRecoverable(error: ExtensionError): Boolean {
        return when (error) {
            is ExtensionError.NoInternet -> false
            is ExtensionError.IncompatibleAppVersion -> false
            is ExtensionError.IncompatibleRuntime -> false
            is ExtensionError.UserDisabled -> false
            is ExtensionError.SecurityViolation -> false
            is ExtensionError.InvalidConfiguration -> false
            is ExtensionError.UnknownError -> false
            else -> error.isRecoverable
        }
    }
}
