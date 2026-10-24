package com.example.extension.managed.scraper

import android.util.Log
import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.CredentialProjector
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.web.ControlledWebViewEngine
import com.example.extension.managed.web.StaticMediaExtractor
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Trusted bundled scraper for Qfilm (https://a.qfilm.tv).
 * Fully restored behavioral flow implementing the Legacy Qfilm browser runtime semantics
 * (search.php -> watch.php -> play.php -> window.servers -> DOMParser -> SafeScraperBridge)
 * inside modern Controlled Managed Extension Architecture.
 */
class QfilmScraper : BaseSiteScraper {

    override val scraperKey: String = "qfilm"
    override val implementationVersion: Int = 1

    override val supportedCapabilities: Set<ScraperCapability> = setOf(
        ScraperCapability.SEARCH,
        ScraperCapability.DETAILS,
        ScraperCapability.SERVER_DISCOVERY,
        ScraperCapability.VIDEO_EXTRACTION
    )

    override val supportedContentTypes: Set<ContentType> = setOf(
        ContentType.MOVIE,
        ContentType.ANIME
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun logD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            println("DEBUG: [$tag] $msg")
        }
    }

    private fun logW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            println("WARN: [$tag] $msg")
        }
    }

    private fun logDiag(msg: String) {
        try {
            Log.i("QFILM_DIAG", msg)
        } catch (_: Throwable) {
            println("DIAG: $msg")
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

    internal fun normalizeTitle(raw: String): String {
        return raw.lowercase()
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace(Regex("[^a-zA-Z0-9\u0621-\u064A\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    internal fun calculateMatchScore(query: String, targetTitle: String): Double {
        val normQuery = normalizeTitle(query)
        val normTarget = normalizeTitle(targetTitle)

        if (normQuery.isBlank() || normTarget.isBlank()) return 0.0
        if (normTarget == normQuery) return 1.0
        if (normTarget.contains(normQuery)) return 0.95

        val queryTokens = normQuery.split(" ").filter { it.isNotBlank() }.toSet()
        val targetTokens = normTarget.split(" ").filter { it.isNotBlank() }.toSet()

        if (queryTokens.isEmpty() || targetTokens.isEmpty()) return 0.0

        val intersection = queryTokens.intersect(targetTokens)
        return intersection.size.toDouble() / queryTokens.size.toDouble()
    }

    internal fun resolveBaseUrl(rawBase: String): String {
        val trimmed = rawBase.trim().trimEnd('/')
        return if (trimmed.contains("qfilm.vip", ignoreCase = true) || trimmed.isBlank()) {
            "https://a.qfilm.tv"
        } else {
            trimmed
        }
    }

    override suspend fun search(
        extension: ManagedExtension,
        request: SearchRequest
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        if (request.query.isBlank()) {
            return@withContext Result.success(SearchResult(items = emptyList(), page = request.page))
        }

        try {
            val base = resolveBaseUrl(extension.baseUrl)
            logDiag("[QFILM_DIAG] 01_EXTENSION_SELECTED id=${extension.id}, name=${extension.name}, baseUrl=$base")
            logDiag("[QFILM_DIAG] 02_SEARCH_REQUEST_STARTED query='${request.query}', page=${request.page}, contentType=${request.contentType}")
            logD("QfilmScraper", "[qfilm] search started: query='${request.query}', page=${request.page}")
            val normalizedQuery = normalizeTitle(request.query)
            val encodedQuery = URLEncoder.encode(normalizedQuery.ifBlank { request.query.trim() }, "UTF-8")
            val searchUrl = if (request.page > 1) {
                "$base/search.php?keywords=$encodedQuery&page=${request.page}"
            } else {
                "$base/search.php?keywords=$encodedQuery"
            }

            logDiag("[QFILM_DIAG] 03_SEARCH_URL url=$searchUrl")

            if (!searchUrl.startsWith("https://")) {
                return@withContext Result.failure(ExtensionError.SecurityViolation("Insecure HTTP search URL prohibited: $searchUrl"))
            }

            logDiag("[QFILM_DIAG] 04_SEARCH_HTTP_STARTED url=$searchUrl")
            val doc = Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$base/")
                .timeout(10000)
                .get()

            logDiag("[QFILM_DIAG] 05_SEARCH_HTTP_RESPONSE status=200, title='${doc.title()}', bodyLength=${doc.html().length}")

            if (isCloudflareDocument(doc)) {
                logDiag("[QFILM_DIAG] 05_CLOUDFLARE_CHALLENGE_DETECTED title='${doc.title()}'")
                logW("QfilmScraper", "[qfilm] Cloudflare detected? YES on search")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on Qfilm search"))
            } else {
                logD("QfilmScraper", "[qfilm] Cloudflare detected? NO on search")
            }

            val elements = doc.select(
                "ul.pm-ul-browse-videos li, ul#pm-grid li, .pm-ul-browse-videos li, ul.pm-ul-browse-videos li.pm-li-video"
            )
            logDiag("[QFILM_DIAG] 06_SEARCH_DOM_PARSED elementsCount=${elements.size}")
            logD("QfilmScraper", "[qfilm] search DOM ready: found ${elements.size} candidate items")

            val scoredItems = mutableListOf<Pair<SearchMediaItem, Double>>()

            for (el in elements) {
                val linkEl = el.selectFirst("a[href*='watch.php'], h3.caption a, a") ?: continue
                var href = linkEl.attr("href").trim()
                if (href.isBlank()) continue
                if (href.startsWith("/")) {
                    href = base + href
                } else if (!href.startsWith("http")) {
                    href = "$base/$href"
                }

                val title = el.selectFirst("h3.caption, .caption a, h3, a.pm-title")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: linkEl.attr("title").trim().ifBlank { null }
                    ?: linkEl.text().trim()

                val poster = el.selectFirst("img")?.let { img ->
                    img.attr("data-echo")
                        .ifBlank { img.attr("data-src") }
                        .ifBlank { img.attr("src") }
                }?.let { p ->
                    if (p.startsWith("/")) "$base$p" else if (!p.startsWith("http")) "$base/$p" else p
                }

                val score = calculateMatchScore(request.query, title)

                val isAnime = title.contains("انمي") || title.contains("أنمي") || href.contains("anime")
                val contentType = if (isAnime) ContentType.ANIME else ContentType.MOVIE

                val item = SearchMediaItem(
                    id = href,
                    title = title.ifBlank { "بدون عنوان" },
                    posterUrl = poster,
                    url = href,
                    contentType = contentType
                )
                scoredItems.add(item to score)
            }

            val sortedItems = scoredItems.sortedByDescending { it.second }.map { it.first }
            logDiag("[QFILM_DIAG] 07_SEARCH_RESULTS_COUNT count=${sortedItems.size}, topResult='${sortedItems.firstOrNull()?.title}'")
            logD("QfilmScraper", "[qfilm] search results = ${sortedItems.size}")

            val hasNext = doc.select("ul.pagination li a[href*='page='], a.next").isNotEmpty()

            Result.success(SearchResult(items = sortedItems, page = request.page, hasNextPage = hasNext))
        } catch (e: Exception) {
            logDiag("[QFILM_DIAG] 05_SEARCH_HTTP_RESPONSE FAILED: ${e.javaClass.simpleName} - ${e.message}")
            if (isCloudflareException(e)) {
                logW("QfilmScraper", "[qfilm] Cloudflare detected? YES on search exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on Qfilm search: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("Qfilm search failed: ${e.message}", e))
            }
        }
    }

    override suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext Result.failure(ExtensionError.InvalidConfiguration("Target URL cannot be blank"))
        }

        try {
            logDiag("[QFILM_DIAG] 08_SEARCH_RESULT_SELECTED url=$url")
            logDiag("[QFILM_DIAG] 09_DETAILS_STARTED url=$url")
            logD("QfilmScraper", "[qfilm] details requested for: $url")
            val base = resolveBaseUrl(extension.baseUrl)
            val doc = Jsoup.connect(url)
                .userAgent(userAgent)
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer(base)
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                logDiag("[QFILM_DIAG] 09_CLOUDFLARE_DETECTED url=$url")
                logW("QfilmScraper", "[qfilm] Cloudflare detected? YES on details")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on Qfilm details"))
            } else {
                logD("QfilmScraper", "[qfilm] Cloudflare detected? NO on details")
            }

            val title = doc.selectFirst(".pm-video-heading h1, h1.entry-title, h1")?.text()?.trim()
                ?.ifBlank { null }
                ?: doc.title().trim()

            val description = doc.selectFirst("#pm-video-description, .video-description, .entry-content, p.description")?.text()?.trim()
                ?: ""

            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?.ifBlank { null }
                ?: doc.selectFirst(".pm-video-watch-thumbnail img, img.pm-video-thumb, .video-thumb img")?.let { img ->
                    img.attr("src").ifBlank { img.attr("data-echo") }
                }

            val isAnime = title.contains("انمي") || title.contains("أنمي") || url.contains("anime")
            val contentType = if (isAnime) ContentType.ANIME else ContentType.MOVIE

            val playLinkEl = doc.selectFirst(".video-bibplayer a.xtgo, a.xtgo[href*='play.php'], a[href*='play.php'], a.xtgo")
            var playUrl = playLinkEl?.attr("href")?.trim().orEmpty()
            if (playUrl.isNotBlank()) {
                if (playUrl.startsWith("/")) playUrl = base + playUrl
                else if (!playUrl.startsWith("http")) playUrl = "$base/$playUrl"
            }

            logDiag("[QFILM_DIAG] 10_WATCH_URL playUrl=$playUrl, watchUrl=$url")
            logD("QfilmScraper", "[qfilm] details resolved: title='$title', playUrl='$playUrl'")

            Result.success(
                MediaDetailsResult(
                    id = url,
                    title = title,
                    description = description,
                    posterUrl = poster,
                    bannerUrl = poster,
                    contentType = contentType,
                    url = url
                )
            )
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logW("QfilmScraper", "[qfilm] Cloudflare detected? YES on details exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on Qfilm details: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("Qfilm getDetails failed: ${e.message}", e))
            }
        }
    }

    override suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>> {
        // Qfilm provides Movie and Anime content only (no episodic TV series)
        return Result.success(emptyList())
    }

    internal fun parseServerArrayItems(rawItems: List<String>): List<String> {
        val urls = mutableListOf<String>()
        val srcPattern = Pattern.compile("src\\s*=\\s*\\\\?[\"'](https?:[^\"'\\\\]+)\\\\?[\"']", Pattern.CASE_INSENSITIVE)

        for (item in rawItems) {
            val trimmed = item.trim()
            if (trimmed.contains("<iframe", ignoreCase = true)) {
                try {
                    val doc = Jsoup.parseBodyFragment(trimmed)
                    val iframe = doc.selectFirst("iframe")
                    val src = iframe?.attr("src")?.trim()
                    if (!src.isNullOrBlank() && (src.startsWith("http://") || src.startsWith("https://"))) {
                        urls.add(src)
                        continue
                    }
                } catch (_: Exception) {}
            }

            val matcher = srcPattern.matcher(trimmed)
            if (matcher.find()) {
                val src = matcher.group(1)?.trim()
                if (!src.isNullOrBlank() && (src.startsWith("http://") || src.startsWith("https://"))) {
                    urls.add(src)
                    continue
                }
            }

            val cleaned = trimmed.trim('\"', '\'', ' ', '\\')
            if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
                urls.add(cleaned)
            }
        }
        return urls.distinct()
    }

    internal fun extractServersFromJs(html: String): List<String> {
        val extractedUrls = mutableListOf<String>()

        val pattern = Pattern.compile(
            "(?:var\\s+|window\\.)?servers\\s*=\\s*\\[(.*?)\\]\\s*;",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            val arrayContent = matcher.group(1) ?: ""
            val unescaped = arrayContent.replace("\\/", "/").replace("\\\"", "\"")

            val srcPattern = Pattern.compile("src\\s*=\\s*[\"'](https?:[^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
            val srcMatcher = srcPattern.matcher(unescaped)
            while (srcMatcher.find()) {
                val url = srcMatcher.group(1)?.trim()
                if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    extractedUrls.add(url)
                }
            }

            val urlPattern = Pattern.compile("[\"'](https?:[^\"'\\s<>]+)[\"']", Pattern.CASE_INSENSITIVE)
            val urlMatcher = urlPattern.matcher(unescaped)
            while (urlMatcher.find()) {
                val url = urlMatcher.group(1)?.trim()
                if (!url.isNullOrBlank() && !extractedUrls.contains(url)) {
                    extractedUrls.add(url)
                }
            }
        }

        return extractedUrls.distinct()
    }

    internal fun extractServersFromDom(doc: org.jsoup.nodes.Document): List<String> {
        val urls = mutableListOf<String>()

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http://") || src.startsWith("https://")) {
                urls.add(src)
            }
        }

        doc.select("a[data-src], a[data-link], .servers a[href*='http'], ul.servers li a, div.server a").forEach { el ->
            val link = el.attr("data-src").ifBlank { el.attr("data-link") }.ifBlank { el.attr("href") }.trim()
            if (link.startsWith("http://") || link.startsWith("https://")) {
                urls.add(link)
            }
        }

        return urls.distinct()
    }

    internal fun classifyServerName(url: String, index: Int): String {
        val host = try {
            URI(url).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
        return when {
            host.contains("anafast") -> "AnaFast"
            host.contains("mp4plus") -> "Mp4Plus"
            host.contains("vidmoly") -> "VidMoly"
            host.contains("liiivideo") -> "LiiiVideo"
            host.contains("abyss") -> "AbyssPlayer"
            host.contains("vidara") -> "VidAra"
            host.contains("vidoba") -> "VidOba"
            host.contains("uqload") -> "Uqload"
            host.contains("vidbom") -> "VidBom"
            host.contains("vidshar") -> "VidShar"
            host.contains("dood") -> "DoodStream"
            host.contains("streamtape") -> "StreamTape"
            host.contains("mp4upload") -> "Mp4Upload"
            host.contains("upstream") -> "Upstream"
            host.contains("mixdrop") -> "MixDrop"
            host.contains("filelions") -> "FileLions"
            host.contains("streamwish") -> "StreamWish"
            host.contains("vidspeed") -> "VidSpeed"
            host.contains("akamaized") -> "Akamai CDN"
            host.isNotBlank() -> host.substringBefore(".")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            else -> "سيرفر $index"
        }
    }

    override suspend fun discoverServers(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ServerDiscoveryRequest,
        webEngine: WebExtractionEngine?
    ): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        logDiag("[QFILM_DIAG] 11_DISCOVER_SERVERS_STARTED targetUrl='${request.targetUrl}', webEngineAvailable=${webEngine != null}")
        logD("QfilmScraper", "[qfilm] discoverServers started: targetUrl='${request.targetUrl}'")
        val base = resolveBaseUrl(extension.baseUrl)

        // Primary execution path: ControlledWebViewEngine
        if (webEngine != null) {
            logDiag("[QFILM_DIAG] 12_WEBVIEW_STARTED targetUrl='${request.targetUrl}', timeoutMs=${session.timeoutMs}")
            logDiag("[QFILM_DIAG] 13_WEBVIEW_NAVIGATION targetUrl='${request.targetUrl}'")
            logD("QfilmScraper", "[qfilm] using ControlledWebViewEngine as PRIMARY execution path")
            val script = getBundledQfilmScript()
            val webResult = webEngine.extractServers(
                targetUrl = request.targetUrl,
                script = script,
                timeoutMs = session.timeoutMs,
                expectedOrigin = extension.baseUrl
            )

            if (webResult.isSuccess) {
                val rawServers = webResult.getOrThrow()
                if (rawServers.isNotEmpty()) {
                    logDiag("[QFILM_DIAG] 14_PLAY_URL url='${request.targetUrl}'")
                    logDiag("[QFILM_DIAG] 15_SERVERS_EXTRACTED count=${rawServers.size} (via ControlledWebViewEngine)")
                    logD("QfilmScraper", "[qfilm] server array size = ${rawServers.size} (via ControlledWebViewEngine)")
                    val normalized = rawServers.mapIndexed { index, item ->
                        val link = item.link
                        val isDirect = link.contains(".m3u8") || link.contains(".mp4") || link.contains(".mkv")
                        ServerItem(
                            id = (index + 1).toString(),
                            name = item.name.ifBlank { classifyServerName(link, index + 1) },
                            link = link,
                            isDirectStream = isDirect,
                            serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                            requiresWebView = !isDirect
                        )
                    }
                    logD("QfilmScraper", "[qfilm] SERVER_LIST emitted: ${normalized.size} servers discovered")
                    return@withContext Result.success(ServerDiscoveryResult(servers = normalized, sourcePageUrl = request.targetUrl))
                }
            } else {
                val error = webResult.exceptionOrNull()
                logDiag("[QFILM_DIAG] 15_SERVERS_EXTRACTION_FAILED: ${error?.javaClass?.simpleName} - ${error?.message}")
                if (error != null && isCloudflareException(error)) {
                    logW("QfilmScraper", "[qfilm] Cloudflare challenge encountered during ControlledWebView execution: ${error.message}")
                    return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered: ${error.message}"))
                }
                logW("QfilmScraper", "[qfilm] ControlledWebView execution did not yield servers: ${error?.message}, trying HTTP fallback")
            }
        }

        // Secondary / Offline / Headless Unit-Test Fallback: Jsoup HTTP
        try {
            var playUrl = request.targetUrl
            if (request.targetUrl.contains("watch.php")) {
                logD("QfilmScraper", "[qfilm] watch page reached: resolving play.php link via HTTP: ${request.targetUrl}")
                val watchDoc = Jsoup.connect(request.targetUrl)
                    .userAgent(userAgent)
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .referrer(base)
                    .timeout(10000)
                    .get()

                if (isCloudflareDocument(watchDoc)) {
                    logW("QfilmScraper", "[qfilm] Cloudflare detected on watch page")
                    return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on Qfilm watch page"))
                }

                val playLinkEl = watchDoc.selectFirst(".video-bibplayer a.xtgo, a.xtgo[href*='play.php'], a[href*='play.php'], a.xtgo")
                val extractedPlayHref = playLinkEl?.attr("href")?.trim().orEmpty()
                if (extractedPlayHref.isNotBlank()) {
                    playUrl = if (extractedPlayHref.startsWith("/")) base + extractedPlayHref
                    else if (!extractedPlayHref.startsWith("http")) "$base/$extractedPlayHref"
                    else extractedPlayHref
                    logD("QfilmScraper", "[qfilm] play page reached: $playUrl")
                }
            }

            logD("QfilmScraper", "[qfilm] fetching play page via HTTP: $playUrl")
            val doc = Jsoup.connect(playUrl)
                .userAgent(userAgent)
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer(base)
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                logW("QfilmScraper", "[qfilm] Cloudflare detected on play page")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on Qfilm play page"))
            }

            val html = doc.html()
            logD("QfilmScraper", "[qfilm] waiting for window.servers (in HTTP HTML)")
            val jsServers = extractServersFromJs(html)
            val domServers = extractServersFromDom(doc)
            val serverUrls = (jsServers + domServers).distinct()

            if (serverUrls.isNotEmpty()) {
                logD("QfilmScraper", "[qfilm] window.servers found (HTTP): count=${serverUrls.size}")
                val discovered = mutableListOf<ServerItem>()
                for ((index, link) in serverUrls.withIndex()) {
                    val isDirect = link.contains(".m3u8") || link.contains(".mp4") || link.contains(".mkv")
                    discovered.add(
                        ServerItem(
                            id = (index + 1).toString(),
                            name = classifyServerName(link, index + 1),
                            link = link,
                            isDirectStream = isDirect,
                            serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                            requiresWebView = !isDirect
                        )
                    )
                }
                logD("QfilmScraper", "[qfilm] server array size = ${discovered.size}")
                logD("QfilmScraper", "[qfilm] SERVER_LIST emitted: ${discovered.size} servers")
                return@withContext Result.success(ServerDiscoveryResult(discovered, playUrl))
            }

            logW("QfilmScraper", "[qfilm] servers = 0 on $playUrl")
            Result.failure(ExtensionError.MediaNotFound(playUrl))
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logW("QfilmScraper", "[qfilm] Cloudflare detected on servers exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("Qfilm discoverServers failed: ${e.message}", e))
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
        val base = resolveBaseUrl(extension.baseUrl)
        logDiag("[QFILM_DIAG] 16_SERVER_SELECTED server='${request.serverItem.name}', link=$link")
        logDiag("[QFILM_DIAG] 17_STREAM_EXTRACTION_STARTED server='${request.serverItem.name}', isDirect=${request.serverItem.isDirectStream}")
        logD("QfilmScraper", "[qfilm] extraction started: '${request.serverItem.name}', link=$link")

        // 1. Direct stream links (.m3u8, .mp4, .mkv)
        if (link.contains(".m3u8") || link.contains(".mp4") || link.contains(".mkv")) {
            logD("QfilmScraper", "[qfilm] direct stream / embed: DIRECT stream detected")
            val sessionHeaders = mapOf(
                "Referer" to base,
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

            logDiag("[QFILM_DIAG] 18_STREAM_RESOLVED streamUrl='${playbackSource.streamUrl}'")
            logDiag("[QFILM_DIAG] 19_PLAYER_HANDOFF mimeType='${playbackSource.mimeType}', protocol='${playbackSource.protocol}', variantsCount=${variants.size}")
            logD("QfilmScraper", "[qfilm] extraction completed: direct playback and download sources ready")
            return@withContext Result.success(
                ExtractionResult(
                    playbackSource = playbackSource,
                    downloadSource = downloadSource,
                    variants = variants
                )
            )
        }

        // 2. Embed page extraction via ControlledWebViewEngine
        logD("QfilmScraper", "[qfilm] direct stream / embed: EMBED host detected, using ControlledWebViewEngine")
        if (webEngine != null) {
            val script = ControlledWebViewEngine.getPublicEmbedMediaExtractionScript()
            val streamResult = webEngine.extractStreamUrl(
                targetUrl = link,
                targetServerId = request.serverItem.id,
                script = script,
                timeoutMs = request.timeoutMs,
                expectedOrigin = base
            )

            if (streamResult.isSuccess) {
                val resolvedStream = streamResult.getOrThrow()
                logD("QfilmScraper", "[qfilm] stream resolved: $resolvedStream")
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

                logDiag("[QFILM_DIAG] 18_STREAM_RESOLVED streamUrl='${playbackSource.streamUrl}'")
                logDiag("[QFILM_DIAG] 19_PLAYER_HANDOFF mimeType='${playbackSource.mimeType}', protocol='${playbackSource.protocol}', variantsCount=${variants.size}")
                logD("QfilmScraper", "[qfilm] extraction completed: embed resolved to $protocol")
                return@withContext Result.success(
                    ExtractionResult(
                        playbackSource = playbackSource,
                        downloadSource = downloadSource,
                        variants = variants
                    )
                )
            }
        }

        // Secondary fallback: Static / unpacked HTTP extraction if webEngine is null or fails
        try {
            var extractedMediaUrl = StaticMediaExtractor.extract(link, base, userAgent)
            if (extractedMediaUrl.isNullOrBlank()) {
                val embedDoc = Jsoup.connect(link)
                    .userAgent(userAgent)
                    .referrer(base)
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
                logDiag("[QFILM_DIAG] 18_STREAM_RESOLVED streamUrl='$extractedMediaUrl' (via HTTP fallback)")
                logDiag("[QFILM_DIAG] 19_PLAYER_HANDOFF mimeType='${playbackSource.mimeType}', protocol='${playbackSource.protocol}', variantsCount=${variants.size}")
                logD("QfilmScraper", "[qfilm] extraction completed: HTTP fallback resolved $extractedMediaUrl")
                return@withContext Result.success(ExtractionResult(playbackSource = playbackSource, downloadSource = downloadSource, variants = variants))
            }
        } catch (_: Exception) {}

        logDiag("[QFILM_DIAG] 17_STREAM_EXTRACTION_FAILED server='${request.serverItem.name}'")
        logW("QfilmScraper", "[qfilm] extraction failed for server: ${request.serverItem.name}")
        Result.failure(ExtensionError.ExtractionFailed("Could not extract stream for server: ${request.serverItem.name}"))
    }

    internal fun getBundledQfilmScript(): String {
        return """
            (function() {
                if (window.__qfilm_injected) return;
                window.__qfilm_injected = true;

                var pathname = (window.location.pathname || '').toLowerCase();
                var href = (window.location.href || '').toLowerCase();

                function log(msg) {
                    console.log('[qfilm] ' + msg);
                    console.log('[QFILM_DIAG] ' + msg);
                }

                function classifyServerInJs(url, index) {
                    var u = url.toLowerCase();
                    if (u.indexOf('uqload') !== -1) return 'Uqload';
                    if (u.indexOf('vidbom') !== -1) return 'VidBom';
                    if (u.indexOf('vidshar') !== -1) return 'VidShar';
                    if (u.indexOf('dood') !== -1) return 'DoodStream';
                    if (u.indexOf('streamtape') !== -1) return 'StreamTape';
                    if (u.indexOf('mp4upload') !== -1) return 'Mp4Upload';
                    if (u.indexOf('upstream') !== -1) return 'Upstream';
                    if (u.indexOf('mixdrop') !== -1) return 'MixDrop';
                    if (u.indexOf('filelions') !== -1) return 'FileLions';
                    if (u.indexOf('streamwish') !== -1) return 'StreamWish';
                    if (u.indexOf('vidspeed') !== -1) return 'VidSpeed';
                    if (u.indexOf('akamaized') !== -1) return 'Akamai CDN';
                    return 'سيرفر ' + index;
                }

                // --- 1. SEARCH PAGE ---
                if (pathname.indexOf('search.php') !== -1 || href.indexOf('search.php') !== -1) {
                    log('search started: ' + window.location.href);
                    var searchPollCount = 0;
                    var maxSearchPoll = 15; // 15 * 500ms = 7.5s

                    function pollSearchDom() {
                        searchPollCount++;
                        var elements = document.querySelectorAll('ul.pm-ul-browse-videos li, ul#pm-grid li, .pm-ul-browse-videos li');
                        if (elements && elements.length > 0) {
                            log('search DOM ready, found ' + elements.length + ' items');
                            var watchLink = null;
                            for (var i = 0; i < elements.length; i++) {
                                var a = elements[i].querySelector('a[href*="watch.php"], h3.caption a, a');
                                if (a && a.href && a.href.indexOf('watch.php') !== -1) {
                                    watchLink = a.href;
                                    break;
                                }
                            }
                            if (watchLink) {
                                log('navigating from search.php to watch.php: ' + watchLink);
                                window.location.href = watchLink;
                                return;
                            }
                        }

                        if (searchPollCount < maxSearchPoll) {
                            setTimeout(pollSearchDom, 500);
                        } else {
                            log('search DOM wait timed out');
                            if (typeof SafeScraperBridge !== 'undefined' && SafeScraperBridge.postMessage) {
                                SafeScraperBridge.postMessage(JSON.stringify({
                                    type: 'ERROR',
                                    detail: 'Search DOM wait timed out without finding watch.php link'
                                }));
                            }
                        }
                    }

                    pollSearchDom();
                    return;
                }

                // --- 2. WATCH PAGE ---
                if (pathname.indexOf('watch.php') !== -1 || href.indexOf('watch.php') !== -1) {
                    log('watch page reached: ' + window.location.href);
                    var watchPollCount = 0;
                    var maxWatchPoll = 15; // 15 * 500ms = 7.5s

                    function pollWatchDom() {
                        watchPollCount++;
                        var playLink = document.querySelector('.video-bibplayer a.xtgo, a.xtgo[href*="play.php"], a[href*="play.php"], a.xtgo');
                        if (playLink && playLink.href && playLink.href.indexOf('play.php') !== -1) {
                            log('play link found on watch page, navigating to play.php: ' + playLink.href);
                            window.location.href = playLink.href;
                            return;
                        }

                        if (watchPollCount < maxWatchPoll) {
                            setTimeout(pollWatchDom, 500);
                        } else {
                            log('play.php link not found on watch page within timeout');
                            if (typeof SafeScraperBridge !== 'undefined' && SafeScraperBridge.postMessage) {
                                SafeScraperBridge.postMessage(JSON.stringify({
                                    type: 'ERROR',
                                    detail: 'Could not resolve play.php from watch.php'
                                }));
                            }
                        }
                    }

                    pollWatchDom();
                    return;
                }

                // --- 3. PLAY PAGE ---
                if (pathname.indexOf('play.php') !== -1 || href.indexOf('play.php') !== -1) {
                    log('play page reached: ' + window.location.href);
                    log('waiting for window.servers');

                    var playPollCount = 0;
                    var maxPlayPoll = 20; // 20 * 500ms = 10s bounded polling

                    function pollForServers() {
                        playPollCount++;
                        var list = [];
                        if (typeof servers !== 'undefined' && Array.isArray(servers) && servers.length > 0) {
                            list = servers;
                        } else if (typeof window.servers !== 'undefined' && Array.isArray(window.servers) && window.servers.length > 0) {
                            list = window.servers;
                        }

                        if (list.length > 0) {
                            log('window.servers found, size = ' + list.length);
                            extractServersFromList(list);
                            return;
                        }

                        if (playPollCount < maxPlayPoll) {
                            setTimeout(pollForServers, 500);
                        } else {
                            log('window.servers wait timed out, attempting iframe DOM extraction fallback');
                            extractIframesFromDom();
                        }
                    }

                    function extractServersFromList(list) {
                        var serverItems = [];
                        var parser = (typeof DOMParser !== 'undefined') ? new DOMParser() : null;

                        for (var i = 0; i < list.length; i++) {
                            var item = list[i];
                            var src = null;

                            if (typeof item === 'string') {
                                if (parser) {
                                    try {
                                        var parsedDoc = parser.parseFromString(item, 'text/html');
                                        var iframeEl = parsedDoc.querySelector('iframe');
                                        if (iframeEl && iframeEl.getAttribute('src')) {
                                            src = iframeEl.getAttribute('src');
                                        }
                                    } catch(e) {}
                                }

                                if (!src) {
                                    var match = item.match(/src\s*=\s*\\?["'](https?:[^"'\\]+)\\?["']/i);
                                    if (match && match[1]) {
                                        src = match[1];
                                    } else if (item.indexOf('http://') === 0 || item.indexOf('https://') === 0) {
                                        src = item.trim();
                                    }
                                }
                            } else if (item && typeof item === 'object') {
                                src = item.src || item.link || item.url || null;
                            }

                            if (src) {
                                src = src.trim();
                                if ((src.indexOf('http://') === 0 || src.indexOf('https://') === 0) &&
                                    src.indexOf('cloudflare') === -1 &&
                                    src.indexOf('googleads') === -1) {
                                    var serverIndex = serverItems.length + 1;
                                    serverItems.push({
                                        id: serverIndex.toString(),
                                        name: classifyServerInJs(src, serverIndex),
                                        link: src
                                    });
                                }
                            }
                        }

                        if (serverItems.length > 0) {
                            log('server array size = ' + serverItems.length);
                            log('SERVER_LIST emitted');
                            if (typeof SafeScraperBridge !== 'undefined' && SafeScraperBridge.postMessage) {
                                SafeScraperBridge.postMessage(JSON.stringify({
                                    type: 'SERVER_LIST',
                                    servers: serverItems
                                }));
                            }
                        } else {
                            extractIframesFromDom();
                        }
                    }

                    function extractIframesFromDom() {
                        var serverItems = [];
                        var iframes = document.querySelectorAll('iframe[src]');
                        for (var j = 0; j < iframes.length; j++) {
                            var fSrc = iframes[j].getAttribute('src') || iframes[j].src || '';
                            fSrc = fSrc.trim();
                            if ((fSrc.indexOf('http://') === 0 || fSrc.indexOf('https://') === 0) &&
                                fSrc.indexOf('cloudflare') === -1 &&
                                fSrc.indexOf('googleads') === -1) {
                                var sIndex = serverItems.length + 1;
                                serverItems.push({
                                    id: sIndex.toString(),
                                    name: classifyServerInJs(fSrc, sIndex),
                                    link: fSrc
                                });
                            }
                        }

                        if (serverItems.length > 0) {
                            log('iframe extracted, count = ' + serverItems.length);
                            log('SERVER_LIST emitted');
                            if (typeof SafeScraperBridge !== 'undefined' && SafeScraperBridge.postMessage) {
                                SafeScraperBridge.postMessage(JSON.stringify({
                                    type: 'SERVER_LIST',
                                    servers: serverItems
                                }));
                            }
                        } else {
                            log('no servers or iframes found on play.php');
                            if (typeof SafeScraperBridge !== 'undefined' && SafeScraperBridge.postMessage) {
                                SafeScraperBridge.postMessage(JSON.stringify({
                                    type: 'ERROR',
                                    detail: 'No servers or iframes found on play.php'
                                }));
                            }
                        }
                    }

                    pollForServers();
                    return;
                }

                // --- 4. UNKNOWN PAGE DISPATCH ---
                if (pathname !== '/' && pathname.indexOf('index.php') === -1) {
                    log('unknown page encountered: ' + pathname);
                    if (typeof SafeScraperBridge !== 'undefined' && SafeScraperBridge.postMessage) {
                        SafeScraperBridge.postMessage(JSON.stringify({
                            type: 'ERROR',
                            detail: 'Unexpected page encountered: ' + pathname
                        }));
                    }
                }
            })();
        """.trimIndent()
    }
}
