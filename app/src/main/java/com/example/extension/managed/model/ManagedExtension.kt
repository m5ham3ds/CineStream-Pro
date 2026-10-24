package com.example.extension.managed.model

import com.example.domain.models.AudioCapability

/**
 * Remote configuration snapshot representing a managed extension.
 * This is an untrusted remote snapshot and does not contain scraper execution logic.
 */
data class ManagedExtension(
    val id: String,
    val name: String,
    val description: String = "",
    val baseUrl: String,
    val iconUrl: String = "",
    val scraperKey: String,
    val definitionVersion: Int = 1,
    val minAppVersionCode: Long = 1L,
    val runtimeApiVersion: Int = 1,
    val priority: Int = 0,
    val language: String = "ar",
    val contentTypes: Set<ContentType> = emptySet(),
    val capabilities: Set<ScraperCapability> = emptySet(),
    val audioCapabilities: Set<AudioCapability> = emptySet(),
    val searchUrl: String? = null,
    val enabled: Boolean = true,
    val status: ExtensionLifecycleStatus = ExtensionLifecycleStatus.ACTIVE,
    val updatedAt: Long = System.currentTimeMillis(),
    val userEnabled: Boolean = true
)

