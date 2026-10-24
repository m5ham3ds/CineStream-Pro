package com.example.extension.managed.repository

/**
 * Contract for managing local user preferences regarding extension enablement.
 * Independent of remote Firestore configuration.
 */
interface ExtensionUserPreferences {
    fun isExtensionEnabled(extensionId: String): Boolean
    fun setExtensionEnabled(extensionId: String, enabled: Boolean)
    fun getAllPreferences(): Map<String, Boolean>
}

/**
 * Thread-safe in-memory implementation of user preferences.
 */
class InMemoryExtensionUserPreferences(
    initialPreferences: Map<String, Boolean> = emptyMap()
) : ExtensionUserPreferences {
    private val preferences = java.util.concurrent.ConcurrentHashMap<String, Boolean>(initialPreferences)

    override fun isExtensionEnabled(extensionId: String): Boolean {
        // By default, extensions are enabled unless explicitly disabled by the user locally
        return preferences[extensionId] ?: true
    }

    override fun setExtensionEnabled(extensionId: String, enabled: Boolean) {
        preferences[extensionId] = enabled
    }

    override fun getAllPreferences(): Map<String, Boolean> {
        return java.util.Collections.unmodifiableMap(preferences)
    }
}
