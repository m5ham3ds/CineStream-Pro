package com.example.extension.managed.runtime.web

import android.util.Log

/**
 * Phase 05Q Forensic Trace Logger.
 *
 * Implements strict structured forensic traces [CF-01] through [CF-16].
 * CRITICAL HARD SECURITY RULE:
 * NEVER prints the plaintext value of cf_clearance, authorization tokens, or sensitive cookies.
 * Only logs presence status (present=true / present=false).
 */
object CloudflareForensicLogger {

    private const val TAG = "ProtectedWebRuntime"

    @Volatile
    var testLogAppender: ((tag: String, message: String) -> Unit)? = null

    private fun emit(tag: String, message: String) {
        testLogAppender?.invoke(tag, message)
        try {
            Log.i(TAG, "[$tag] $message")
        } catch (_: Throwable) {
            println("DIAG: [$TAG] [$tag] $message")
        }
    }

    /**
     * Phase 05Q.3-B Structured Diagnostics.
     * Logs exact lifecycle events:
     * [CF] challenge_detected, webview_attached, waiting_for_user, verifying,
     * success_detected, session_established, session_persisted, webview_hidden, retry_same_candidate.
     */
    fun cfTrace(event: String, host: String = "", candidateId: String = "", state: String = "") {
        val extra = listOfNotNull(
            host.takeIf { it.isNotBlank() }?.let { "host='$it'" },
            candidateId.takeIf { it.isNotBlank() }?.let { "candidate='$it'" },
            state.takeIf { it.isNotBlank() }?.let { "state='$it'" }
        ).joinToString(", ")
        emit("CF", "$event${if (extra.isNotBlank()) " ($extra)" else ""}")
    }

    fun cf01_siteIdentified(siteKey: String, canonicalHost: String) {
        emit("CF-01", "Site identified: siteKey='$siteKey', host='$canonicalHost'")
    }

    fun cf02_sessionLookup(siteKey: String, canonicalHost: String) {
        emit("CF-02", "Session lookup for siteKey='$siteKey' ($canonicalHost)")
    }

    fun cf03_sessionFound(siteKey: String, state: ProtectedSessionState, cookieCount: Int, hasCfClearance: Boolean) {
        emit("CF-03", "Session found: siteKey='$siteKey', state=$state, cookiesCount=$cookieCount, cf_clearance present=$hasCfClearance")
    }

    fun cf03_sessionNotFound(siteKey: String) {
        emit("CF-03", "Session not found: siteKey='$siteKey' -> state=NONE")
    }

    fun cf04_webEngineCreated(reused: Boolean) {
        emit("CF-04", "WebEngine ${if (reused) "reused" else "created"}")
    }

    fun cf05_cookiesRestored(siteKey: String, cookieCount: Int, hasCfClearance: Boolean) {
        emit("CF-05", "Cookies restored for siteKey='$siteKey': count=$cookieCount, cf_clearance present=$hasCfClearance")
    }

    fun cf06_navigationStarted(url: String) {
        emit("CF-06", "Navigation started: url='$url'")
    }

    fun cf07_cloudflareDetected(isChallenge: Boolean, details: String) {
        emit("CF-07", "Cloudflare ${if (isChallenge) "DETECTED" else "NOT_DETECTED"}: $details")
    }

    fun cf08_challengeState(phase: String) {
        emit("CF-08", "Challenge state: phase='$phase'")
    }

    fun cf09_browserVerification(method: String, status: String) {
        emit("CF-09", "Browser verification: method='$method', status='$status'")
    }

    fun cf10_sessionEstablished(siteKey: String, canonicalHost: String, hasCfClearance: Boolean) {
        emit("CF-10", "Session established: siteKey='$siteKey', host='$canonicalHost', cf_clearance present=$hasCfClearance")
    }

    fun cf11_sessionPersisted(siteKey: String, canonicalHost: String) {
        emit("CF-11", "Session persisted: siteKey='$siteKey' ($canonicalHost)")
    }

    fun cf12_extractionStarted(targetUrl: String) {
        emit("CF-12", "Extraction started: targetUrl='$targetUrl'")
    }

    fun cf13_directMediaUrl(url: String, protocol: String) {
        // Safe URL clean (strip sensitive params if any)
        val cleanUrl = if (url.length > 120) url.substring(0, 120) + "..." else url
        emit("CF-13", "Direct media URL: url='$cleanUrl', protocol='$protocol'")
    }

    fun cf14_playerHandoff(serverName: String, mediaUrl: String) {
        val cleanUrl = if (mediaUrl.length > 80) mediaUrl.substring(0, 80) + "..." else mediaUrl
        emit("CF-14", "PlayerHandoff: server='$serverName', url='$cleanUrl'")
    }

    fun cf15_exoPlayerReady(mediaUrl: String) {
        emit("CF-15", "ExoPlayer READY")
    }

    fun cf16_exoPlayerPlaying(mediaUrl: String) {
        emit("CF-16", "ExoPlayer PLAYING")
    }

    // =========================================================================
    // Phase 05Q.1 Interactive Challenge Recovery Structured Traces [CF-UI-01..13]
    // =========================================================================

    fun cfUi01_challengeDetected(siteKey: String, host: String, reason: String) {
        emit("CF-UI-01", "Challenge detected: siteKey='$siteKey', host='$host', reason='$reason'")
    }

    fun cfUi02_webViewOpened(siteKey: String, targetUrl: String) {
        val cleanUrl = if (targetUrl.length > 100) targetUrl.take(100) + "..." else targetUrl
        emit("CF-UI-02", "Challenge WebView opened: siteKey='$siteKey', targetUrl='$cleanUrl'")
    }

    fun cfUi03_siteLoaded(url: String) {
        val cleanUrl = if (url.length > 100) url.take(100) + "..." else url
        emit("CF-UI-03", "Challenge site loaded: url='$cleanUrl'")
    }

    fun cfUi04_userVerificationStarted(siteKey: String) {
        emit("CF-UI-04", "User verification started: siteKey='$siteKey'")
    }

    fun cfUi05_userVerificationCompleted(siteKey: String, durationMs: Long) {
        emit("CF-UI-05", "User verification completed: siteKey='$siteKey', durationMs=$durationMs")
    }

    fun cfUi06_clearanceCookieCaptured(siteKey: String, cookieCount: Int, hasCfClearance: Boolean) {
        emit("CF-UI-06", "Clearance cookie captured: siteKey='$siteKey', cookieCount=$cookieCount, hasCfClearance=$hasCfClearance")
    }

    fun cfUi07_sessionStored(siteKey: String, host: String, ttlMs: Long) {
        emit("CF-UI-07", "Session stored: siteKey='$siteKey', host='$host', ttlMs=$ttlMs")
    }

    fun cfUi08_webViewClosed(siteKey: String, reason: String) {
        emit("CF-UI-08", "Challenge WebView closed: siteKey='$siteKey', reason='$reason'")
    }

    fun cfUi09_extractionResumed(siteKey: String, mediaId: String) {
        emit("CF-UI-09", "Extraction resumed: siteKey='$siteKey', mediaId='$mediaId'")
    }

    fun cfUi10_playableUrlObtained(url: String, protocol: String) {
        val cleanUrl = if (url.length > 100) url.take(100) + "..." else url
        emit("CF-UI-10", "Playable URL obtained: url='$cleanUrl', protocol='$protocol'")
    }

    fun cfUi11_playerHandoffCompleted(serverName: String) {
        emit("CF-UI-11", "Player handoff completed: server='$serverName'")
    }

    fun cfUi12_exoPlayerReady() {
        emit("CF-UI-12", "ExoPlayer state: STATE_READY")
    }

    fun cfUi13_exoPlayerPlaying() {
        emit("CF-UI-13", "ExoPlayer state: STATE_PLAYING")
    }

    fun cfUi14_recoveryLimitReached(siteKey: String, host: String) {
        emit("CF-UI-14", "Recovery attempt limit reached for siteKey='$siteKey' ($host). Halting recovery loop.")
    }

    fun cfUi15_retrySameCandidate(siteKey: String, targetUrl: String) {
        val cleanUrl = if (targetUrl.length > 100) targetUrl.take(100) + "..." else targetUrl
        emit("CF-UI-15", "Retrying extraction on same candidate: siteKey='$siteKey', url='$cleanUrl'")
    }

    fun cfUi16_recoveryCancelled(siteKey: String) {
        emit("CF-UI-16", "Interactive challenge cancelled by user: siteKey='$siteKey'")
    }

    fun cfUi17_candidateCancelled(candidateId: String) {
        emit("CF-UI-17", "Candidate cancelled: candidateId='$candidateId'. Advancing to next candidate.")
    }

    fun cfUi18_unverifiedExit(siteKey: String, reason: String) {
        emit("CF-UI-18", "Challenge exited without verification: siteKey='$siteKey', reason='$reason'")
    }

    fun cfUi19_attemptDiscarded(siteKey: String, attemptId: Long) {
        emit("CF-UI-19", "Challenge attempt discarded: siteKey='$siteKey', attemptId=$attemptId")
    }

    fun cfUi20_newAttemptStarted(siteKey: String, attemptId: Long) {
        emit("CF-UI-20", "New challenge attempt started: siteKey='$siteKey', attemptId=$attemptId")
    }

    /**
     * Phase 05Q.5-B: Structured Real-Device Turnstile Positioning Trace.
     * Records exact geometry and actions without logging any sensitive cookies, tokens, or plaintext HTML.
     */
    fun cfPositioningTrace(
        challengeKey: String,
        webViewInstanceId: String,
        webViewWidth: Int,
        webViewHeight: Int,
        targetType: String,
        targetWidth: Double,
        targetHeight: Double,
        targetTop: Double,
        targetBottom: Double,
        viewportWidth: Double,
        viewportHeight: Double,
        scrollTopBefore: Double,
        scrollTopAfter: Double,
        positioningAction: String,
        positioningAttempts: Int,
        webViewRecreated: Boolean = false,
        loadUrlDuringPositioning: Boolean = false,
        verifyingRegression: Boolean = false
    ) {
        emit(
            "CF-POS",
            "CHALLENGE_KEY='$challengeKey', " +
            "WEBVIEW_INSTANCE_ID='$webViewInstanceId', " +
            "WEBVIEW_WIDTH=$webViewWidth, " +
            "WEBVIEW_HEIGHT=$webViewHeight, " +
            "TARGET_TYPE='$targetType', " +
            "TARGET_WIDTH=${targetWidth.toInt()}, " +
            "TARGET_HEIGHT=${targetHeight.toInt()}, " +
            "TARGET_TOP=${targetTop.toInt()}, " +
            "TARGET_BOTTOM=${targetBottom.toInt()}, " +
            "VIEWPORT_WIDTH=${viewportWidth.toInt()}, " +
            "VIEWPORT_HEIGHT=${viewportHeight.toInt()}, " +
            "SCROLL_TOP_BEFORE=${scrollTopBefore.toInt()}, " +
            "SCROLL_TOP_AFTER=${scrollTopAfter.toInt()}, " +
            "POSITIONING_ACTION=$positioningAction, " +
            "POSITIONING_ATTEMPTS=$positioningAttempts, " +
            "WEBVIEW_RECREATED=${if (webViewRecreated) "YES" else "NO"}, " +
            "LOAD_URL_DURING_POSITIONING=${if (loadUrlDuringPositioning) "YES" else "NO"}, " +
            "VERIFYING_REGRESSION=${if (verifyingRegression) "YES" else "NO"}"
        )
    }
}
