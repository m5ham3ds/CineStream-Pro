package com.example.extension.managed.registry

import com.example.extension.managed.model.*
import com.example.extension.managed.repository.ManagedExtensionCache
import com.example.extension.managed.repository.SafeLocalMetadataCache
import com.example.extension.managed.searchorder.SearchOrder
import com.example.extension.managed.trace.Phase05HLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 05M: Canonical Local Runtime Registry.
 *
 * Serves as the SINGLE LOCAL SOURCE OF TRUTH across:
 * - Playback (PlaybackOrchestrator / ServerStateStore)
 * - Downloads (UnifiedDownloadCoordinator / BatchDownloadProcessor)
 * - Extensions Screen (ExtensionsScreen / ExtensionsViewModel)
 *
 * Enforces Phase 05M Hard Rules:
 * 1. ZERO Firebase in Playback / Download path.
 * 2. Immutable ExtensionRuntimeSnapshot published via StateFlow.
 * 3. Search Order is canonical for ordering; priority does NOT override search order.
 * 4. Admin authority preserved: DISABLED cannot be resurrected.
 * 5. Atomic snapshot commits with revision protection.
 * 6. LKG persistent disk restoration across process restarts and offline mode.
 */
open class ManagedExtensionRuntimeRegistry(
    private val cache: ManagedExtensionCache = SafeLocalMetadataCache(),
    initialSnapshot: ExtensionRuntimeSnapshot? = null
) {
    private val _snapshotFlow: MutableStateFlow<ExtensionRuntimeSnapshot>
    val snapshotFlow: StateFlow<ExtensionRuntimeSnapshot>

    private val _extensionsFlow: MutableStateFlow<List<ManagedExtension>>
    val extensionsFlow: StateFlow<List<ManagedExtension>>

    private val _searchOrderFlow: MutableStateFlow<SearchOrder>
    val searchOrderFlow: StateFlow<SearchOrder>

    init {
        val initial = initialSnapshot ?: run {
            val lkg = cache.loadSnapshot()
            if (lkg != null && (lkg.extensions.isNotEmpty() || !lkg.searchOrder.isEmpty)) {
                Phase05HLogger.log(
                    "SYNC",
                    "lkg",
                    "EXT_SYNC_LKG_LOADED: count=${lkg.extensions.size}, active=${lkg.getActiveExtensions().size}, rev=${lkg.revision}"
                )
                lkg.copy(source = SnapshotSource.LOCAL_LKG, state = GlobalExtensionConfigState.READY)
            } else {
                ExtensionRuntimeSnapshot(
                    state = GlobalExtensionConfigState.READY,
                    extensions = com.example.extension.orchestrator.ManagedMediaOrchestrator.BUNDLED_DEFAULT_EXTENSIONS,
                    source = SnapshotSource.LOCAL_LKG
                )
            }
        }
        _snapshotFlow = MutableStateFlow(initial)
        snapshotFlow = _snapshotFlow.asStateFlow()

        _extensionsFlow = MutableStateFlow(initial.extensions)
        extensionsFlow = _extensionsFlow.asStateFlow()

        _searchOrderFlow = MutableStateFlow(initial.searchOrder)
        searchOrderFlow = _searchOrderFlow.asStateFlow()
    }

    fun getCurrentSnapshot(): ExtensionRuntimeSnapshot = _snapshotFlow.value

    fun getAllExtensions(): List<ManagedExtension> = _snapshotFlow.value.extensions

    fun getActiveExtensions(): List<ManagedExtension> = _snapshotFlow.value.getActiveExtensions()

    fun getSearchOrder(): SearchOrder = _snapshotFlow.value.searchOrder

    fun getOrderForContentType(contentType: ContentType): List<String> =
        _snapshotFlow.value.searchOrder.getOrderForContentType(contentType)

    fun getExtension(id: String): ManagedExtension? =
        _snapshotFlow.value.extensions.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun getExtensionById(id: String): ManagedExtension? = getExtension(id)

    fun getExtensionByScraperKey(scraperKey: String): ManagedExtension? =
        _snapshotFlow.value.extensions.firstOrNull { it.scraperKey.equals(scraperKey, ignoreCase = true) }

    fun hasActiveExtensions(contentType: ContentType? = null): Boolean =
        _snapshotFlow.value.hasActiveExtensions(contentType)

    /**
     * Atomically commits a new validated snapshot.
     * Enforces revision protection and persistent disk LKG saving.
     */
    @Synchronized
    fun commitSnapshot(newSnapshot: ExtensionRuntimeSnapshot): Boolean {
        val current = _snapshotFlow.value

        // Revision protection: reject strictly older revision if both are REMOTE
        if (newSnapshot.source == SnapshotSource.REMOTE && current.source == SnapshotSource.REMOTE) {
            if (newSnapshot.revision > 0 && current.revision > 0 && newSnapshot.revision < current.revision) {
                Phase05HLogger.log(
                    "SYNC",
                    "registry",
                    "EXT_SYNC_SNAPSHOT_REJECTED: incoming rev ${newSnapshot.revision} < current rev ${current.revision}"
                )
                return false
            }
        }

        Phase05HLogger.log(
            "SYNC",
            "registry",
            "EXT_SYNC_SNAPSHOT_COMMITTED: rev=${newSnapshot.revision}, count=${newSnapshot.extensions.size}, active=${newSnapshot.getActiveExtensions().size}, source=${newSnapshot.source}"
        )

        _snapshotFlow.value = newSnapshot
        _extensionsFlow.value = newSnapshot.extensions
        _searchOrderFlow.value = newSnapshot.searchOrder

        // Persist to LKG cache atomically
        if (newSnapshot.extensions.isNotEmpty() || !newSnapshot.searchOrder.isEmpty || newSnapshot.state == GlobalExtensionConfigState.READY) {
            cache.saveSnapshot(newSnapshot)
        }

        if (current.state != newSnapshot.state) {
            Phase05HLogger.log("SYNC", "registry", "EXT_SYNC_STATE_CHANGED: ${current.state} -> ${newSnapshot.state}")
        }
        return true
    }

    /**
     * Atomically updates extensions and/or search order.
     */
    @Synchronized
    fun commitRemoteUpdate(
        newExtensions: List<ManagedExtension>? = null,
        newSearchOrder: SearchOrder? = null,
        revision: Long = System.currentTimeMillis(),
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        val current = _snapshotFlow.value
        val updatedExtensions = newExtensions ?: current.extensions
        val updatedSearchOrder = newSearchOrder ?: current.searchOrder
        val updatedState = if (updatedExtensions.isNotEmpty() || !updatedSearchOrder.isEmpty) {
            GlobalExtensionConfigState.READY
        } else {
            current.state
        }

        val updatedSnapshot = ExtensionRuntimeSnapshot(
            revision = revision,
            extensions = updatedExtensions,
            searchOrder = updatedSearchOrder,
            syncedAt = timestamp,
            source = SnapshotSource.REMOTE,
            state = updatedState
        )
        return commitSnapshot(updatedSnapshot)
    }

    /**
     * Updates extensions while preserving current search order.
     */
    fun setExtensions(newExtensions: List<ManagedExtension>) {
        commitRemoteUpdate(newExtensions = newExtensions)
    }

    /**
     * Updates search order while preserving current extensions.
     */
    fun setSearchOrder(newSearchOrder: SearchOrder) {
        commitRemoteUpdate(newSearchOrder = newSearchOrder)
    }

    fun updateExtensionUserPreference(extensionId: String, enabled: Boolean) {
        val current = _snapshotFlow.value
        val updated = current.extensions.map { ext ->
            if (ext.id.equals(extensionId, ignoreCase = true)) ext.copy(userEnabled = enabled) else ext
        }
        commitSnapshot(current.copy(extensions = updated))
    }

    companion object {
        @Volatile
        private var instance: ManagedExtensionRuntimeRegistry? = null

        val INSTANCE: ManagedExtensionRuntimeRegistry
            get() = instance ?: synchronized(this) {
                instance ?: ManagedExtensionRegistry.INSTANCE.also { instance = it }
            }

        fun setTestInstance(testRegistry: ManagedExtensionRuntimeRegistry) {
            instance = testRegistry
        }
    }
}
