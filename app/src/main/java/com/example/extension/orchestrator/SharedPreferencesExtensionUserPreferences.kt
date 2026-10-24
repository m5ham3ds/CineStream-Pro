package com.example.extension.orchestrator

import android.content.Context
import android.content.SharedPreferences
import com.example.extension.managed.repository.ExtensionUserPreferences

/**
 * Persistent SharedPreferences-backed implementation of ExtensionUserPreferences.
 * Keeps user enable/disable toggles across application restarts.
 */
class SharedPreferencesExtensionUserPreferences(
    context: Context
) : ExtensionUserPreferences {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("managed_extension_user_prefs", Context.MODE_PRIVATE)

    override fun isExtensionEnabled(extensionId: String): Boolean {
        return prefs.getBoolean("enabled_$extensionId", true)
    }

    override fun setExtensionEnabled(extensionId: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$extensionId", enabled).apply()
    }

    override fun getAllPreferences(): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        for ((key, value) in prefs.all) {
            if (key.startsWith("enabled_") && value is Boolean) {
                result[key.removePrefix("enabled_")] = value
            }
        }
        return result
    }
}
