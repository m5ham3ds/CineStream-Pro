package com.example.extension.managed.repository

import com.example.extension.managed.model.ManagedExtension

/**
 * Domain repository contract for accessing validated ManagedExtension configurations.
 * Completely decoupled from UI, Scraper execution, and WebView internals.
 */
interface ManagedExtensionRepository {
    suspend fun getExtensions(forceRefresh: Boolean = false): Result<List<ManagedExtension>>
    suspend fun getExtensionById(id: String, forceRefresh: Boolean = false): Result<ManagedExtension>
}
