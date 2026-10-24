package com.example.extension.managed.contract

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.DetailsRequest
import com.example.extension.managed.model.EpisodeItem
import com.example.extension.managed.model.EpisodeList
import com.example.extension.managed.model.EpisodesRequest
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ExtractionResult
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.MediaDetailsResult
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.SearchResult
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.model.ServerDiscoveryResult
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.context.ExtensionContext
import com.example.extension.managed.web.WebExtractionEngine

/**
 * Base contract for trusted, bundled site scrapers in CineStream.
 * Each implementation lives statically within the APK and owns its implementationVersion.
 * A scraper acts strictly as a "Site Adapter" that translates target website structure
 * into CineStream canonical models.
 */
interface BaseSiteScraper {
    val scraperKey: String
    val implementationVersion: Int
    val supportedCapabilities: Set<ScraperCapability>
    val supportedContentTypes: Set<ContentType>

    // --- Core Scraper Operations (ManagedExtension Signatures) ---

    suspend fun search(
        extension: ManagedExtension,
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
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ServerDiscoveryRequest,
        webEngine: WebExtractionEngine? = null
    ): Result<ServerDiscoveryResult>

    suspend fun extractStream(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ExtractionRequest,
        webEngine: WebExtractionEngine? = null
    ): Result<ExtractionResult>

    // --- Modern Ergonomic Context-Based Overloads ---

    suspend fun search(
        context: ExtensionContext,
        request: SearchRequest
    ): Result<SearchResult> = search(
        extension = context.toManagedExtension(),
        request = request
    )

    suspend fun getDetails(
        context: ExtensionContext,
        url: String
    ): Result<MediaDetailsResult> = getDetails(
        extension = context.toManagedExtension(),
        url = url
    )

    suspend fun getDetails(
        context: ExtensionContext,
        request: DetailsRequest
    ): Result<MediaDetailsResult> = getDetails(context, request.url)

    suspend fun getEpisodes(
        context: ExtensionContext,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>> = getEpisodes(
        extension = context.toManagedExtension(),
        seriesUrl = seriesUrl,
        season = season
    )

    suspend fun getEpisodes(
        context: ExtensionContext,
        request: EpisodesRequest
    ): Result<EpisodeList> = getEpisodes(
        context = context,
        seriesUrl = request.seriesUrl,
        season = request.season
    ).map {
        EpisodeList(episodes = it, seasonNumber = request.season)
    }

    suspend fun discoverServers(
        context: ExtensionContext,
        request: ServerDiscoveryRequest
    ): Result<ServerDiscoveryResult> = discoverServers(
        extension = context.toManagedExtension(),
        session = context.session,
        request = request,
        webEngine = context.webEngine
    )

    suspend fun extractStream(
        context: ExtensionContext,
        request: ExtractionRequest
    ): Result<ExtractionResult> = extractStream(
        extension = context.toManagedExtension(),
        session = context.session,
        request = request,
        webEngine = context.webEngine
    )
}
