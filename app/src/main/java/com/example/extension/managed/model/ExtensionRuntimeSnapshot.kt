package com.example.extension.managed.model

import com.example.extension.managed.searchorder.SearchOrder

/**
 * Phase 05M: Canonical runtime snapshot source indicator.
 */
enum class SnapshotSource {
    REMOTE,
    LOCAL_LKG
}

/**
 * Phase 05M: Immutable, atomic runtime snapshot model representing the complete
 * local extension configuration state for Playback, Download, and Extensions UI.
 */
data class ExtensionRuntimeSnapshot(
    val revision: Long = 0L,
    val extensions: List<ManagedExtension> = emptyList(),
    val searchOrder: SearchOrder = SearchOrder(),
    val syncedAt: Long = 0L,
    val source: SnapshotSource = SnapshotSource.LOCAL_LKG,
    val state: GlobalExtensionConfigState = GlobalExtensionConfigState.REMOTE_SYNC_PENDING
) {
    val isReady: Boolean
        get() = state == GlobalExtensionConfigState.READY

    fun getActiveExtensions(): List<ManagedExtension> {
        return extensions.filter { it.status == ExtensionLifecycleStatus.ACTIVE }
    }

    fun hasActiveExtensions(contentType: ContentType? = null): Boolean {
        val active = getActiveExtensions()
        return if (contentType != null) {
            active.any { it.contentTypes.contains(contentType) }
        } else {
            active.isNotEmpty()
        }
    }
}
