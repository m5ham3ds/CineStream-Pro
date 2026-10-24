package com.example.extension.managed.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * Headless, sandboxed WebView extraction engine implementing a multi-stage media extraction pipeline:
 * Stage A: Static HTML scan & script unpacker (Fast path)
 * Stage B: DOM media inspection (querySelectorAll video/source, MutationObserver)
 * Stage C: Runtime JavaScript inspection (Playerjs, JWPlayer, VideoJS, performance timing, globals)
 * Stage D: Network resource observation (shouldInterceptRequest, fetch, XMLHttpRequest)
 * Stage E: Player state inspection & play activation
 * Stage F: Normalization
 * Stage G: Validation
 *
 * Enforces strict security configurations, HTTPS-only navigation, and zero SSL bypass.
 */
class ControlledWebViewEngine(
    private val context: Context
) : WebExtractionEngine {

    private var activeWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isCancelled = false

    private fun logDiag(msg: String) {
        try {
            Log.i("ControlledWebViewEngine", msg)
        } catch (_: Throwable) {
            println("DIAG: [ControlledWebViewEngine] $msg")
        }
    }

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

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun extractServers(
        targetUrl: String,
        script: String,
        timeoutMs: Long,
        expectedOrigin: String
    ): Result<List<ServerItem>> = withContext(Dispatchers.Main.immediate) {
        if (!targetUrl.startsWith("https://", ignoreCase = true)) {
            logDiag("EXTRACTION_FAILED: Non-HTTPS navigation rejected: $targetUrl")
            return@withContext Result.failure(
                ExtensionError.InvalidBaseUrl(targetUrl, "Non-HTTPS navigation rejected").let { Exception(it.message) }
            )
        }

        val targetUri = try { Uri.parse(targetUrl) } catch (_: Exception) { null }
        val targetHost = targetUri?.host?.lowercase()
        if (targetHost.isNullOrBlank() || TrustedEmbedHostPolicy.isForbiddenHost(targetHost)) {
            logDiag("EXTRACTION_FAILED: Forbidden or invalid host rejected: $targetUrl")
            return@withContext Result.failure(
                ExtensionError.InvalidBaseUrl(targetUrl, "Forbidden or invalid host rejected").let { Exception(it.message) }
            )
        }

        logDiag("EXTRACTION_START: extractServers targetUrl='$targetUrl', timeoutMs=$timeoutMs")
        isCancelled = false
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    logDiag("EXTRACTION_CANCELLED: extractServers cancelled")
                    cleanup()
                }

                val bridge = SafeScraperBridge(
                    expectedOrigin = expectedOrigin,
                    targetEmbedOrigin = targetUrl,
                    listener = object : SafeScraperBridge.BridgeMessageListener {
                        override fun onServerList(servers: List<ServerItem>) {
                            if (!isCancelled && continuation.isActive) {
                                logDiag("EXTRACTION_SUCCESS: Server list received with ${servers.size} items")
                                cleanup()
                                continuation.resume(Result.success(servers))
                            }
                        }

                        override fun onExtractionResult(streamUrl: String) {
                            if (!isCancelled && continuation.isActive) {
                                val normalized = MediaStreamDetector.normalizeStreamUrl(streamUrl)
                                if (MediaStreamDetector.validateMediaUrl(normalized)) {
                                    logDiag("MEDIA_VALIDATED: $normalized")
                                    logDiag("EXTRACTION_SUCCESS: $normalized")
                                    cleanup()
                                    continuation.resume(
                                        Result.success(
                                            listOf(ServerItem(id = "1", name = "السيرفر الرئيسي", link = normalized, isDirectStream = true))
                                        )
                                    )
                                } else {
                                    logDiag("MEDIA_CANDIDATE_REJECTED: Invalid stream URL: $streamUrl")
                                }
                            }
                        }

                        override fun onChallengeState(status: ChallengeStatus) {
                            logDiag("CHALLENGE_STATE: $status")
                            if ((status == ChallengeStatus.DETECTED || status == ChallengeStatus.FAILED) && !isCancelled && continuation.isActive) {
                                logDiag("EXTRACTION_CHALLENGE_TRIGGERED: status=$status")
                                cleanup()
                                continuation.resume(
                                    Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered ($status)"))
                                )
                            }
                        }

                        override fun onError(error: ExtensionError) {
                            if (!isCancelled && continuation.isActive) {
                                logDiag("EXTRACTION_FAILED: Bridge reported error: ${error.message}")
                                cleanup()
                                continuation.resume(Result.failure(Exception(error.message)))
                            }
                        }
                    }
                )

                createAndConfigureWebView(bridge, script, expectedOrigin, targetUrl) { err ->
                    if (!isCancelled && continuation.isActive) {
                        logDiag("EXTRACTION_FAILED: WebView error: ${err.message}")
                        cleanup()
                        continuation.resume(Result.failure(Exception(err.message)))
                    }
                }
            }
        }

        cleanup()
        if (result == null) {
            logDiag("EXTRACTION_TIMEOUT: extractServers timed out after ${timeoutMs}ms")
        }
        result ?: Result.failure(Exception(ExtensionError.Timeout(timeoutMs).message))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun extractStreamUrl(
        targetUrl: String,
        targetServerId: String?,
        script: String,
        timeoutMs: Long,
        expectedOrigin: String
    ): Result<String> = withContext(Dispatchers.Main.immediate) {
        if (!targetUrl.startsWith("https://", ignoreCase = true)) {
            logDiag("EXTRACTION_FAILED: Non-HTTPS navigation rejected: $targetUrl")
            return@withContext Result.failure(
                Exception(ExtensionError.InvalidBaseUrl(targetUrl, "Non-HTTPS navigation rejected").message)
            )
        }

        val targetUri = try { Uri.parse(targetUrl) } catch (_: Exception) { null }
        val targetHost = targetUri?.host?.lowercase()
        if (targetHost.isNullOrBlank() || TrustedEmbedHostPolicy.isForbiddenHost(targetHost)) {
            logDiag("EXTRACTION_FAILED: Forbidden or invalid host rejected: $targetUrl")
            return@withContext Result.failure(
                Exception(ExtensionError.InvalidBaseUrl(targetUrl, "Forbidden or invalid host rejected").message)
            )
        }

        logDiag("EXTRACTION_START: extractStreamUrl targetUrl='$targetUrl', timeoutMs=$timeoutMs")

        isCancelled = false
        val result = withTimeoutOrNull(timeoutMs) {
            // STAGE A: Fast Static Scan & Unpacker (Non-blocking IO, bounded by timeout)
            val staticStream = withContext(Dispatchers.IO) {
                try {
                    StaticMediaExtractor.extract(targetUrl, expectedOrigin)
                } catch (_: Exception) {
                    null
                }
            }
            if (!staticStream.isNullOrBlank()) {
                val normalized = MediaStreamDetector.normalizeStreamUrl(staticStream)
                if (MediaStreamDetector.validateMediaUrl(normalized)) {
                    logDiag("MEDIA_VALIDATED: $normalized (Stage A: Static Extractor)")
                    logDiag("EXTRACTION_SUCCESS: $normalized (Stage A: Fast Path)")
                    return@withTimeoutOrNull Result.success(normalized)
                }
            }

            // STAGES B - G: Headless WebView Runtime Extraction
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    logDiag("EXTRACTION_CANCELLED: extractStreamUrl cancelled")
                    cleanup()
                }

                val bridge = SafeScraperBridge(
                    expectedOrigin = expectedOrigin,
                    targetEmbedOrigin = targetUrl,
                    listener = object : SafeScraperBridge.BridgeMessageListener {
                        override fun onServerList(servers: List<ServerItem>) {
                            val direct = servers.firstOrNull { it.isDirectStream }
                            if (direct != null && !isCancelled && continuation.isActive) {
                                val normalized = MediaStreamDetector.normalizeStreamUrl(direct.link)
                                if (MediaStreamDetector.validateMediaUrl(normalized)) {
                                    logDiag("MEDIA_VALIDATED: $normalized (via onServerList)")
                                    logDiag("EXTRACTION_SUCCESS: $normalized")
                                    cleanup()
                                    continuation.resume(Result.success(normalized))
                                }
                            }
                        }

                        override fun onExtractionResult(streamUrl: String) {
                            if (!isCancelled && continuation.isActive) {
                                val normalized = MediaStreamDetector.normalizeStreamUrl(streamUrl)
                                if (MediaStreamDetector.validateMediaUrl(normalized)) {
                                    logDiag("MEDIA_VALIDATED: $normalized (via onExtractionResult)")
                                    logDiag("EXTRACTION_SUCCESS: $normalized")
                                    cleanup()
                                    continuation.resume(Result.success(normalized))
                                } else {
                                    logDiag("MEDIA_CANDIDATE_REJECTED: Rejection from validateMediaUrl: '$streamUrl'")
                                }
                            }
                        }

                        override fun onChallengeState(status: ChallengeStatus) {
                            logDiag("CHALLENGE_STATE: $status")
                            if ((status == ChallengeStatus.DETECTED || status == ChallengeStatus.FAILED) && !isCancelled && continuation.isActive) {
                                logDiag("EXTRACTION_CHALLENGE_TRIGGERED: status=$status")
                                cleanup()
                                continuation.resume(
                                    Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered ($status)"))
                                )
                            }
                        }

                        override fun onError(error: ExtensionError) {
                            if (!isCancelled && continuation.isActive) {
                                logDiag("EXTRACTION_FAILED: Bridge error: ${error.message}")
                                cleanup()
                                continuation.resume(Result.failure(Exception(error.message)))
                            }
                        }
                    }
                )

                createAndConfigureWebView(bridge, script, expectedOrigin, targetUrl) { err ->
                    if (!isCancelled && continuation.isActive) {
                        logDiag("EXTRACTION_FAILED: WebView error: ${err.message}")
                        cleanup()
                        continuation.resume(Result.failure(Exception(err.message)))
                    }
                }
            }
        }

        cleanup()
        if (result == null) {
            logDiag("EXTRACTION_TIMEOUT: extractStreamUrl timed out after ${timeoutMs}ms")
        }
        result ?: Result.failure(Exception(ExtensionError.Timeout(timeoutMs).message))
    }

    override suspend fun extractMedia(
        targetUrl: String,
        targetServerId: String?,
        script: String,
        timeoutMs: Long,
        expectedOrigin: String
    ): Result<ExtractionResult> {
        val streamResult = extractStreamUrl(targetUrl, targetServerId, script, timeoutMs, expectedOrigin)
        return if (streamResult.isSuccess) {
            val streamUrl = streamResult.getOrThrow()
            val headers = if (expectedOrigin.isNotBlank()) mapOf("Referer" to expectedOrigin) else emptyMap()
            Result.success(MediaStreamDetector.createExtractionResult(streamUrl, headers))
        } else {
            Result.failure(streamResult.exceptionOrNull() ?: Exception("Extraction failed"))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndConfigureWebView(
        bridge: SafeScraperBridge,
        script: String,
        expectedOrigin: String,
        targetUrl: String,
        onFatalError: (ExtensionError) -> Unit
    ) {
        cleanup()

        try {
            val webView = WebView(context.applicationContext)
            activeWebView = webView
            logDiag("WEBVIEW_CREATED: WebView instance created successfully")

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                // CRITICAL SECURITY: Never allow file or content access from remote web pages
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = false
                blockNetworkImage = false // Allow poster & UI images so players don't halt
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = MediaStreamDetector.STANDARD_USER_AGENT
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message() ?: ""
                    val level = consoleMessage?.messageLevel()
                    val line = consoleMessage?.lineNumber()
                    val src = consoleMessage?.sourceId() ?: ""
                    logD("ControlledWebViewEngine", "[CONSOLE] [$level] $msg ($src:$line)")
                    return true
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress >= 15 && !isCancelled && activeWebView == view) {
                        // Inject early runtime hooks before complex body scripts load
                        view?.evaluateJavascript(RuntimeMediaExtractionScript.EARLY_HOOK_SCRIPT, null)
                    }
                }
            }

            webView.addJavascriptInterface(bridge, "SafeScraperBridge")

            webView.webViewClient = object : WebViewClient() {
                private var streamFound = false

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    // CRITICAL HARD SECURITY RULE: Never proceed on SSL error.
                    handler?.cancel()
                    logDiag("EXTRACTION_FAILED: SSL Verification Failed: ${error?.primaryError}")
                    onFatalError(ExtensionError.ExtractionFailed("SSL Verification Failed: ${error?.primaryError}"))
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    logDiag("PAGE_STARTED: url='$url'")
                    if (!isCancelled && !streamFound) {
                        view?.evaluateJavascript(RuntimeMediaExtractionScript.EARLY_HOOK_SCRIPT, null)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return true
                    val uri = try { Uri.parse(url) } catch (_: Exception) { return true }

                    // Intercept direct media streams and prevent raw rendering in WebView
                    if (MediaStreamDetector.isMediaUrl(url)) {
                        logDiag("MEDIA_CANDIDATE_FOUND: Intercepted via shouldOverrideUrlLoading: $url")
                        if (!streamFound) {
                            val normalized = MediaStreamDetector.normalizeStreamUrl(url)
                            if (MediaStreamDetector.validateMediaUrl(normalized)) {
                                streamFound = true
                                logDiag("MEDIA_VALIDATED: $normalized")
                                mainHandler.post {
                                    val payload = JSONObject().apply {
                                        put("type", SafeScraperBridge.TYPE_EXTRACTION_RESULT)
                                        put("streamUrl", normalized)
                                    }.toString()
                                    bridge.postMessage(payload)
                                }
                            }
                        }
                        return true
                    }

                    val initialHost = try { Uri.parse(targetUrl).host } catch (_: Exception) { null }
                    val expectedHost = try { Uri.parse(expectedOrigin).host } catch (_: Exception) { null }

                    // Apply TrustedEmbedHostPolicy
                    val isAllowed = TrustedEmbedHostPolicy.isNavigationAllowed(
                        targetUri = uri,
                        expectedOriginHost = expectedHost,
                        initialTargetHost = initialHost
                    )

                    if (!isAllowed) {
                        logDiag("MEDIA_CANDIDATE_REJECTED: Untrusted navigation blocked by policy: $url")
                    }

                    return !isAllowed
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    val host = request?.url?.host?.lowercase() ?: ""

                    // Block known tracking and ad networks (never block Cloudflare verification infrastructure)
                    if (!host.contains("cloudflare") && (MediaStreamDetector.isAdOrAnalytics(reqUrl) || host.contains("google-analytics") ||
                        host.contains("doubleclick") || host.contains("popcash") ||
                        host.contains("propeller") || host.contains("adtrue") || host.contains("clarity.ms"))) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    // Stage D: Sniff media stream requests (.m3u8, .mp4, /hls/, videodelivery, etc.)
                    val acceptHeader = request?.requestHeaders?.entries
                        ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value ?: ""
                    val isMedia = MediaStreamDetector.isMediaUrl(reqUrl) ||
                        acceptHeader.contains("application/vnd.apple.mpegurl", ignoreCase = true) ||
                        acceptHeader.contains("application/x-mpegurl", ignoreCase = true) ||
                        acceptHeader.contains("video/", ignoreCase = true)

                    if (isMedia) {
                        logDiag("RESOURCE_SCAN: Candidate media network request detected: $reqUrl")
                        val normalized = MediaStreamDetector.normalizeStreamUrl(reqUrl)
                        if (MediaStreamDetector.validateMediaUrl(normalized)) {
                            if (!streamFound) {
                                streamFound = true
                                logDiag("MEDIA_VALIDATED: $normalized (Stage D: Network Request)")
                                mainHandler.post {
                                    val payload = JSONObject().apply {
                                        put("type", SafeScraperBridge.TYPE_EXTRACTION_RESULT)
                                        put("streamUrl", normalized)
                                    }.toString()
                                    bridge.postMessage(payload)
                                }
                            }

                            // Request observation != download: For direct video files (.mp4, .mkv, .webm),
                            // return an empty response so WebView does not waste bandwidth downloading the file
                            val pathNoQuery = normalized.substringBefore("?").lowercase()
                            if (pathNoQuery.endsWith(".mp4") || pathNoQuery.endsWith(".mkv") || pathNoQuery.endsWith(".webm")) {
                                return WebResourceResponse("video/mp4", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true && (errorResponse?.statusCode == 403 || errorResponse?.statusCode == 503)) {
                        logDiag("CHALLENGE_HTTP_ERROR: statusCode=${errorResponse?.statusCode}")
                        mainHandler.post {
                            val payload = JSONObject().apply {
                                put("type", SafeScraperBridge.TYPE_CHALLENGE_STATE)
                                put("status", "DETECTED")
                            }.toString()
                            bridge.postMessage(payload)
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    logDiag("PAGE_FINISHED: url='$url'")
                    if (isCancelled || streamFound) return

                    // Check for Cloudflare challenge markers in DOM
                    view?.evaluateJavascript(
                        "(function() {" +
                        "  var title = (document.title || '').toLowerCase();" +
                        "  var isCf = title.indexOf('just a moment') !== -1 ||" +
                        "             title.indexOf('cloudflare') !== -1 ||" +
                        "             title.indexOf('attention required') !== -1 ||" +
                        "             document.querySelector('#challenge-form, #turnstile-wrapper, .cf-browser-verification, iframe[src*=\"cloudflare\"]') !== null;" +
                        "  if (isCf && window.SafeScraperBridge && window.SafeScraperBridge.postMessage) {" +
                        "    window.SafeScraperBridge.postMessage(JSON.stringify({ type: 'CHALLENGE_STATE', status: 'DETECTED' }));" +
                        "  }" +
                        "})();", null
                    )

                    logDiag("JS_RUNTIME_READY: Injecting RuntimeMediaExtractionScript and custom scripts")
                    val runtimeScript = RuntimeMediaExtractionScript.SCRIPT
                    val scriptToRun = if (script.isNotBlank()) "$script\n;$runtimeScript" else runtimeScript
                    view?.evaluateJavascript(scriptToRun, null)

                    // Inject staggered polling for asynchronous player setup (Stages B, C, E)
                    val pollDelays = listOf(300L, 800L, 1800L, 3500L, 5500L)
                    for (delay in pollDelays) {
                        mainHandler.postDelayed({
                            if (!isCancelled && !streamFound && activeWebView == view) {
                                logDiag("DOM_MEDIA_SCAN: Running staggered runtime check (delay=${delay}ms)")
                                logDiag("PLAYER_STATE_SCAN: Inspecting player state and triggering activation")
                                view?.evaluateJavascript(scriptToRun, null)
                            }
                        }, delay)
                    }
                }
            }

            val headers = mutableMapOf(
                "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
                "DNT" to "1",
                "Upgrade-Insecure-Requests" to "1"
            )
            if (expectedOrigin.isNotBlank()) {
                headers["Referer"] = expectedOrigin
            }
            webView.loadUrl(targetUrl, headers)
        } catch (e: Exception) {
            logDiag("EXTRACTION_FAILED: Initialization failed: ${e.message}")
            onFatalError(ExtensionError.ExtractionFailed("Failed to initialize WebView: ${e.message}", e))
        }
    }

    override fun cancel() {
        logDiag("EXTRACTION_CANCELLED: cancel() called")
        isCancelled = true
        mainHandler.post {
            cleanup()
        }
    }

    private fun cleanup() {
        activeWebView?.let { webView ->
            try {
                webView.stopLoading()
                webView.removeJavascriptInterface("SafeScraperBridge")
                webView.webViewClient = WebViewClient()
                webView.webChromeClient = WebChromeClient()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            } catch (_: Exception) {}
        }
        activeWebView = null
    }

    companion object {
        fun getPublicEmbedMediaExtractionScript(): String {
            return RuntimeMediaExtractionScript.SCRIPT
        }
    }
}
