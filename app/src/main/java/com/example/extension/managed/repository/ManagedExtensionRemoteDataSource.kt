package com.example.extension.managed.repository

/**
 * Abstraction for fetching raw remote DTOs from Firestore.
 * Facilitates strict unit testing without requiring live Firebase production servers.
 */
interface ManagedExtensionRemoteDataSource {
    suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>>
}
