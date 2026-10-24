package com.example.extension.managed.contract

import com.example.extension.managed.model.EpisodeItem
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ExtractionResult
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.MediaDetailsResult
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.SearchResult
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.model.ServerDiscoveryResult

interface ControlledManagedExtensionRuntime {
    val supportedRuntimeApiVersion: Int
    val currentAppVersionCode: Long

    suspend fun search(
        extensions: List<ManagedExtension>,
        request: SearchRequest
    ): Result<SearchResult>

    suspend fun search(
        request: SearchRequest
    ): Result<SearchResult>

    suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult>

    suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>>

    suspend fun discoverServers(
        extensions: List<ManagedExtension>,
        request: ServerDiscoveryRequest
    ): Result<ServerDiscoveryResult>

    suspend fun discoverServers(
        request: ServerDiscoveryRequest
    ): Result<ServerDiscoveryResult>

    suspend fun extractStream(
        extensions: List<ManagedExtension>,
        request: ExtractionRequest
    ): Result<ExtractionResult>

    suspend fun extractStream(
        request: ExtractionRequest
    ): Result<ExtractionResult>
}
