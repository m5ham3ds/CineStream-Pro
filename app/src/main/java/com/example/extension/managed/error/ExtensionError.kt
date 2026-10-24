package com.example.extension.managed.error

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ScraperCapability

sealed class ExtensionError(
    val code: String,
    final override val message: String,
    val isRecoverable: Boolean = true
) : Exception("[$code] $message") {
    data class NoInternet(
        val detail: String = "No internet connection available",
        override val cause: Throwable? = null
    ) : ExtensionError("NO_INTERNET", detail, isRecoverable = false)

    data class Timeout(
        val timeoutMs: Long,
        val detail: String = "Operation timed out after ${timeoutMs}ms"
    ) : ExtensionError("TIMEOUT", detail, isRecoverable = true)

    data class MediaNotFound(
        val query: String,
        val detail: String = "Media not found for query: $query"
    ) : ExtensionError("MEDIA_NOT_FOUND", detail, isRecoverable = true)

    data class SourceDisabled(
        val extensionId: String,
        val detail: String = "Source extension is disabled globally: $extensionId"
    ) : ExtensionError("SOURCE_DISABLED", detail, isRecoverable = true)

    data class MaintenanceHold(
        val extensionId: String,
        val detail: String = "Source extension is currently under maintenance: $extensionId"
    ) : ExtensionError("MAINTENANCE_HOLD", detail, isRecoverable = true)

    data class CloudflareChallenge(
        val detail: String = "Cloudflare verification challenge required"
    ) : ExtensionError("CLOUDFLARE_CHALLENGE", detail, isRecoverable = true)

    data class ChallengeFailed(
        val detail: String = "Cloudflare verification challenge failed"
    ) : ExtensionError("CHALLENGE_FAILED", detail, isRecoverable = true)

    data class ExtractionFailed(
        val detail: String = "Stream extraction failed",
        override val cause: Throwable? = null
    ) : ExtensionError("EXTRACTION_FAILED", detail, isRecoverable = true)

    data class IncompatibleAppVersion(
        val currentAppVersion: Long,
        val requiredMinAppVersion: Long
    ) : ExtensionError(
        "INCOMPATIBLE_APP_VERSION",
        "App version $currentAppVersion is below required minimum $requiredMinAppVersion",
        isRecoverable = false
    )

    data class IncompatibleRuntime(
        val currentRuntimeApi: Int,
        val requiredRuntimeApi: Int
    ) : ExtensionError(
        "INCOMPATIBLE_RUNTIME",
        "Extension requires runtime API $requiredRuntimeApi but app only supports $currentRuntimeApi",
        isRecoverable = false
    )

    data class UnknownScraperKey(
        val scraperKey: String
    ) : ExtensionError(
        "UNKNOWN_SCRAPER_KEY",
        "Unknown scraper key specified: $scraperKey",
        isRecoverable = true
    )

    data class MissingBundledScraper(
        val scraperKey: String
    ) : ExtensionError(
        "MISSING_BUNDLED_SCRAPER",
        "No bundled scraper implementation found for key: $scraperKey",
        isRecoverable = true
    )

    data class InvalidBaseUrl(
        val url: String,
        val reason: String
    ) : ExtensionError(
        "INVALID_BASE_URL",
        "Invalid baseUrl '$url': $reason",
        isRecoverable = true
    )

    data class ContentTypeUnsupported(
        val contentType: ContentType
    ) : ExtensionError(
        "CONTENT_TYPE_UNSUPPORTED",
        "Content type $contentType is not supported by this extension",
        isRecoverable = true
    )

    data class CapabilityUnsupported(
        val capability: ScraperCapability
    ) : ExtensionError(
        "CAPABILITY_UNSUPPORTED",
        "Capability $capability is not supported by this extension",
        isRecoverable = true
    )

    data class UserDisabled(
        val extensionId: String
    ) : ExtensionError(
        "USER_DISABLED",
        "Extension is disabled in user local settings: $extensionId",
        isRecoverable = false
    )

    data class InvalidConfiguration(
        val detail: String,
        override val cause: Throwable? = null
    ) : ExtensionError("INVALID_CONFIGURATION", detail, isRecoverable = false)

    data class InvalidMedia(
        val detail: String,
        override val cause: Throwable? = null
    ) : ExtensionError("INVALID_MEDIA", detail, isRecoverable = true)

    data class ParseError(
        val detail: String = "Failed to parse website DOM or response structure",
        override val cause: Throwable? = null
    ) : ExtensionError("PARSE_ERROR", detail, isRecoverable = true)

    data class SecurityViolation(
        val detail: String,
        override val cause: Throwable? = null
    ) : ExtensionError("SECURITY_VIOLATION", detail, isRecoverable = false)

    data class GlobalConfigUnavailable(
        val detail: String = "Global extension configuration is unavailable and no verified last-known-good state exists",
        override val cause: Throwable? = null
    ) : ExtensionError("GLOBAL_CONFIG_UNAVAILABLE", detail, isRecoverable = false)

    data class UnknownError(
        val detail: String,
        override val cause: Throwable? = null
    ) : ExtensionError("UNKNOWN_ERROR", detail, isRecoverable = false)
}

class ExtensionException(val error: ExtensionError) : Exception(error.message)
