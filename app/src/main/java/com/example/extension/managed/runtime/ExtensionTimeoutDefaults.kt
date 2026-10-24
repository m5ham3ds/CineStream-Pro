package com.example.extension.managed.runtime

/**
 * Standard centralized timeout limits for managed extension pipeline operations.
 */
object ExtensionTimeoutDefaults {
    const val SEARCH_TIMEOUT_MS = 15_000L
    const val DETAILS_TIMEOUT_MS = 15_000L
    const val EPISODES_TIMEOUT_MS = 15_000L
    const val SERVER_DISCOVERY_TIMEOUT_MS = 15_000L
    const val WEB_EXTRACTION_TIMEOUT_MS = 15_000L
    const val STREAM_EXTRACTION_TIMEOUT_MS = 15_000L
    const val CHALLENGE_TIMEOUT_MS = 15_000L
}
