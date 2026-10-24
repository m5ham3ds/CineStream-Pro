package com.example.extension.managed.scraper

import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.CredentialProjector
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Reference trusted scraper implementation for EgyDead (Phase 7).
 * Bundled statically inside the application, independently testable, and strictly decoupled
 * from Android UI, media player, download service, and cloud backends.
 */
class EgyDeadScraper : BaseSiteScraper {

    override val scraperKey: String = "egydead"
    override val implementationVersion: Int = 1

    override val supportedCapabilities: Set<ScraperCapability> = setOf(
        ScraperCapability.SEARCH,
        ScraperCapability.DETAILS,
        ScraperCapability.EPISODES,
        ScraperCapability.SERVER_DISCOVERY,
        ScraperCapability.VIDEO_EXTRACTION
    )

    override val supportedContentTypes: Set<ContentType> = setOf(
        ContentType.MOVIE,
        ContentType.SERIES,
        ContentType.ANIME
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

    private fun resolveUrl(baseUrl: String, pathOrUrl: String): String {
        val clean = pathOrUrl.trim()
        if (clean.isBlank()) return ""
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return clean
        }
        if (clean.startsWith("//")) {
            return "https:$clean"
        }
        val base = baseUrl.trimEnd('/')
        return if (clean.startsWith("/")) "$base$clean" else "$base/$clean"
    }

    override suspend fun search(
        extension: ManagedExtension,
        request: SearchRequest
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        if (request.query.isBlank()) {
            return@withContext Result.success(SearchResult(items = emptyList(), page = request.page))
        }

        val baseUrl = extension.baseUrl.trimEnd('/')
        val encodedQuery = URLEncoder.encode(request.query.trim(), "UTF-8")
        val searchUrl = if (request.page <= 1) {
            "$baseUrl/?s=$encodedQuery"
        } else {
            "$baseUrl/page/${request.page}/?s=$encodedQuery"
        }

        try {
            val doc = Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$baseUrl/")
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on EgyDead search"))
            }

            val items = parseSearchDocument(doc, baseUrl)

            val currentVal = doc.selectFirst("div.pagination-two span.current, .pagination span.current")?.text()?.toIntOrNull() ?: request.page
            val hasHigherPageInPaginationTwo = doc.select("div.pagination-two a.inactive, .pagination a.inactive, .pagination a.page-numbers").any {
                val p = it.text().toIntOrNull()
                p != null && p > currentVal
            }
            val hasLegacyNext = doc.select(".pagination a.next, a[rel=next], a.next-page, div.pagination-two a.next").isNotEmpty()
            val hasNext = hasHigherPageInPaginationTwo || hasLegacyNext

            Result.success(SearchResult(items = items, page = request.page, hasNextPage = hasNext))
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on EgyDead search: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("EgyDead search failed: ${e.message}", e))
            }
        }
    }

    internal fun parseSearchDocument(doc: org.jsoup.nodes.Document, baseUrl: String): List<SearchMediaItem> {
        val elements = doc.select(
            "div.search-page div.catHolder ul.posts-list li.movieItem, " +
            "div.catHolder ul.posts-list li.movieItem, " +
            "ul.posts-list li.movieItem, " +
            "section.main-section ul.posts-list li.movieItem, " +
            "div.pin-posts-list ul li.movieItem, " +
            "li.movieItem, " +
            ".movieItem"
        )
        val items = mutableListOf<SearchMediaItem>()

        for (el in elements) {
            val linkEl = el.selectFirst("a[href]") ?: el.selectFirst("a") ?: continue
            val rawHref = linkEl.attr("href").trim()
            if (rawHref.isBlank()) continue
            val href = resolveUrl(baseUrl, rawHref)

            val title = linkEl.attr("title").trim()
                .ifBlank { el.selectFirst("h1.BottomTitle, .BottomTitle, h2, .title, h3")?.text()?.trim() }
                ?: linkEl.text().trim()

            if (title.isBlank()) continue

            val poster = el.selectFirst("img")?.let { img ->
                val candidates = listOf(
                    img.attr("data-src"),
                    img.attr("data-lazy-src"),
                    img.attr("data-original"),
                    img.attr("src")
                )
                candidates.firstOrNull { src ->
                    val s = src.trim()
                    s.isNotBlank() && !s.startsWith("data:image", ignoreCase = true) && !s.contains("placeholder", ignoreCase = true)
                }?.let { resolveUrl(baseUrl, it) }
            }

            val category = el.selectFirst("span.cat_name, .cat_name, .category, .cat")?.text()?.trim() ?: ""
            val yearBadge = el.selectFirst(".year, .movie-date, span.date")?.text()?.trim()
                ?: Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.value

            val isDubbed = title.contains("مدبلج") || title.contains("مدبلجة") || title.contains("دبلجة") ||
                    title.contains("dubbed", ignoreCase = true) || href.contains("dubbed", ignoreCase = true)

            val isAnime = title.contains("انمي") || title.contains("أنمي") || category.contains("انمي") || category.contains("أنمي") || href.contains("/anime/")
            val isSeries = href.contains("/series/") || href.contains("/serie/") || href.contains("/season/") || href.contains("/episode/") ||
                    title.contains("مسلسل") || category.contains("مسلسل") || category.contains("مسلسلات")

            val detectedType = when {
                isAnime -> ContentType.ANIME
                isSeries -> ContentType.SERIES
                else -> ContentType.MOVIE
            }

            val extraMetadata = mutableMapOf<String, String>()
            if (category.isNotBlank()) extraMetadata["category"] = category
            if (isDubbed) extraMetadata["audio"] = "dubbed"

            items.add(
                SearchMediaItem(
                    id = href,
                    title = title.ifBlank { "بدون عنوان" },
                    posterUrl = poster,
                    url = href,
                    contentType = detectedType,
                    year = yearBadge,
                    extraMetadata = extraMetadata
                )
            )
        }
        return items.distinctBy { it.url }
    }

    override suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext Result.failure(ExtensionError.InvalidConfiguration("Target URL cannot be blank"))
        }

        try {
            val doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer(extension.baseUrl)
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on EgyDead details"))
            }

            val details = parseDetailsDocument(doc, url, extension.baseUrl)
            Result.success(details)
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on EgyDead details: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("EgyDead getDetails failed: ${e.message}", e))
            }
        }
    }

    internal fun parseDetailsDocument(doc: org.jsoup.nodes.Document, targetUrl: String, baseUrl: String): MediaDetailsResult {
        val title = doc.selectFirst("h1.TitleMaster span em, .TitleMaster span em, .TitleMaster, h1.TitleMaster, h1.entry-title, h1, .post-title, .single-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "بدون عنوان"
        val desc = doc.selectFirst(".story, .desc, .post-content, div.story p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.trim()

        val poster = doc.selectFirst(".singleCover img, .singleCover a img, .poster img, .movie-image img")?.let { img ->
            val candidates = listOf(
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
                img.attr("src")
            )
            candidates.firstOrNull { src ->
                val s = src.trim()
                s.isNotBlank() && !s.startsWith("data:image", ignoreCase = true) && !s.contains("placeholder", ignoreCase = true)
            }?.let { resolveUrl(baseUrl, it) }
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { resolveUrl(baseUrl, it) }

        val year = doc.selectFirst("ul.singleInfo li a[href*=/year/]")?.text()?.trim()
            ?: doc.selectFirst("ul.singleInfo li:has(span:contains(سنة)) a")?.text()?.trim()
            ?: Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.value

        val rating = doc.selectFirst(".rating, .imdb-rating, .rate, span.imdb-rate")?.text()?.trim()
            ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

        val hasSeasonElements = doc.select("div.seasons-list ul li.movieItem a, div.seasons-list a").isNotEmpty()
        val hasEpisodeElements = doc.select("div.EpsList li a, div.episodes-list li a, ul.episodes-list li a").isNotEmpty()
        val isSeries = targetUrl.contains("/series/") || targetUrl.contains("/serie/") || targetUrl.contains("/season/") || targetUrl.contains("/episode/") ||
                title.contains("مسلسل") || hasSeasonElements || hasEpisodeElements
        val isAnime = title.contains("انمي") || title.contains("أنمي") || targetUrl.contains("/anime/")
        val contentType = when {
            isAnime -> ContentType.ANIME
            isSeries -> ContentType.SERIES
            else -> ContentType.MOVIE
        }

        val seasons = mutableListOf<Int>()
        val seasonElements = doc.select("div.seasons-list ul li.movieItem a, div.seasons-list a")
        if (seasonElements.isNotEmpty()) {
            seasonElements.indices.forEach { seasons.add(it + 1) }
        } else {
            seasons.add(1)
        }

        val episodes = parseEpisodesDocument(doc, baseUrl, seasonNumber = 1)

        return MediaDetailsResult(
            id = targetUrl,
            title = title,
            description = desc,
            posterUrl = poster,
            bannerUrl = null,
            contentType = contentType,
            episodes = episodes,
            seasons = seasons,
            url = targetUrl,
            year = year,
            rating = rating
        )
    }

    override suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        try {
            val doc = Jsoup.connect(seriesUrl)
                .userAgent(userAgent)
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer(baseUrl)
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on EgyDead episodes"))
            }

            val seasonItems = doc.select("div.seasons-list ul li.movieItem a, div.seasons-list a")
            val targetUrl = if (seasonItems.size >= season && season > 0) {
                resolveUrl(baseUrl, seasonItems[season - 1].attr("href").trim())
            } else {
                seriesUrl
            }

            val episodesDoc = if (targetUrl != seriesUrl) {
                Jsoup.connect(targetUrl)
                    .userAgent(userAgent)
                    .referrer(seriesUrl)
                    .timeout(10000)
                    .get()
            } else {
                doc
            }

            if (isCloudflareDocument(episodesDoc)) {
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on EgyDead episodes"))
            }

            val result = parseEpisodesDocument(episodesDoc, baseUrl, seasonNumber = season)
            Result.success(result)
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on EgyDead episodes: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("EgyDead getEpisodes failed: ${e.message}", e))
            }
        }
    }

    internal fun parseEpisodesDocument(doc: org.jsoup.nodes.Document, baseUrl: String, seasonNumber: Int): List<EpisodeItem> {
        val episodeLinks = doc.select("div.EpsList li a, div.episodes-list li a, ul.episodes-list li a")
        val result = mutableListOf<EpisodeItem>()

        for ((index, epEl) in episodeLinks.withIndex()) {
            val rawHref = epEl.attr("href").trim()
            if (rawHref.isBlank()) continue
            val href = resolveUrl(baseUrl, rawHref)
            val epText = epEl.text().trim()
            val epTitleAttr = epEl.attr("title").trim()
            val epNum = Regex("""حلقه\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""الحلقة\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""s\d+e(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: epText.replace("\\D+".toRegex(), "").toIntOrNull()
                ?: (index + 1)
            result.add(
                EpisodeItem(
                    id = href,
                    title = epTitleAttr.ifBlank { epText }.ifBlank { "الحلقة $epNum" },
                    episodeNumber = epNum,
                    seasonNumber = seasonNumber,
                    url = href
                )
            )
        }
        return result.distinctBy { it.url }
    }

    override suspend fun discoverServers(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ServerDiscoveryRequest,
        webEngine: WebExtractionEngine?
    ): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        try {
            // 1. First attempt: Direct HTML parsing.
            // EgyDead requires HTTP POST with body `View=1` to return server lists.
            val doc = try {
                Jsoup.connect(request.targetUrl)
                    .userAgent(userAgent)
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .referrer(request.targetUrl)
                    .data("View", "1")
                    .method(org.jsoup.Connection.Method.POST)
                    .timeout(10000)
                    .post()
            } catch (e: Exception) {
                // Fallback to GET for static/fixture/cached endpoints
                Jsoup.connect(request.targetUrl)
                    .userAgent(userAgent)
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .referrer(extension.baseUrl)
                    .timeout(10000)
                    .get()
            }

            if (isCloudflareDocument(doc)) {
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on EgyDead servers"))
            }

            val discovered = mutableListOf<ServerItem>()

            // 1A. Watch Servers: Desktop (.serversList li) and Mobile (.mob-servers ul li)
            val watchServerElements = doc.select(
                "ul.serversList li[data-link], " +
                "div.mob-servers ul li[data-link], " +
                ".mob-servers ul li, " +
                ".servers ul li, " +
                "ul.watch-servers li, " +
                "#servers-list li, " +
                ".servers-items li, " +
                ".servers-container li, " +
                ".play-servers li"
            )
            val rawElements = if (watchServerElements.isNotEmpty()) watchServerElements else doc.select("a[data-link], a[data-src]")

            for ((index, el) in rawElements.withIndex()) {
                val a = if (el.tagName() == "a") el else el.selectFirst("a")
                val link = el.attr("data-link")
                    .ifBlank { el.attr("data-src") }
                    .ifBlank { a?.attr("data-link") ?: "" }
                    .ifBlank { a?.attr("data-src") ?: "" }
                    .ifBlank { el.attr("href") }
                    .ifBlank { a?.attr("href") ?: "" }

                val name = el.selectFirst("span p, p, .ser-name, span")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: a?.text()?.trim()?.ifBlank { null }
                    ?: el.text().trim().ifBlank { null }
                    ?: "سيرفر ${index + 1}"

                if (link.isNotBlank() && (link.startsWith("https://") || link.startsWith("http://"))) {
                    val isDirect = link.contains(".m3u8") || link.contains(".mp4") || link.contains(".mkv")
                    discovered.add(
                        ServerItem(
                            id = (index + 1).toString(),
                            name = name,
                            link = link,
                            isDirectStream = isDirect,
                            serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                            requiresWebView = !isDirect
                        )
                    )
                }
            }

            // 1B. Download Servers: Template typo `ul.donwload-servers-list`
            val downloadServerElements = doc.select("ul.donwload-servers-list li, ul.download-servers-list li")
            for ((index, el) in downloadServerElements.withIndex()) {
                val a = el.selectFirst("a.ser-link, a")
                val link = a?.attr("href")?.trim().orEmpty()
                val serName = el.selectFirst("span.ser-name, .ser-name")?.text()?.trim() ?: "تحميل"
                val quality = el.selectFirst("div.server-info em, .server-info")?.text()?.trim() ?: ""
                val nameWithTag = if (quality.isNotBlank()) "$serName ($quality) (تحميل)" else "$serName (تحميل)"

                if (link.isNotBlank() && (link.startsWith("https://") || link.startsWith("http://"))) {
                    val isDirect = link.contains(".mp4") || link.contains(".mkv") || link.contains(".m3u8")
                    discovered.add(
                        ServerItem(
                            id = "dl-${index + 1}",
                            name = nameWithTag,
                            link = link,
                            isDirectStream = isDirect,
                            serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                            requiresWebView = !isDirect
                        )
                    )
                }
            }

            if (discovered.isNotEmpty()) {
                return@withContext Result.success(ServerDiscoveryResult(discovered, request.targetUrl))
            }

            // Fallback: Check if iframe exists in HTML (Desktop or Mobile)
            val iframe = doc.selectFirst(".watchAreaMaster .holder iframe, .mobIframe iframe, .player--iframe iframe, #iframe-container iframe, iframe")
            val iframeSrc = iframe?.attr("src")
            if (!iframeSrc.isNullOrBlank() && !iframeSrc.contains("cloudflare")) {
                val isDirect = iframeSrc.contains(".m3u8") || iframeSrc.contains(".mp4")
                discovered.add(
                    ServerItem(
                        id = "1",
                        name = "السيرفر الرئيسي",
                        link = iframeSrc,
                        isDirectStream = isDirect,
                        serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                        requiresWebView = !isDirect
                    )
                )
                return@withContext Result.success(ServerDiscoveryResult(discovered, request.targetUrl))
            }

            // 2. Second attempt: Headless Controlled WebView if webEngine is provided
            if (webEngine != null) {
                val script = getBundledEgyDeadScript()
                val webResult = webEngine.extractServers(
                    targetUrl = request.targetUrl,
                    script = script,
                    timeoutMs = session.timeoutMs,
                    expectedOrigin = extension.baseUrl
                )
                if (webResult.isSuccess) {
                    val servers = webResult.getOrThrow()
                    if (servers.isNotEmpty()) {
                        return@withContext Result.success(ServerDiscoveryResult(servers, request.targetUrl))
                    }
                }
            }

            Result.failure(ExtensionError.MediaNotFound(request.targetUrl))
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on EgyDead servers: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("EgyDead discoverServers failed: ${e.message}", e))
            }
        }
    }

    override suspend fun extractStream(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ExtractionRequest,
        webEngine: WebExtractionEngine?
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        val link = request.serverItem.link

        // Direct stream links
        if (link.contains(".m3u8") || link.contains(".mp4") || link.contains(".mkv")) {
            val sessionHeaders = mapOf(
                "Referer" to extension.baseUrl,
                "User-Agent" to userAgent
            )
            val projected = CredentialProjector.projectHeaders(
                sessionHeaders = sessionHeaders,
                sessionCookies = session.sessionCookies,
                requiredHeaderKeys = setOf("referer", "user-agent")
            )

            val isHls = link.contains(".m3u8") || link.contains("akamaized.net")
            val protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE

            val variants = if (isHls) {
                try {
                    val parsed = com.example.utils.M3U8Parser.getQualities(link, projected)
                    if (parsed.isNotEmpty()) {
                        MediaVariantParser.fromQualityInfoList(parsed, StreamProtocol.HLS, projected)
                    } else {
                        listOf(MediaVariant(url = link, protocol = StreamProtocol.HLS, headers = projected))
                    }
                } catch (_: Exception) {
                    listOf(MediaVariant(url = link, protocol = StreamProtocol.HLS, headers = projected))
                }
            } else {
                emptyList()
            }

            val playbackSource = PlaybackSource(
                streamUrl = link,
                headers = projected,
                mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                protocol = protocol,
                variants = variants
            )

            val downloadSource = DownloadSource(
                url = link,
                mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                protocol = protocol,
                headers = projected
            )

            return@withContext Result.success(
                ExtractionResult(
                    playbackSource = playbackSource,
                    downloadSource = downloadSource,
                    variants = variants
                )
            )
        }

        // 2. Static / unpacked HTTP extraction via StaticMediaExtractor
        try {
            var extractedMediaUrl = com.example.extension.managed.web.StaticMediaExtractor.extract(link, extension.baseUrl, userAgent)
            if (extractedMediaUrl.isNullOrBlank()) {
                val embedDoc = Jsoup.connect(link)
                    .userAgent(userAgent)
                    .referrer(extension.baseUrl)
                    .timeout(8000)
                    .get()
                val embedHtml = embedDoc.html()
                val mediaMatch = Regex("https?://[^\"'\\s<>\n\r\t]+?\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE).find(embedHtml)
                extractedMediaUrl = mediaMatch?.value?.trim()
            }
            if (!extractedMediaUrl.isNullOrBlank() && !extractedMediaUrl.contains("googleads") && !extractedMediaUrl.contains("facebook")) {
                val isHls = extractedMediaUrl.contains(".m3u8") || extractedMediaUrl.contains("akamaized.net")
                val protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
                val sessionHeaders = mapOf("Referer" to link, "User-Agent" to userAgent)
                val projected = CredentialProjector.projectHeaders(sessionHeaders, session.sessionCookies, setOf("referer", "user-agent"))
                val variants = if (isHls) {
                    try {
                        val parsed = com.example.utils.M3U8Parser.getQualities(extractedMediaUrl, projected)
                        if (parsed.isNotEmpty()) {
                            MediaVariantParser.fromQualityInfoList(parsed, StreamProtocol.HLS, projected)
                        } else {
                            listOf(MediaVariant(url = extractedMediaUrl, protocol = StreamProtocol.HLS, headers = projected))
                        }
                    } catch (_: Exception) {
                        listOf(MediaVariant(url = extractedMediaUrl, protocol = StreamProtocol.HLS, headers = projected))
                    }
                } else {
                    emptyList()
                }
                val playbackSource = PlaybackSource(
                    streamUrl = extractedMediaUrl,
                    headers = projected,
                    mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                    protocol = protocol,
                    variants = variants
                )
                val downloadSource = DownloadSource(
                    url = extractedMediaUrl,
                    mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                    protocol = protocol,
                    headers = projected
                )
                return@withContext Result.success(ExtractionResult(playbackSource = playbackSource, downloadSource = downloadSource, variants = variants))
            }
        } catch (_: Exception) {}

        // 3. Web extraction if link is an embed/page and webEngine is provided
        if (webEngine != null) {
            val script = com.example.extension.managed.web.ControlledWebViewEngine.getPublicEmbedMediaExtractionScript()
            val streamResult = webEngine.extractStreamUrl(
                targetUrl = link,
                targetServerId = request.serverItem.id,
                script = script,
                timeoutMs = request.timeoutMs,
                expectedOrigin = extension.baseUrl
            )

            if (streamResult.isSuccess) {
                val resolvedStream = streamResult.getOrThrow()
                val sessionHeaders = mapOf(
                    "Referer" to link,
                    "User-Agent" to userAgent
                )
                val projected = CredentialProjector.projectHeaders(
                    sessionHeaders = sessionHeaders,
                    sessionCookies = session.sessionCookies,
                    requiredHeaderKeys = setOf("referer", "user-agent")
                )

                val isHls = resolvedStream.contains(".m3u8") || resolvedStream.contains("akamaized.net")
                val protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE

                val variants = if (isHls) {
                    try {
                        val parsed = com.example.utils.M3U8Parser.getQualities(resolvedStream, projected)
                        if (parsed.isNotEmpty()) {
                            MediaVariantParser.fromQualityInfoList(parsed, StreamProtocol.HLS, projected)
                        } else {
                            listOf(MediaVariant(url = resolvedStream, protocol = StreamProtocol.HLS, headers = projected))
                        }
                    } catch (_: Exception) {
                        listOf(MediaVariant(url = resolvedStream, protocol = StreamProtocol.HLS, headers = projected))
                    }
                } else {
                    emptyList()
                }

                val playbackSource = PlaybackSource(
                    streamUrl = resolvedStream,
                    headers = projected,
                    mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                    protocol = protocol,
                    variants = variants
                )

                val downloadSource = DownloadSource(
                    url = resolvedStream,
                    mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                    protocol = protocol,
                    headers = projected
                )

                return@withContext Result.success(
                    ExtractionResult(
                        playbackSource = playbackSource,
                        downloadSource = downloadSource,
                        variants = variants
                    )
                )
            }
        }

        Result.failure(ExtensionError.ExtractionFailed("Could not extract stream for server: ${request.serverItem.name}"))
    }

    /**
     * Bundled trusted script for EgyDead.
     * Hardcoded inside the app, never downloaded remotely from Firebase.
     */
    private fun getBundledEgyDeadScript(): String {
        return """
            (function() {
                var serverItems = [];
                
                // Watch servers (Desktop: ul.serversList li, Mobile: div.mob-servers ul li)
                var serverLinks = document.querySelectorAll('ul.serversList li, div.mob-servers ul li, .mob-servers ul li, .servers ul li, ul.watch-servers li, #servers-list li, .servers-items li, .servers-container li, .play-servers li, a[data-link], a[data-src]');
                if (serverLinks && serverLinks.length > 0) {
                    serverLinks.forEach(function(el, index) {
                        var nameEl = el.querySelector('span p') || el.querySelector('p') || el.querySelector('.ser-name') || el.querySelector('span') || el.querySelector('a') || el;
                        var name = nameEl ? nameEl.textContent.trim() : ('سيرفر ' + (index + 1));
                        name = name.replace(/\s+/g, ' ').trim();
                        var link = el.getAttribute('data-link') || el.getAttribute('data-src') || el.getAttribute('data-server') || el.getAttribute('href');
                        if (link && (link.startsWith('https://') || link.startsWith('http://'))) {
                            serverItems.push({ name: name, link: link, id: (index + 1).toString() });
                        }
                    });
                }
                
                // Download servers (literal donwload typo from live template)
                var dlLinks = document.querySelectorAll('ul.donwload-servers-list li, ul.download-servers-list li');
                if (dlLinks && dlLinks.length > 0) {
                    dlLinks.forEach(function(el, index) {
                        var serName = el.querySelector('.ser-name') ? el.querySelector('.ser-name').textContent.trim() : 'تحميل';
                        var qEl = el.querySelector('.server-info em') || el.querySelector('.server-info');
                        var quality = qEl ? qEl.textContent.trim() : '';
                        var a = el.querySelector('a.ser-link') || el.querySelector('a');
                        var link = a ? a.getAttribute('href') : null;
                        if (link && (link.startsWith('https://') || link.startsWith('http://'))) {
                            var dName = quality ? (serName + ' (' + quality + ') (تحميل)') : (serName + ' (تحميل)');
                            serverItems.push({ name: dName, link: link, id: 'dl-' + (index + 1) });
                        }
                    });
                }

                if (serverItems.length === 0) {
                    var iframe = document.querySelector('.watchAreaMaster .holder iframe, .mobIframe iframe, .player--iframe iframe, #iframe-container iframe, iframe');
                    if (iframe && iframe.src && (iframe.src.startsWith('https://') || iframe.src.startsWith('http://')) && !iframe.src.includes('cloudflare')) {
                        serverItems.push({ name: 'السيرفر الرئيسي', link: iframe.src, id: '1' });
                    }
                }
                
                var title = document.title.toLowerCase();
                var isCf = title.includes('just a moment') || title.includes('cloudflare') || title.includes('attention required');
                
                if (typeof SafeScraperBridge !== 'undefined') {
                    if (isCf) {
                        SafeScraperBridge.postMessage(JSON.stringify({ type: 'CHALLENGE_STATE', status: 'DETECTED' }));
                    } else if (serverItems.length > 0) {
                        SafeScraperBridge.postMessage(JSON.stringify({ type: 'SERVER_LIST', servers: serverItems }));
                    }
                }
            })();
        """.trimIndent()
    }
}
