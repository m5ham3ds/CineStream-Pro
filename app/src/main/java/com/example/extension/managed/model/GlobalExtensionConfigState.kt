package com.example.extension.managed.model

/**
 * Phase 05H: Represents the global administrative synchronization state of managed extensions.
 *
 * States:
 * - REMOTE_SYNC_PENDING: Initial startup state before first remote synchronization completes.
 * - READY: Global configuration has been obtained, validated, and applied (or restored from last-known-good cache).
 * - GLOBAL_CONFIG_UNAVAILABLE: Remote configuration failed and no last-known-good cache exists; blocks source-dependent playback.
 */
enum class GlobalExtensionConfigState {
    REMOTE_SYNC_PENDING,
    READY,
    GLOBAL_CONFIG_UNAVAILABLE
}
