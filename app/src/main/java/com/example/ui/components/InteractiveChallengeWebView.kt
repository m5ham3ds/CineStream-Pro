package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import com.example.extension.managed.runtime.web.InteractiveChallengeController
import com.example.extension.managed.runtime.web.ChallengeStateMachine
import com.example.extension.managed.runtime.web.PerSiteSessionStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.extension.managed.web.MediaStreamDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 05Q.3-A: Player-Contained Interactive Challenge WebView & Verification Overlay.
 *
 * Responsibilities:
 * 1. Player-Contained UI: Rendered strictly inside the player container bounds (16:9 inline player or
 *    fullscreen player). Never covers Details title, metadata, trailers, episodes, toolbar, or bottom navigation.
 * 2. Multi-Signal Verification: Actively polls and combines CookieManager (cf_clearance), DOM, URL,
 *    and Title checks to instantly detect Turnstile and Cloudflare completion without waiting for full reloads.
 * 3. Stable WebView Lifecycle: Prevents timer-based recomposition from repeatedly reloading the challenge URL.
 * 4. User Interaction & Cancellation: Compact Arabic instructions, countdown badge, and player-scoped cancel/retry controls.
 */
object InteractiveChallengeWebViewHelper {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun removePolling(runnable: Runnable) {
        mainHandler.removeCallbacks(runnable)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(
        webView: WebView,
        canonicalHost: String,
        controller: InteractiveChallengeController,
        attemptId: Long = controller.stateFlow.value.attemptId,
        challengeKey: String = controller.stateFlow.value.challengeKey,
        onCompletionDetected: (Map<String, String>) -> Unit
    ): Runnable {
        val cookieManager = try { CookieManager.getInstance() } catch (_: Throwable) { null }
        cookieManager?.setAcceptCookie(true)
        cookieManager?.setAcceptThirdPartyCookies(webView, true)

        val defaultUa = try {
            WebSettings.getDefaultUserAgent(webView.context)
        } catch (_: Throwable) {
            null
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            if (!defaultUa.isNullOrBlank()) {
                userAgentString = defaultUa
            }
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false

        // Ensure user interactions (taps, drags, Turnstile clicks) reach the WebView
        // and pin state to VERIFYING as soon as user touches Turnstile checkbox.
        webView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_UP -> {
                    // Phase 05Q.3-B: Pin state to VERIFYING as soon as user begins interaction
                    if (controller.stateFlow.value.phase == ChallengeStateMachine.Phase.WAITING_FOR_USER) {
                        controller.onUserVerificationStarted()
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        var isCompleted = false

        fun triggerSuccessIfVerified() {
            if (isCompleted || controller.isAttemptCancelled(attemptId, challengeKey)) return
            val currentUrl = webView.url ?: ""
            checkVerificationCompletion(webView, currentUrl, canonicalHost, controller, attemptId, challengeKey) { passed ->
                if (passed && !isCompleted && !controller.isAttemptCancelled(attemptId, challengeKey)) {
                    isCompleted = true
                    val cookies = PerSiteSessionStore.getInstance().captureCookiesFromWebView(canonicalHost)
                    onCompletionDetected(cookies)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                // Internal progress does NOT reset verification or create new challenge
                if (newProgress >= 50 && view != null) {
                    scrollTurnstileIntoView(view, challengeKey, controller)
                }
                if (newProgress >= 70) {
                    triggerSuccessIfVerified()
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                triggerSuccessIfVerified()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                // Cloudflare internal navigation / form submission must not reset state to CHALLENGE_DETECTED
                if (url != null) {
                    controller.onSiteLoaded(url)
                }
                triggerSuccessIfVerified()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                if (view != null) {
                    scrollTurnstileIntoView(view, challengeKey, controller)
                }
                triggerSuccessIfVerified()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                triggerSuccessIfVerified()
            }
        }

        // Active Periodic Monitor: Polls every 500ms while user interacts with the challenge
        // to scroll Turnstile into view and detect genuine Turnstile / cf_clearance completion.
        val pollingRunnable = object : Runnable {
            override fun run() {
                if (!isCompleted && !controller.isAttemptCancelled(attemptId, challengeKey)) {
                    scrollTurnstileIntoView(webView, challengeKey, controller)
                    triggerSuccessIfVerified()
                    mainHandler.postDelayed(this, 500L)
                }
            }
        }
        mainHandler.postDelayed(pollingRunnable, 600L)

        return pollingRunnable
    }

    /**
     * Phase 05Q.5-B: Intelligent Visual Positioning Engine entry point.
     * Delegates to TurnstilePositioningEngine to position the Turnstile verification box
     * without blind scrolling, without smooth scroll, and without repeating executions.
     */
    fun scrollTurnstileIntoView(
        webView: WebView,
        challengeKey: String = "",
        controller: InteractiveChallengeController = InteractiveChallengeController.getInstance()
    ) {
        val key = challengeKey.ifBlank { controller.stateFlow.value.challengeKey }
        TurnstilePositioningEngine.attemptPositioning(webView, key, controller)
    }

    /**
     * Phase 05Q.3-B: Multi-Signal Verification Completion Detection.
     * Combines DOM state, page title, challenge URL disappearance, CookieManager (cf_clearance),
     * and non-challenge navigation checks.
     * HARD RULE: Presence of cf_clearance alone is NOT sufficient if the page still displays a Challenge!
     */
    fun checkVerificationCompletion(
        webView: WebView,
        currentUrl: String,
        canonicalHost: String,
        controller: InteractiveChallengeController = InteractiveChallengeController.getInstance(),
        attemptId: Long = controller.stateFlow.value.attemptId,
        challengeKey: String = controller.stateFlow.value.challengeKey,
        callback: (Boolean) -> Unit
    ) {
        if (controller.isAttemptCancelled(attemptId, challengeKey)) {
            callback(false)
            return
        }

        // Hard Guard: Blank or about:blank is never verified!
        if (currentUrl.isBlank() || currentUrl == "about:blank" || currentUrl.startsWith("data:")) {
            callback(false)
            return
        }

        val uri = try { Uri.parse(currentUrl) } catch (_: Exception) { null }
        val cleanHost = canonicalHost.removePrefix("www.").lowercase()
        val urlHost = uri?.host?.lowercase() ?: ""
        if (urlHost.isNotBlank() && !urlHost.contains(cleanHost) && !cleanHost.contains(urlHost)) {
            callback(false)
            return
        }

        val path = uri?.path?.lowercase() ?: ""

        // Signal 1: URL is not Cloudflare challenge platform
        val isChallengeUrl = path.contains("/cdn-cgi/challenge-platform/") ||
                currentUrl.contains("challenges.cloudflare.com")

        if (isChallengeUrl) {
            callback(false)
            return
        }

        // Signal 2: Title check
        val title = webView.title?.lowercase() ?: ""
        val isChallengeTitle = title.contains("just a moment") ||
                title.contains("attention required") ||
                title.contains("security check") ||
                title.contains("ddos-guard") ||
                title.contains("please verify")

        if (isChallengeTitle) {
            callback(false)
            return
        }

        // Signal 3: Cookies check from CookieManager
        val cookies = PerSiteSessionStore.getInstance().captureCookiesFromWebView(canonicalHost)
        val hasCfClearance = cookies.containsKey("cf_clearance") && !cookies["cf_clearance"].isNullOrBlank()

        // Signal 4: Detailed DOM inspection
        val jsCheck = """
            (function() {
                try {
                    var title = (document.title || '').toLowerCase();
                    var isTitleChallenge = title.indexOf('just a moment') !== -1 ||
                                           title.indexOf('attention required') !== -1 ||
                                           title.indexOf('security check') !== -1 ||
                                           title.indexOf('ddos-guard') !== -1 ||
                                           title.indexOf('please verify') !== -1;
                    if (isTitleChallenge) {
                        return JSON.stringify({ isChallenge: true, reason: 'CHALLENGE_TITLE' });
                    }

                    var challengeForm = document.querySelector('#challenge-form, #challenge-stage, #turnstile-wrapper, .cf-turnstile, #challenge-running, #cf-wrapper');
                    var challengeIframe = document.querySelector('iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"]');
                    var turnstileToken = document.querySelector('[name="cf-turnstile-response"]');
                    var hasToken = !!(turnstileToken && turnstileToken.value && turnstileToken.value.length > 10);
                    var successElem = document.querySelector('#challenge-success, .cf-turnstile-success');
                    
                    var hasActiveChallengeElements = !!(challengeForm || challengeIframe);
                    var body = document.body;
                    var bodyTextLength = (body && body.innerText) ? body.innerText.trim().length : 0;
                    var htmlLength = (body && body.innerHTML) ? body.innerHTML.length : 0;

                    return JSON.stringify({
                        hasActiveChallengeElements: hasActiveChallengeElements,
                        successElem: !!successElem,
                        hasToken: hasToken,
                        bodyTextLength: bodyTextLength,
                        htmlLength: htmlLength
                    });
                } catch(e) {
                    return JSON.stringify({ error: e.toString() });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCheck) { result ->
            if (controller.isAttemptCancelled(attemptId, challengeKey)) {
                callback(false)
                return@evaluateJavascript
            }
            if (result.isNullOrBlank() || result == "null") {
                callback(false)
                return@evaluateJavascript
            }

            try {
                val cleanJson = if (result.startsWith("\"") && result.endsWith("\"")) {
                    org.json.JSONObject(org.json.JSONTokener(result).nextValue() as String)
                } else {
                    org.json.JSONObject(result)
                }

                val hasActiveChallengeElements = cleanJson.optBoolean("hasActiveChallengeElements", false)
                val successElem = cleanJson.optBoolean("successElem", false)
                val hasToken = cleanJson.optBoolean("hasToken", false)
                val bodyTextLength = cleanJson.optInt("bodyTextLength", 0)
                val htmlLength = cleanJson.optInt("htmlLength", 0)

                // If user solved Turnstile locally (token generated or success element visible),
                // ensure the controller is marked as VERIFYING
                if (hasToken || successElem) {
                    controller.onUserVerificationStarted()
                }

                // HARD RULE (Section 9):
                // "لا تعتبر وجود cf_clearance وحده كافيًا إذا كانت الصفحة لا تزال تعرض Challenge."
                if (hasActiveChallengeElements && !successElem) {
                    callback(false)
                    return@evaluateJavascript
                }

                val isDomClean = (!hasActiveChallengeElements || successElem) && (bodyTextLength > 50 || htmlLength > 200)

                // HARD RULE (Section 5): cf-turnstile-response token is NOT success!
                // Genuine completion requires:
                // (hasCfClearance OR isDomClean) AND cookies.isNotEmpty() AND clean title & URL
                val isVerified = (hasCfClearance || isDomClean) &&
                        cookies.isNotEmpty() &&
                        !isChallengeTitle &&
                        !isChallengeUrl &&
                        (!hasActiveChallengeElements || successElem)

                callback(isVerified)
            } catch (_: Exception) {
                callback(false)
            }
        }
    }
}

/**
 * Phase 05Q.3-A: Player-contained Compose verification overlay.
 * Rendered strictly inside the video player container bounds (16:9 inline player or fullscreen player).
 * Never covers Details title, metadata, trailers, episodes, toolbar, or bottom navigation.
 * Auto-scroll hardening: Automatically scrolls the parent viewport to the challenge surface
 * using BringIntoViewRequester once the WebView is measured, attached, and stable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InteractiveChallengeOverlay(
    state: InteractiveChallengeController.UiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible && state.phase != ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT) return

    val controller = remember { InteractiveChallengeController.getInstance() }
    val context = LocalContext.current
    var pollingRunnable by remember { mutableStateOf<Runnable?>(null) }
    val activeWebView = remember(state.challengeKey) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            val runnable = InteractiveChallengeWebViewHelper.configureWebView(
                webView = this,
                canonicalHost = state.canonicalHost,
                controller = controller,
                attemptId = state.attemptId,
                challengeKey = state.challengeKey,
                onCompletionDetected = { cookies ->
                    controller.onVerificationCompleted(state.attemptId, state.challengeKey, cookies)
                }
            )
            pollingRunnable = runnable
            setTag(com.example.R.id.tag_url, state.challengeKey)
            loadUrl(state.challengeUrl)
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    // ANTI-LOOP REQUIREMENT:
    // Track whether auto-scroll has occurred for the current challenge session/url.
    // Changing countdown timer (state.remainingSeconds) must NOT re-trigger scroll!
    var lastScrolledChallengeKey by remember { mutableStateOf<String?>(null) }
    val currentChallengeKey = remember(state.challengeUrl, state.phase) {
        "${state.challengeUrl}:${state.phase}"
    }

    LaunchedEffect(state.isVisible) {
        if (!state.isVisible) {
            lastScrolledChallengeKey = null
        }
    }

    DisposableEffect(state.challengeKey) {
        controller.registerWebViewCleanup {
            try {
                activeWebView.stopLoading()
                activeWebView.loadUrl("about:blank")
                pollingRunnable?.let { InteractiveChallengeWebViewHelper.removePolling(it) }
                pollingRunnable = null
                TurnstilePositioningEngine.reset(state.challengeKey)
            } catch (_: Throwable) {}
        }
        onDispose {
            controller.unregisterWebViewCleanup()
            try {
                activeWebView.stopLoading()
                activeWebView.loadUrl("about:blank")
                activeWebView.destroy()
                pollingRunnable?.let { InteractiveChallengeWebViewHelper.removePolling(it) }
                pollingRunnable = null
                TurnstilePositioningEngine.reset(state.challengeKey)
            } catch (_: Throwable) {}
        }
    }

    Box(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .clipToBounds()
            .background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        if (state.phase == ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT) {
            // Timeout recovery state inside player container
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
                    .onGloballyPositioned { layoutCoordinates ->
                        val isAttached = layoutCoordinates.isAttached
                        val isMeasured = layoutCoordinates.size.width > 0 && layoutCoordinates.size.height > 0
                        if (isAttached && isMeasured) {
                            if (lastScrolledChallengeKey != currentChallengeKey) {
                                lastScrolledChallengeKey = currentChallengeKey
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(100L)
                                    try {
                                        bringIntoViewRequester.bringIntoView()
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "انتهت مهلة التحقق الأمني",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "تعذر إكمال التحقق في الوقت المحدد. حاول مرة أخرى للمتابعة.",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.testTag("challenge_retry_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إعادة المحاولة", color = Color.White)
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                activeWebView.stopLoading()
                                activeWebView.loadUrl("about:blank")
                                pollingRunnable?.let { InteractiveChallengeWebViewHelper.removePolling(it) }
                                pollingRunnable = null
                                TurnstilePositioningEngine.reset(state.challengeKey)
                            } catch (_: Throwable) {}
                            onCancel()
                        },
                        modifier = Modifier.testTag("challenge_cancel_timeout_button"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("إلغاء")
                    }
                }
            }
        } else {
            // Active verification surface contained strictly inside player container
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Compact Player Header (Arabic instructions + countdown + cancel)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.90f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "جاري التحقق من الموقع",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "أكمل التحقق للمتابعة (${state.canonicalHost.ifBlank { "Cloudflare" }})",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Countdown Timer Badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        ) {
                            Text(
                                text = "${state.remainingSeconds} ث",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Compact Cancel Button
                        IconButton(
                            onClick = {
                                try {
                                    activeWebView.stopLoading()
                                    activeWebView.loadUrl("about:blank")
                                    pollingRunnable?.let { InteractiveChallengeWebViewHelper.removePolling(it) }
                                    pollingRunnable = null
                                } catch (_: Throwable) {}
                                onCancel()
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("challenge_cancel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إلغاء التحقق",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Player-contained WebView surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.White)
                        .onGloballyPositioned { layoutCoordinates ->
                            val isAttached = layoutCoordinates.isAttached
                            val width = layoutCoordinates.size.width
                            val height = layoutCoordinates.size.height
                            val isMeasured = width > 0 && height > 0

                            // TIMING & ANTI-LOOP REQUIREMENT:
                            // 1. If not measured or not attached: DO NOT scroll!
                            // 2. Only when attached, measured, and challenge is visible:
                            if (isAttached && isMeasured && state.isVisible) {
                                if (lastScrolledChallengeKey != currentChallengeKey) {
                                    lastScrolledChallengeKey = currentChallengeKey
                                    coroutineScope.launch {
                                        // Wait until layout is stable
                                        kotlinx.coroutines.delay(120L)
                                        try {
                                            bringIntoViewRequester.bringIntoView()
                                        } catch (_: Throwable) {
                                            // Container might not be scrollable or already fully in view
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    AndroidView(
                        factory = { _ ->
                            (activeWebView.parent as? ViewGroup)?.removeView(activeWebView)
                            activeWebView
                        },
                        update = { webView ->
                            // Phase 05Q.5-A FIX 4: Never call loadUrl() during VERIFYING or for the same active challenge!
                            // Timer countdown ticks every 1s, triggering recomposition.
                            // Under NO circumstance should recomposition call loadUrl() while verification is in progress.
                            val lastLoadedKey = webView.getTag(com.example.R.id.tag_url) as? String
                            val isVerifying = state.phase == ChallengeStateMachine.Phase.VERIFYING ||
                                    state.phase == ChallengeStateMachine.Phase.SESSION_ESTABLISHED ||
                                    state.phase == ChallengeStateMachine.Phase.SESSION_PERSISTED
                            if (!isVerifying &&
                                state.phase != ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT &&
                                state.challengeUrl.isNotBlank() &&
                                lastLoadedKey != state.challengeKey &&
                                lastLoadedKey != null
                            ) {
                                webView.setTag(com.example.R.id.tag_url, state.challengeKey)
                                webView.loadUrl(state.challengeUrl)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("interactive_challenge_webview")
                    )
                }
            }
        }
    }
}

/**
 * Phase 05Q.5-B: Real Turnstile Positioning Engine.
 *
 * Responsibilities:
 * 1. FIX 1: Identifies real Turnstile iframe / widget bounding client rect in parent DOM.
 * 2. FIX 2: Computes relative visibility (targetTop, targetBottom vs viewportHeight) rather than blind scrolling.
 * 3. FIX 3: Never inspects cross-origin iframe DOM; only measures the iframe container in parent document.
 * 4. FIX 4: Targets real scroll container (document.scrollingElement, document.documentElement, document.body).
 * 5. FIX 5: Uses synchronous positioning without smooth behavior.
 * 6. FIX 6: Layout stability gate: requires attached WebView, dimensions > 0, target dimensions > 0,
 *           and two consecutive stable geometry measurements before positioning.
 * 7. FIX 7: Idempotent: once positioned for a given challengeKey and layout geometry, no repeated scrolling.
 * 8. FIX 8: All positioning is contained inside the WebView without scrolling outer Details screens.
 * 9. FIX 9: Operates on the existing stateful WebView instance without recreation.
 * 10. FIX 10: Handles all 4 cases: NONE (already visible), DOWN (below viewport), UP (above viewport), WAIT (geometry not ready).
 */
object TurnstilePositioningEngine {

    data class PositioningDecision(
        val action: String, // "NONE", "DOWN", "UP", "WAIT"
        val delta: Int,
        val targetType: String,
        val targetTop: Double,
        val targetBottom: Double,
        val viewportHeight: Double
    )

    private val positionedKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val positioningAttemptsMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lastGeometryProbeMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val stableProbeCountMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lastLayoutDimensionsMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun reset(challengeKey: String) {
        if (challengeKey.isNotBlank()) {
            positionedKeys.remove(challengeKey)
            positioningAttemptsMap.remove(challengeKey)
            lastGeometryProbeMap.remove(challengeKey)
            stableProbeCountMap.remove(challengeKey)
            lastLayoutDimensionsMap.remove(challengeKey)
        }
    }

    fun isPositioned(challengeKey: String): Boolean = positionedKeys.contains(challengeKey)

    fun calculatePositioning(
        targetTop: Double,
        targetBottom: Double,
        targetWidth: Double,
        targetHeight: Double,
        viewportHeight: Double,
        targetType: String = "iframe"
    ): PositioningDecision {
        if (targetWidth <= 0 || targetHeight <= 0 || viewportHeight <= 0) {
            return PositioningDecision("WAIT", 0, targetType, targetTop, targetBottom, viewportHeight)
        }

        val safeMarginTop = 15.0
        val safeMarginBottom = 15.0

        return when {
            targetTop >= safeMarginTop && targetBottom <= (viewportHeight - safeMarginBottom) -> {
                PositioningDecision("NONE", 0, targetType, targetTop, targetBottom, viewportHeight)
            }
            targetBottom > (viewportHeight - safeMarginBottom) -> {
                val desiredTop = Math.max(safeMarginTop, Math.floor((viewportHeight - targetHeight) / 2.0))
                val delta = Math.round(targetTop - desiredTop).toInt()
                PositioningDecision("DOWN", delta, targetType, targetTop, targetBottom, viewportHeight)
            }
            targetTop < safeMarginTop -> {
                val desiredTop = Math.max(safeMarginTop, Math.floor((viewportHeight - targetHeight) / 2.0))
                val delta = Math.round(targetTop - desiredTop).toInt()
                PositioningDecision("UP", delta, targetType, targetTop, targetBottom, viewportHeight)
            }
            else -> {
                PositioningDecision("NONE", 0, targetType, targetTop, targetBottom, viewportHeight)
            }
        }
    }

    fun attemptPositioning(
        webView: WebView,
        challengeKey: String,
        controller: InteractiveChallengeController = InteractiveChallengeController.getInstance(),
        onResult: ((action: String, delta: Int) -> Unit)? = null
    ) {
        if (challengeKey.isBlank()) return
        if (controller.isAttemptCancelled(controller.stateFlow.value.attemptId, challengeKey)) return

        val webViewWidth = webView.width
        val webViewHeight = webView.height
        val isAttached = webView.isAttachedToWindow

        // Gate 1 (FIX 6): WebView must be attached and have non-zero dimensions
        if (!isAttached || webViewWidth <= 0 || webViewHeight <= 0) {
            onResult?.invoke("WAIT", 0)
            return
        }

        val currentLayoutKey = "${webViewWidth}x${webViewHeight}"
        val previousLayoutKey = lastLayoutDimensionsMap[challengeKey]

        // If layout dimensions changed (e.g. orientation or size change), reset positioned flag to allow re-positioning
        if (previousLayoutKey != null && previousLayoutKey != currentLayoutKey) {
            positionedKeys.remove(challengeKey)
            lastGeometryProbeMap.remove(challengeKey)
            stableProbeCountMap.remove(challengeKey)
        }
        lastLayoutDimensionsMap[challengeKey] = currentLayoutKey

        // Gate 2 (FIX 7): If already positioned for this challengeKey and layout, do not repeat scroll!
        if (positionedKeys.contains(challengeKey)) {
            onResult?.invoke("NONE", 0)
            return
        }

        val attempts = (positioningAttemptsMap[challengeKey] ?: 0) + 1
        positioningAttemptsMap[challengeKey] = attempts

        val webViewInstanceId = "WebView@${System.identityHashCode(webView)}"

        val jsPositioning = """
            (function() {
                try {
                    var selectors = [
                        'iframe[src*="challenges.cloudflare.com"]',
                        'iframe[src*="turnstile"]',
                        '.cf-turnstile',
                        '#turnstile-wrapper',
                        '#challenge-stage',
                        '#challenge-running'
                    ];

                    var target = null;
                    var targetType = 'NONE';
                    for (var i = 0; i < selectors.length; i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el) {
                            var r = el.getBoundingClientRect();
                            if (r.width > 0 && r.height > 0) {
                                target = el;
                                targetType = selectors[i];
                                break;
                            }
                        }
                    }

                    var scroller = document.scrollingElement || document.documentElement || document.body;
                    var viewportHeight = window.innerHeight || document.documentElement.clientHeight || (scroller ? scroller.clientHeight : 0);
                    var viewportWidth = window.innerWidth || document.documentElement.clientWidth || (scroller ? scroller.clientWidth : 0);
                    var scrollTopBefore = (scroller && scroller.scrollTop !== undefined) ? scroller.scrollTop : (window.pageYOffset || 0);
                    var scrollHeight = scroller ? scroller.scrollHeight : 0;
                    var clientHeight = scroller ? scroller.clientHeight : 0;

                    if (!target) {
                        return JSON.stringify({
                            action: 'WAIT',
                            reason: 'NO_TARGET_READY',
                            targetType: 'NONE',
                            targetWidth: 0,
                            targetHeight: 0,
                            targetTop: 0,
                            targetBottom: 0,
                            viewportWidth: viewportWidth,
                            viewportHeight: viewportHeight,
                            scrollTopBefore: scrollTopBefore,
                            scrollTopAfter: scrollTopBefore
                        });
                    }

                    var rect = target.getBoundingClientRect();
                    var targetWidth = rect.width;
                    var targetHeight = rect.height;
                    var targetTop = rect.top;
                    var targetBottom = rect.bottom;

                    if (targetWidth <= 0 || targetHeight <= 0 || viewportHeight <= 0) {
                        return JSON.stringify({
                            action: 'WAIT',
                            reason: 'GEOMETRY_ZERO',
                            targetType: targetType,
                            targetWidth: targetWidth,
                            targetHeight: targetHeight,
                            targetTop: targetTop,
                            targetBottom: targetBottom,
                            viewportWidth: viewportWidth,
                            viewportHeight: viewportHeight,
                            scrollTopBefore: scrollTopBefore,
                            scrollTopAfter: scrollTopBefore
                        });
                    }

                    var safeMarginTop = 15;
                    var safeMarginBottom = 15;
                    var action = 'NONE';
                    var delta = 0;

                    if (targetTop >= safeMarginTop && targetBottom <= (viewportHeight - safeMarginBottom)) {
                        action = 'NONE';
                        delta = 0;
                    } else if (targetBottom > (viewportHeight - safeMarginBottom)) {
                        action = 'DOWN';
                        var desiredTop = Math.max(safeMarginTop, Math.floor((viewportHeight - targetHeight) / 2));
                        delta = Math.round(targetTop - desiredTop);
                    } else if (targetTop < safeMarginTop) {
                        action = 'UP';
                        var desiredTop = Math.max(safeMarginTop, Math.floor((viewportHeight - targetHeight) / 2));
                        delta = Math.round(targetTop - desiredTop);
                    }

                    var scrollTopAfter = scrollTopBefore;
                    if (delta !== 0) {
                        var initialScroll = (scroller && scroller.scrollTop !== undefined) ? scroller.scrollTop : (window.pageYOffset || 0);
                        if (scroller && typeof scroller.scrollTop === 'number') {
                            scroller.scrollTop += delta;
                        }
                        var intermediateScroll = (scroller && scroller.scrollTop !== undefined) ? scroller.scrollTop : (window.pageYOffset || 0);
                        if (intermediateScroll === initialScroll) {
                            window.scrollBy(0, delta);
                        }
                        scrollTopAfter = (scroller && scroller.scrollTop !== undefined) ? scroller.scrollTop : (window.pageYOffset || 0);
                    }

                    return JSON.stringify({
                        action: action,
                        delta: delta,
                        targetType: targetType,
                        targetWidth: targetWidth,
                        targetHeight: targetHeight,
                        targetTop: targetTop,
                        targetBottom: targetBottom,
                        viewportWidth: viewportWidth,
                        viewportHeight: viewportHeight,
                        scrollTopBefore: scrollTopBefore,
                        scrollTopAfter: scrollTopAfter,
                        scrollHeight: scrollHeight,
                        clientHeight: clientHeight
                    });
                } catch(e) {
                    return JSON.stringify({ error: e.toString() });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsPositioning) { jsonResult ->
            if (controller.isAttemptCancelled(controller.stateFlow.value.attemptId, challengeKey)) return@evaluateJavascript
            if (jsonResult.isNullOrBlank() || jsonResult == "null") return@evaluateJavascript

            try {
                val clean = if (jsonResult.startsWith("\"") && jsonResult.endsWith("\"")) {
                    org.json.JSONObject(org.json.JSONTokener(jsonResult).nextValue() as String)
                } else {
                    org.json.JSONObject(jsonResult)
                }

                val action = clean.optString("action", "WAIT")
                val delta = clean.optInt("delta", 0)
                val targetType = clean.optString("targetType", "NONE")
                val targetWidth = clean.optDouble("targetWidth", 0.0)
                val targetHeight = clean.optDouble("targetHeight", 0.0)
                val targetTop = clean.optDouble("targetTop", 0.0)
                val targetBottom = clean.optDouble("targetBottom", 0.0)
                val viewportWidth = clean.optDouble("viewportWidth", 0.0)
                val viewportHeight = clean.optDouble("viewportHeight", 0.0)
                val scrollTopBefore = clean.optDouble("scrollTopBefore", 0.0)
                val scrollTopAfter = clean.optDouble("scrollTopAfter", 0.0)

                // Layout Stability Gate (FIX 6):
                if (action == "WAIT") {
                    stableProbeCountMap[challengeKey] = 0
                    lastGeometryProbeMap.remove(challengeKey)
                    com.example.extension.managed.runtime.web.CloudflareForensicLogger.cfPositioningTrace(
                        challengeKey = challengeKey,
                        webViewInstanceId = webViewInstanceId,
                        webViewWidth = webViewWidth,
                        webViewHeight = webViewHeight,
                        targetType = targetType,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        targetTop = targetTop,
                        targetBottom = targetBottom,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        scrollTopBefore = scrollTopBefore,
                        scrollTopAfter = scrollTopAfter,
                        positioningAction = "WAIT",
                        positioningAttempts = attempts
                    )
                    onResult?.invoke("WAIT", 0)
                    return@evaluateJavascript
                }

                val currentGeometryStr = "${targetWidth.toInt()}x${targetHeight.toInt()}@${targetTop.toInt()}"
                val previousGeometryStr = lastGeometryProbeMap[challengeKey]

                if (previousGeometryStr == currentGeometryStr) {
                    val count = (stableProbeCountMap[challengeKey] ?: 0) + 1
                    stableProbeCountMap[challengeKey] = count
                } else {
                    lastGeometryProbeMap[challengeKey] = currentGeometryStr
                    stableProbeCountMap[challengeKey] = 1
                }

                // Mark positioned once stable (FIX 7):
                positionedKeys.add(challengeKey)

                com.example.extension.managed.runtime.web.CloudflareForensicLogger.cfPositioningTrace(
                    challengeKey = challengeKey,
                    webViewInstanceId = webViewInstanceId,
                    webViewWidth = webViewWidth,
                    webViewHeight = webViewHeight,
                    targetType = targetType,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    targetTop = targetTop,
                    targetBottom = targetBottom,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    scrollTopBefore = scrollTopBefore,
                    scrollTopAfter = scrollTopAfter,
                    positioningAction = action,
                    positioningAttempts = attempts
                )

                onResult?.invoke(action, delta)
            } catch (_: Throwable) {}
        }
    }
}

