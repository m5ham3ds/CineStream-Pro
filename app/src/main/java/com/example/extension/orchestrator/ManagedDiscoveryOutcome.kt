package com.example.extension.orchestrator

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.ServerItem

/**
 * Result outcome of a server discovery operation through the Managed Extension pipeline.
 */
sealed class ManagedDiscoveryOutcome {

    /**
     * Managed scraper successfully discovered playback/download servers.
     */
    data class Success(
        val servers: List<ServerItem>,
        val website: String,
        val sourceUrl: String,
        val directStream: PlaybackSource? = null
    ) : ManagedDiscoveryOutcome()

    /**
     * Recoverable error (e.g. title not found on this site, temporary network timeout).
     * Managed-only fallback across candidate extensions is handled centrally.
     */
    data class RecoverableFailure(
        val error: ExtensionError
    ) : ManagedDiscoveryOutcome()

    /**
     * Non-recoverable security or compatibility failure (e.g. unsafe URL, incompatible runtime, disabled).
     * Must halt immediately and NEVER fallback blindly.
     */
    data class SecurityFailure(
        val error: ExtensionError
    ) : ManagedDiscoveryOutcome()
}
