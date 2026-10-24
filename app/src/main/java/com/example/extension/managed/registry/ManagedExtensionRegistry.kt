package com.example.extension.managed.registry

import com.example.extension.managed.model.ExtensionRuntimeSnapshot
import com.example.extension.managed.model.GlobalExtensionConfigState
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.repository.ManagedExtensionCache
import com.example.extension.managed.repository.SafeLocalMetadataCache

/**
 * Phase 05M: ManagedExtensionRegistry inherits from ManagedExtensionRuntimeRegistry,
 * unifying the local runtime registry across all application consumers.
 */
class ManagedExtensionRegistry(
    cache: ManagedExtensionCache = SafeLocalMetadataCache(),
    initialSnapshot: ExtensionRuntimeSnapshot? = null
) : ManagedExtensionRuntimeRegistry(
    cache = cache,
    initialSnapshot = initialSnapshot
) {
    constructor(
        initialExtensions: List<ManagedExtension>,
        cache: ManagedExtensionCache = SafeLocalMetadataCache()
    ) : this(
        cache = cache,
        initialSnapshot = if (initialExtensions.isNotEmpty()) {
            ExtensionRuntimeSnapshot(
                extensions = initialExtensions,
                state = GlobalExtensionConfigState.READY
            )
        } else null
    )

    companion object {
        @Volatile
        private var instance: ManagedExtensionRegistry? = null

        val INSTANCE: ManagedExtensionRegistry
            get() = instance ?: synchronized(this) {
                instance ?: ManagedExtensionRegistry().also {
                    instance = it
                    ManagedExtensionRuntimeRegistry.setTestInstance(it)
                }
            }

        fun setTestInstance(testRegistry: ManagedExtensionRegistry) {
            instance = testRegistry
            ManagedExtensionRuntimeRegistry.setTestInstance(testRegistry)
        }
    }
}
