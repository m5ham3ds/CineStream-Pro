package com.example.extension.managed.scraper

import android.util.Log
import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.CredentialProjector
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.web.ControlledWebViewEngine
import com.example.extension.managed.web.MediaStreamDetector
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Bundled Site Scraper implementation for AnimeBlkom (https://animeblkom.net).
 * Follows the forensic specifications of PHASE NEW-BLK-01:
 * - Search endpoint: /search?query=<encodedQuery>
 * - Details: /anime/{slug}
 * - Episode list: .episodes-links -> li.episode-link a[href] -> /watch/{slug}/{episode}
 * - Watch page & Server Discovery: dynamic server extraction from [data-src], iframes, and container links
 * - Decoupled: Zero reflection, zero dynamic loading, zero Firebase/Firestore, zero UI/Player/Downloader dependencies.
 */
class AnimeBlkomScraper : BaseSiteScraper {

    override val scraperKey: String = "animeblkom"
    override val implementationVersion: Int = 1

    override val supportedCapabilities: Set<ScraperCapability> = setOf(
        ScraperCapability.SEARCH,
        ScraperCapability.DETAILS,
        ScraperCapability.EPISODES,
        ScraperCapability.SERVER_DISCOVERY,
        ScraperCapability.VIDEO_EXTRACTION
    )

    override val supportedContentTypes: Set<ContentType> = setOf(
        ContentType.ANIME,
        ContentType.MOVIE,
        ContentType.SERIES
    )

    private val userAgent = MediaStreamDetector.STANDARD_USER_AGENT

    private fun logDiag(msg: String) {
        try {
            Log.i("ANIMEBLKOM_DIAG", msg)
        } catch (_: Throwable) {
            println("DIAG: [ANIMEBLKOM] $msg")
        }
    }

    private fun isCloudflareDocument(doc: org.jsoup.nodes.Document): Boolean {
        val title = doc.title().lowercase()
        return title.contains("just a moment") ||
                title.contains("cloudflare") ||
                title.contains("attention required") ||
                doc.select(".cf-browser-verification, #cf-wrapper, #challenge-form, #turnstile-wrapper").isNotEmpty()
    }

    private fun isCloudflareException(e: Throwable): Boolean {
        if (e is org.jsoup.HttpStatusException && (e.statusCode == 403 || e.statusCode == 503)) {
            return true
        }
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("403") || msg.contains("503") || msg.contains("cloudflare") || msg.contains("just a moment")
    }

    internal fun resolveUrl(baseUrl: String, pathOrUrl: String): String {
        val clean = pathOrUrl.trim()
        if (clean.isBlank()) return ""
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return if (clean.startsWith("http://")) clean.replaceFirst("http://", "https://") else clean
        }
        if (clean.startsWith("//")) {
            return "https:$clean"
        }
        val base = baseUrl.trimEnd('/')
        return if (clean.startsWith("/")) "$base$clean" else "$base/$clean"
    }

    internal fun safeBase64Decode(str: String): String? {
        val clean = str.trim()
        if (clean.isBlank()) return null
        return try {
            val bytes = java.util.Base64.getDecoder().decode(clean)
            String(bytes, Charsets.UTF_8).trim()
        } catch (_: Throwable) {
            try {
                val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                String(bytes, Charsets.UTF_8).trim()
            } catch (_: Throwable) {
                null
            }
        }
    }

    internal fun resolveWatchUrl(rawUrl: String, baseUrl: String): String? {
        val clean = rawUrl.trim()
        if (clean.isBlank()) return null

        // Security check: reject insecure or dangerous schemes
        val lower = clean.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("data:")) {
            return null
        }

        // 1. Direct or protocol-relative URL
        if (clean.startsWith("https://") || clean.startsWith("http://") || clean.startsWith("//")) {
            return resolveUrl(baseUrl, clean)
        }

        // 2. Relative path starting with /
        if (clean.startsWith("/")) {
            return resolveUrl(baseUrl, clean)
        }

        // 3. Potential Base64 encoded payload
        val decoded = safeBase64Decode(clean)
        if (decoded != null && (decoded.startsWith("http://") || decoded.startsWith("https://") || decoded.startsWith("//") || decoded.startsWith("/"))) {
            return resolveWatchUrl(decoded, baseUrl)
        }

        // 4. Domain-like path (e.g. embed.server.com/...)
        if (clean.contains(".") && !clean.contains(" ") && clean.contains("/")) {
            return "https://$clean"
        }

        return null
    }

    // --- Search ---

    override suspend fun search(
        extension: ManagedExtension,
        request: SearchRequest
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        if (request.query.isBlank()) {
            return@withContext Result.success(SearchResult(items = emptyList(), page = request.page))
        }

        val baseUrl = extension.baseUrl.trimEnd('/')
        val encodedQuery = URLEncoder.encode(request.query.trim(), "UTF-8")
        val searchUrl = if (request.page > 1) {
            "$baseUrl/search?query=$encodedQuery&page=${request.page}"
        } else {
            "$baseUrl/search?query=$encodedQuery"
        }

        try {
            val doc = Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$baseUrl/")
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                logDiag("CLOUDFLARE_DETECTED on search: $searchUrl")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on AnimeBlkom search"))
            }

            val items = parseSearchDocument(doc, baseUrl)
            val hasNextPage = doc.select(".pagination .next, a[rel=next], .pagination li.active + li a").isNotEmpty()

            Result.success(
                SearchResult(
                    items = items,
                    page = request.page,
                    hasNextPage = hasNextPage
                )
            )
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on search exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on AnimeBlkom search: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("AnimeBlkom search failed: ${e.message}", e))
            }
        }
    }

    internal fun parseSearchDocument(doc: org.jsoup.nodes.Document, baseUrl: String): List<SearchMediaItem> {
        val containerElements = doc.select(
            ".anime-card, " +
            ".search-results .item, " +
            ".content .item, " +
            "div.anime-item, " +
            "div.poster-item, " +
            "div.item"
        )

        val cardElements = if (containerElements.isNotEmpty()) {
            containerElements
        } else {
            doc.select("a[href*='/anime/'], a[href*='/movie/']")
        }

        val items = mutableListOf<SearchMediaItem>()
        for (el in cardElements) {
            val linkEl = if (el.tagName() == "a") el else el.selectFirst("a[href*='/movie/'], a[href*='/anime/'], a") ?: continue
            val href = linkEl.attr("href").trim()
            if (href.isBlank() || (!href.contains("/anime/") && !href.contains("/movie/"))) continue

            val fullUrl = resolveUrl(baseUrl, href)
            if (!fullUrl.startsWith("https://") && !fullUrl.startsWith("http://")) continue

            val rawTitle = el.selectFirst(".anime-title, .title, .name, .anime-name, h3, h4, h2, h1")?.text()?.trim()
                ?: linkEl.attr("title").trim().ifBlank { linkEl.text().trim() }

            val title = rawTitle.substringBefore(" – ").trim()
            if (title.isBlank()) continue

            val poster = el.selectFirst(".poster img, img")?.let { img ->
                val candidates = listOf(
                    img.attr("data-src"),
                    img.attr("data-original"),
                    img.attr("data-lazy-src"),
                    img.attr("src")
                )
                candidates.firstOrNull { src ->
                    val s = src.trim()
                    s.isNotBlank() && !s.startsWith("data:image", ignoreCase = true) && !s.contains("placeholder", ignoreCase = true)
                }?.let { resolveUrl(baseUrl, it) }
            }

            val year = el.selectFirst(".year, .release-date, span.year")?.text()?.trim()
                ?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value }
                ?: Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.value

            val rating = el.selectFirst(".rating, .score, .rate")?.text()?.trim()
                ?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1) }

            val typeBadge = el.selectFirst(".type, .badge, .category, .anime-type")?.text()?.trim() ?: ""
            val isMovie = fullUrl.contains("/movie/") ||
                    title.contains("فيلم", ignoreCase = true) ||
                    title.contains("Movie", ignoreCase = true) ||
                    typeBadge.contains("فيلم", ignoreCase = true) ||
                    typeBadge.contains("Movie", ignoreCase = true)

            val contentType = if (isMovie) ContentType.MOVIE else ContentType.ANIME

            val slug = fullUrl.trimEnd('/').substringAfterLast('/')
            val id = slug.ifBlank { "blkom-${items.size + 1}" }

            val extraMetadata = mutableMapOf<String, String>()
            rating?.let { extraMetadata["rating"] = it }
            if (typeBadge.isNotBlank()) extraMetadata["type"] = typeBadge

            items.add(
                SearchMediaItem(
                    id = id,
                    title = title,
                    posterUrl = poster,
                    url = fullUrl,
                    contentType = contentType,
                    year = year,
                    extraMetadata = extraMetadata
                )
            )
        }

        return items.distinctBy { it.url }
    }

    // --- Details ---

    override suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        val targetUrl = resolveUrl(baseUrl, url)

        try {
            val doc = Jsoup.connect(targetUrl)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$baseUrl/")
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                logDiag("CLOUDFLARE_DETECTED on details: $targetUrl")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on AnimeBlkom details"))
            }

            val details = parseDetailsDocument(doc, targetUrl, baseUrl)
            Result.success(details)
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on details exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on AnimeBlkom details: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("AnimeBlkom getDetails failed: ${e.message}", e))
            }
        }
    }

    internal fun parseDetailsDocument(
        doc: org.jsoup.nodes.Document,
        targetUrl: String,
        baseUrl: String
    ): MediaDetailsResult {
        // Title
        val title = doc.selectFirst("h1.anime-title, h1.entry-title, .anime-name, h1")?.text()?.trim()
            ?: doc.title().substringBefore("-").trim()

        // Poster
        val poster = doc.selectFirst(".poster img, .anime-poster img, img.img-responsive, img.thumbnail, .content img, .single-poster img")?.let { img ->
            val candidates = listOf(
                img.attr("data-src"),
                img.attr("data-original"),
                img.attr("data-lazy-src"),
                img.attr("src")
            )
            candidates.firstOrNull { src ->
                val s = src.trim()
                s.isNotBlank() && !s.startsWith("data:image", ignoreCase = true) && !s.contains("placeholder", ignoreCase = true)
            }?.let { resolveUrl(baseUrl, it) }
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { resolveUrl(baseUrl, it) }

        // Description
        val description = doc.selectFirst(".story, .anime-story, .description, .anime-description, .overview, p.story")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.trim()

        // Rating
        val ratingText = doc.selectFirst(".rating, .score, .rate, span[itemprop=ratingValue]")?.text()?.trim()
        val rating = ratingText?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

        // Year
        val year = doc.selectFirst(".year, .release-date, span:containsOwn(سنة), span:containsOwn(تاريخ)")?.let { el ->
            Regex("""\b(19\d\d|20\d\d)\b""").find(el.text())?.value ?: el.text().trim()
        } ?: Regex("""\b(19\d\d|20\d\d)\b""").find(doc.text())?.value

        // Status
        val status = doc.selectFirst(".status, span:containsOwn(حالة), span:containsOwn(الحالة)")?.text()?.trim()

        // Genres
        val genres = doc.select(".genres a, .genre a, ul.genres li a, .categories a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Studio
        val studio = doc.selectFirst(".studio, span:containsOwn(استديو), span:containsOwn(الاستوديو)")?.text()?.trim()

        // Country
        val country = doc.selectFirst(".country, span:containsOwn(البلد), span:containsOwn(دولة)")?.text()?.trim()

        // Episode count
        val episodeCount = doc.selectFirst(".episodes-count, span:containsOwn(عدد الحلقات)")?.text()?.trim()

        val slug = targetUrl.trimEnd('/').substringAfterLast('/')
        val id = slug.ifBlank { "blkom-details" }

        val isMovie = targetUrl.contains("/movie/") || title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true)
        val contentType = if (isMovie) ContentType.MOVIE else ContentType.ANIME

        // Episodes
        val parsedEpisodes = parseEpisodesDocument(doc, baseUrl, seasonNumber = 1)
        val episodes = if (isMovie && parsedEpisodes.isEmpty()) {
            listOf(
                EpisodeItem(
                    id = id,
                    title = title,
                    episodeNumber = 1,
                    seasonNumber = 1,
                    url = targetUrl
                )
            )
        } else {
            parsedEpisodes
        }

        val metadata = mutableMapOf<String, String>()
        status?.let { metadata["status"] = it }
        studio?.let { metadata["studio"] = it }
        country?.let { metadata["country"] = it }
        episodeCount?.let { metadata["episodeCount"] = it }

        return MediaDetailsResult(
            id = id,
            title = title.ifBlank { "بدون عنوان" },
            description = description,
            posterUrl = poster,
            contentType = contentType,
            episodes = episodes,
            seasons = listOf(1),
            url = targetUrl,
            year = year,
            genres = genres,
            rating = rating,
            metadata = metadata
        )
    }

    // --- Episodes ---

    override suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        val targetUrl = resolveUrl(baseUrl, seriesUrl)

        try {
            val doc = Jsoup.connect(targetUrl)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$baseUrl/")
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                logDiag("CLOUDFLARE_DETECTED on episodes: $targetUrl")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on AnimeBlkom episodes"))
            }

            val episodes = parseEpisodesDocument(doc, baseUrl, seasonNumber = season)
            Result.success(episodes)
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on episodes exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on AnimeBlkom episodes: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("AnimeBlkom getEpisodes failed: ${e.message}", e))
            }
        }
    }

    internal fun parseEpisodesDocument(
        doc: org.jsoup.nodes.Document,
        baseUrl: String,
        seasonNumber: Int
    ): List<EpisodeItem> {
        val links = doc.select(
            ".episodes-links li.episode-link a[href], " +
            "ul.episodes-links li a[href], " +
            "li.episode-link a[href], " +
            ".episodes-links a[href], " +
            ".episodes-list a[href], " +
            "a[href*='/watch/'], " +
            "a[href*='/episode/']"
        )

        val episodes = mutableListOf<EpisodeItem>()
        for ((index, a) in links.withIndex()) {
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#" || (!href.contains("/watch/") && !href.contains("/episode/"))) continue

            val fullUrl = resolveUrl(baseUrl, href)
            if (!fullUrl.startsWith("https://") && !fullUrl.startsWith("http://")) continue

            val rawText = a.text().trim().ifBlank { a.attr("title").trim() }
            val epNumber = extractEpisodeNumber(rawText, fullUrl, index + 1)
            val title = rawText.ifBlank { "الحلقة $epNumber" }
            val id = fullUrl.trimEnd('/').substringAfterLast('/').ifBlank { "ep-$epNumber" }

            episodes.add(
                EpisodeItem(
                    id = id,
                    title = title,
                    episodeNumber = epNumber,
                    seasonNumber = seasonNumber,
                    url = fullUrl
                )
            )
        }

        return episodes.distinctBy { it.url }
    }

    internal fun extractEpisodeNumber(rawText: String, url: String, fallbackPosition: Int): Int {
        // 1. DOM text matching
        val textMatch = Regex("""(?:الحلقة|حلقة|Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(rawText)
        if (textMatch != null) {
            val num = textMatch.groupValues[1].toIntOrNull()
            if (num != null) return num
        }

        val pureNumberMatch = Regex("""^(\d+)$""").find(rawText.trim())
        if (pureNumberMatch != null) {
            val num = pureNumberMatch.groupValues[1].toIntOrNull()
            if (num != null) return num
        }

        // 2. URL slug matching (/watch/{slug}/{episode})
        val urlWatchMatch = Regex("""/watch/[^/]+/(\d+)""").find(url)
        if (urlWatchMatch != null) {
            val num = urlWatchMatch.groupValues[1].toIntOrNull()
            if (num != null) return num
        }

        val urlTrailingMatch = Regex("""(?:episode|الحلقة|ep)[-_/]?(\d+)""", RegexOption.IGNORE_CASE).find(url)
            ?: Regex("""-(\d+)/?$""").find(url)
        if (urlTrailingMatch != null) {
            val num = urlTrailingMatch.groupValues[1].toIntOrNull()
            if (num != null) return num
        }

        // 3. Fallback position
        return fallbackPosition
    }

    // --- Server Discovery ---

    override suspend fun discoverServers(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ServerDiscoveryRequest,
        webEngine: WebExtractionEngine?
    ): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        val targetUrl = request.targetUrl.trim()

        try {
            val doc = Jsoup.connect(targetUrl)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$baseUrl/")
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                logDiag("CLOUDFLARE_DETECTED on servers: $targetUrl")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on AnimeBlkom servers"))
            }

            val discovered = parseServersDocument(doc, targetUrl, baseUrl)
            if (discovered.isEmpty()) {
                logDiag("NO_SERVERS_FOUND for $targetUrl")
                return@withContext Result.failure(ExtensionError.MediaNotFound(targetUrl))
            }

            logDiag("SERVERS_DISCOVERED: Found ${discovered.size} servers for $targetUrl")
            Result.success(ServerDiscoveryResult(servers = discovered, sourcePageUrl = targetUrl))
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on servers exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on AnimeBlkom servers: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("AnimeBlkom discoverServers failed: ${e.message}", e))
            }
        }
    }

    internal fun parseServersDocument(
        doc: org.jsoup.nodes.Document,
        episodeUrl: String,
        baseUrl: String
    ): List<ServerItem> {
        val discovered = mutableListOf<ServerItem>()

        // 1. Scan for elements with data-src, data-embed, data-watch, or data-url attributes
        val dataSrcElements = doc.select(
            "ul.servers-list li[data-src], .servers-list li[data-src], li[data-src], a[data-src], button[data-src], .server-item[data-src], [data-src], " +
            "ul.servers-list li[data-embed], .servers-list li[data-embed], li[data-embed], a[data-embed], " +
            "ul.servers-list li[data-watch], .servers-list li[data-watch], li[data-watch], a[data-watch], " +
            "ul.servers-list li[data-url], .servers-list li[data-url], li[data-url], a[data-url]"
        )

        for (el in dataSrcElements) {
            val rawSrc = el.attr("data-src")
                .ifBlank { el.attr("data-embed") }
                .ifBlank { el.attr("data-watch") }
                .ifBlank { el.attr("data-url") }
                .trim()
            if (rawSrc.isBlank() || rawSrc == "#") continue

            val resolvedUrl = resolveWatchUrl(rawSrc, baseUrl) ?: continue
            val secureUrl = if (resolvedUrl.startsWith("http://")) resolvedUrl.replaceFirst("http://", "https://") else resolvedUrl

            val serverName = el.selectFirst(".server-name, span, p, a, b")?.text()?.trim()
                ?.ifBlank { null }
                ?: el.attr("data-server").ifBlank { null }
                ?: el.attr("data-name").ifBlank { null }
                ?: el.text().trim().ifBlank { null }
                ?: "سيرفر ${discovered.size + 1}"

            val qualityAttr = el.attr("data-quality").trim()
            val textToScan = "$serverName $qualityAttr ${el.text()}"
            val qualityMatch = Regex("""\b(4K|2160p|FHD|1080p|HD|720p|SD|480p|360p)\b""", RegexOption.IGNORE_CASE).find(textToScan)
            val quality = qualityAttr.ifBlank { qualityMatch?.value }

            val isDirect = secureUrl.contains(".mp4") || secureUrl.contains(".m3u8") || secureUrl.contains(".mkv")
            val serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED

            val metadata = mutableMapOf(
                "embedUrl" to secureUrl,
                "serverName" to serverName
            )
            quality?.let { metadata["quality"] = it }

            val deterministicId = "animeblkom:${discovered.size + 1}:${serverName.lowercase().replace(Regex("[^a-z0-9]"), "")}"

            discovered.add(
                ServerItem(
                    id = deterministicId,
                    name = if (!quality.isNullOrBlank() && !serverName.contains(quality, ignoreCase = true)) "$serverName ($quality)" else serverName,
                    link = secureUrl,
                    isDirectStream = isDirect,
                    sourceUrl = episodeUrl,
                    serverType = serverType,
                    requiresWebView = !isDirect,
                    metadata = metadata,
                    extraData = metadata
                )
            )
        }

        // 2. Scan for iframe elements (.embed-responsive, .video-container, or general iframe)
        val iframes = doc.select(".embed-responsive iframe, .video-container iframe, .player iframe, iframe[src]")
        for ((index, iframe) in iframes.withIndex()) {
            val src = iframe.attr("src").trim()
            if (src.isBlank() || src.contains("cloudflare")) continue

            val resolvedIframe = resolveWatchUrl(src, baseUrl) ?: continue
            val secureUrl = if (resolvedIframe.startsWith("http://")) resolvedIframe.replaceFirst("http://", "https://") else resolvedIframe

            val isDirect = secureUrl.contains(".mp4") || secureUrl.contains(".m3u8")
            val defaultName = if (discovered.isEmpty() && index == 0) "السيرفر الرئيسي" else "سيرفر مضمن ${index + 1}"

            val metadata = mapOf(
                "embedUrl" to secureUrl,
                "serverName" to defaultName
            )

            discovered.add(
                ServerItem(
                    id = "animeblkom:iframe:${index + 1}",
                    name = defaultName,
                    link = secureUrl,
                    isDirectStream = isDirect,
                    sourceUrl = episodeUrl,
                    serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                    requiresWebView = !isDirect,
                    metadata = metadata,
                    extraData = metadata
                )
            )
        }

        // Deduplicate servers by unique target link
        return discovered.distinctBy { it.link }
    }

    // --- Stream Extraction & Generic Playback Resolution ---

    override suspend fun extractStream(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ExtractionRequest,
        webEngine: WebExtractionEngine?
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        val serverItem = request.serverItem
        val embedUrl = serverItem.link.trim()

        if (embedUrl.isBlank()) {
            return@withContext Result.failure(ExtensionError.InvalidConfiguration("Target stream link cannot be blank"))
        }

        // Security check
        val lower = embedUrl.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("data:")) {
            return@withContext Result.failure(ExtensionError.SecurityViolation("Insecure or unauthorized URL scheme"))
        }

        // Fast path: direct media stream
        if (MediaStreamDetector.isMediaUrl(embedUrl)) {
            val normalized = MediaStreamDetector.normalizeStreamUrl(embedUrl)
            if (MediaStreamDetector.validateMediaUrl(normalized)) {
                val headers = mapOf(
                    "Referer" to extension.baseUrl,
                    "User-Agent" to userAgent
                )
                val projected = CredentialProjector.projectHeaders(
                    sessionHeaders = headers,
                    sessionCookies = session.sessionCookies,
                    requiredHeaderKeys = setOf("referer", "user-agent")
                )
                val isHls = normalized.contains(".m3u8")
                val playbackSource = PlaybackSource(
                    streamUrl = normalized,
                    headers = projected,
                    mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                    protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
                )
                return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
            }
        }

        // Headless Controlled WebView extraction
        if (webEngine != null) {
            val script = ControlledWebViewEngine.getPublicEmbedMediaExtractionScript()
            val streamResult = webEngine.extractStreamUrl(
                targetUrl = embedUrl,
                targetServerId = serverItem.id,
                script = script,
                timeoutMs = request.timeoutMs,
                expectedOrigin = extension.baseUrl
            )

            if (streamResult.isSuccess) {
                val detected = streamResult.getOrThrow()
                val normalized = MediaStreamDetector.normalizeStreamUrl(detected)
                if (MediaStreamDetector.validateMediaUrl(normalized)) {
                    val headers = mapOf(
                        "Referer" to embedUrl,
                        "User-Agent" to userAgent
                    )
                    val projected = CredentialProjector.projectHeaders(
                        sessionHeaders = headers,
                        sessionCookies = session.sessionCookies,
                        requiredHeaderKeys = setOf("referer", "user-agent")
                    )
                    val isHls = normalized.contains(".m3u8")
                    val playbackSource = PlaybackSource(
                        streamUrl = normalized,
                        headers = projected,
                        mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                        protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
                    )
                    return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
                }
            } else {
                val err = streamResult.exceptionOrNull()
                if (err != null && isCloudflareException(err)) {
                    return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on embed: ${err.message}"))
                }
            }
        }

        // Fallback for embed handoff when direct stream couldn't be intercepted
        val headers = mapOf("Referer" to extension.baseUrl, "User-Agent" to userAgent)
        val playbackSource = PlaybackSource(
            streamUrl = embedUrl,
            headers = headers,
            protocol = StreamProtocol.EMBED,
            mimeType = "text/html"
        )
        Result.success(ExtractionResult(playbackSource = playbackSource))
    }
}
