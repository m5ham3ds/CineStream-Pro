package com.example.extension.managed.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Stage A: Static HTML & Unpacked Script Extractor.
 * Inspects page HTML, DOM media elements, raw scripts, and dynamically unpacks
 * obfuscated/packed JavaScript (e.g. Dean Edwards p.a.c.k.e.r) to discover media URLs.
 */
object StaticMediaExtractor {

    suspend fun extract(
        targetUrl: String,
        referer: String? = null,
        userAgent: String = MediaStreamDetector.STANDARD_USER_AGENT
    ): String? = withContext(Dispatchers.IO) {
        if (!targetUrl.startsWith("https://", ignoreCase = true) && !targetUrl.startsWith("http://", ignoreCase = true)) {
            return@withContext null
        }

        try {
            val connection = Jsoup.connect(targetUrl)
                .userAgent(userAgent)
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .header("DNT", "1")
                .timeout(8000)

            if (!referer.isNullOrBlank()) {
                connection.referrer(referer)
            }

            val doc = connection.get()
            val html = doc.html()

            // 1. Direct <video> and <source> elements in static DOM
            for (video in doc.select("video")) {
                val src = video.attr("src").trim()
                val norm = MediaStreamDetector.normalizeStreamUrl(src)
                if (MediaStreamDetector.validateMediaUrl(norm)) {
                    return@withContext norm
                }
                for (source in video.select("source")) {
                    val sSrc = source.attr("src").ifBlank { source.attr("data-src") }.trim()
                    val sNorm = MediaStreamDetector.normalizeStreamUrl(sSrc)
                    if (MediaStreamDetector.validateMediaUrl(sNorm)) {
                        return@withContext sNorm
                    }
                }
            }

            // 2. Standalone <source> tags
            for (source in doc.select("source")) {
                val sSrc = source.attr("src").ifBlank { source.attr("data-src") }.trim()
                val sNorm = MediaStreamDetector.normalizeStreamUrl(sSrc)
                if (MediaStreamDetector.validateMediaUrl(sNorm)) {
                    return@withContext sNorm
                }
            }

            // 3. Scan script tags for un-obfuscated media URLs
            for (script in doc.select("script")) {
                val content = script.data().ifBlank { script.html() }
                if (content.isBlank()) continue

                val directCandidates = MediaStreamDetector.extractMediaCandidates(content)
                val validDirect = directCandidates.firstOrNull { MediaStreamDetector.validateMediaUrl(it) }
                if (validDirect != null) {
                    return@withContext validDirect
                }
            }

            // 4. Scan and unpack obfuscated / packed scripts (Dean Edwards p.a.c.k.e.r)
            if (JsPackerUnpacker.isPacked(html)) {
                val unpackedBlocks = JsPackerUnpacker.unpackAll(html)
                for (unpacked in unpackedBlocks) {
                    val unpackedCandidates = MediaStreamDetector.extractMediaCandidates(unpacked)
                    val validUnpacked = unpackedCandidates.firstOrNull { MediaStreamDetector.validateMediaUrl(it) }
                    if (validUnpacked != null) {
                        return@withContext validUnpacked
                    }
                }
            }

            // 5. Check iframes that point directly to media
            for (iframe in doc.select("iframe")) {
                val ifrSrc = iframe.attr("src").trim()
                val ifrNorm = MediaStreamDetector.normalizeStreamUrl(ifrSrc)
                if (MediaStreamDetector.validateMediaUrl(ifrNorm)) {
                    return@withContext ifrNorm
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }
}
