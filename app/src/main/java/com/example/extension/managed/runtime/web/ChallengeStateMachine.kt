package com.example.extension.managed.runtime.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 05Q Challenge State Machine.
 *
 * Coordinates the deterministic lifecycle of a web page load subject to verification:
 * NO_CHALLENGE -> PAGE_LOADING -> CHALLENGE_DETECTED -> WAITING_FOR_BROWSER_VERIFICATION -> PAGE_VERIFIED -> EXTRACTION.
 *
 * Terminal failure states:
 * - CHALLENGE_FAILED
 * - CHALLENGE_TIMEOUT
 * - CHALLENGE_REQUIRES_INTERACTION
 */
class ChallengeStateMachine(
    private val siteKey: String,
    private val canonicalHost: String
) {
    enum class Phase {
        // Phase 05Q Legacy Phases
        NO_CHALLENGE,
        PAGE_LOADING,
        CHALLENGE_DETECTED,
        WAITING_FOR_BROWSER_VERIFICATION,
        PAGE_VERIFIED,
        EXTRACTION,
        CHALLENGE_FAILED,
        CHALLENGE_TIMEOUT,
        CHALLENGE_REQUIRES_INTERACTION,

        // Phase 05Q.1 Interactive Recovery Pipeline Phases
        IDLE,
        INTERACTIVE_RECOVERY_REQUIRED,
        WEBVIEW_VISIBLE,
        WAITING_FOR_USER,
        VERIFYING,
        SESSION_ESTABLISHED,
        SESSION_PERSISTED,
        WEBVIEW_HIDDEN,
        EXTRACTION_RETRY,
        PLAYBACK,
        USER_CANCELLED,

        // Phase 05Q.3-C Cancellation Pipeline Phases
        CANCEL_REQUESTED,
        CHALLENGE_CANCELLED,

        // Phase 05Q.3-D Unverified Exit & Reset Phases
        UNVERIFIED_EXIT,
        ATTEMPT_DISCARDED
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val currentUrl: String = "",
        val pageTitle: String = "",
        val isVerified: Boolean = false,
        val requiresInteraction: Boolean = false,
        val errorMessage: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    val currentPhase: Phase
        get() = _stateFlow.value.phase

    val isTerminatedOrCancelled: Boolean
        get() = currentPhase == Phase.CANCEL_REQUESTED ||
                currentPhase == Phase.CHALLENGE_CANCELLED ||
                currentPhase == Phase.USER_CANCELLED ||
                currentPhase == Phase.UNVERIFIED_EXIT ||
                currentPhase == Phase.ATTEMPT_DISCARDED

    fun onIdle() {
        _stateFlow.value = State(phase = Phase.IDLE, timestamp = System.currentTimeMillis())
    }

    fun startPageLoading(url: String) {
        if (isTerminatedOrCancelled) {
            return
        }
        if (currentPhase == Phase.VERIFYING || currentPhase == Phase.SESSION_ESTABLISHED || currentPhase == Phase.SESSION_PERSISTED) {
            // Do not regress phase during active verification or session persistence on internal reloads
            _stateFlow.value = _stateFlow.value.copy(currentUrl = url)
            return
        }
        _stateFlow.value = State(
            phase = Phase.PAGE_LOADING,
            currentUrl = url,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf06_navigationStarted(url)
        CloudflareForensicLogger.cf08_challengeState(Phase.PAGE_LOADING.name)
    }

    fun onChallengeDetected(detection: CloudflareChallengeDetector.DetectionResult) {
        // Phase 05Q.3-C & 05Q.3-D: Ignore late detection if cancelled or unverified exit
        if (isTerminatedOrCancelled) {
            return
        }
        // Phase 05Q.3-B Hard Rule: Forbidden to regress from VERIFYING to CHALLENGE_DETECTED on internal navigations
        if (currentPhase == Phase.VERIFYING || currentPhase == Phase.SESSION_ESTABLISHED || currentPhase == Phase.SESSION_PERSISTED) {
            return
        }

        val newPhase = if (detection.requiresInteraction) {
            Phase.INTERACTIVE_RECOVERY_REQUIRED
        } else {
            Phase.CHALLENGE_DETECTED
        }

        _stateFlow.value = _stateFlow.value.copy(
            phase = newPhase,
            requiresInteraction = detection.requiresInteraction,
            errorMessage = detection.details,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf07_cloudflareDetected(true, detection.details)
        CloudflareForensicLogger.cfUi01_challengeDetected(siteKey, canonicalHost, detection.details)
        CloudflareForensicLogger.cf08_challengeState(newPhase.name)

        if (newPhase == Phase.CHALLENGE_DETECTED) {
            transitionToWaitingVerification()
        }
    }

    fun onInteractiveRecoveryRequired(reason: String) {
        if (isTerminatedOrCancelled) {
            return
        }
        if (currentPhase == Phase.VERIFYING || currentPhase == Phase.SESSION_ESTABLISHED || currentPhase == Phase.SESSION_PERSISTED) {
            return
        }

        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.INTERACTIVE_RECOVERY_REQUIRED,
            requiresInteraction = true,
            errorMessage = reason,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cfUi01_challengeDetected(siteKey, canonicalHost, reason)
        CloudflareForensicLogger.cf08_challengeState(Phase.INTERACTIVE_RECOVERY_REQUIRED.name)
    }

    fun onWebViewVisible(challengeUrl: String) {
        if (isTerminatedOrCancelled) {
            return
        }
        if (currentPhase == Phase.VERIFYING || currentPhase == Phase.SESSION_ESTABLISHED || currentPhase == Phase.SESSION_PERSISTED) {
            return
        }

        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.WEBVIEW_VISIBLE,
            currentUrl = challengeUrl,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cfUi02_webViewOpened(siteKey, challengeUrl)
        CloudflareForensicLogger.cf08_challengeState(Phase.WEBVIEW_VISIBLE.name)
    }

    fun onWaitingForUser() {
        if (isTerminatedOrCancelled) {
            return
        }
        if (currentPhase == Phase.VERIFYING || currentPhase == Phase.SESSION_ESTABLISHED || currentPhase == Phase.SESSION_PERSISTED) {
            return
        }

        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.WAITING_FOR_USER,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf08_challengeState(Phase.WAITING_FOR_USER.name)
    }

    fun onVerifying() {
        // Phase 05Q.3-C & 05Q.3-D: Cancel and unverified exit take precedence over Verifying
        if (isTerminatedOrCancelled) {
            return
        }
        if (currentPhase == Phase.SESSION_ESTABLISHED || currentPhase == Phase.SESSION_PERSISTED) {
            return
        }

        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.VERIFYING,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cfUi04_userVerificationStarted(siteKey)
        CloudflareForensicLogger.cf08_challengeState(Phase.VERIFYING.name)
    }

    fun onSessionEstablished(host: String) {
        // Phase 05Q.3-C & 05Q.3-D: CANCEL / UNVERIFIED EXIT MUST WIN OVER SUCCESS!
        if (isTerminatedOrCancelled) {
            return
        }
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.SESSION_ESTABLISHED,
            isVerified = true,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf10_sessionEstablished(siteKey, host, true)
        CloudflareForensicLogger.cf08_challengeState(Phase.SESSION_ESTABLISHED.name)
    }

    fun onSessionPersisted(host: String, hasClearance: Boolean) {
        // Phase 05Q.3-C & 05Q.3-D: DO NOT PERSIST SESSION ON CANCEL OR UNVERIFIED EXIT!
        if (isTerminatedOrCancelled) {
            return
        }
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.SESSION_PERSISTED,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf11_sessionPersisted(siteKey, host)
        CloudflareForensicLogger.cfUi07_sessionStored(siteKey, host, 86400000L)
        CloudflareForensicLogger.cf08_challengeState(Phase.SESSION_PERSISTED.name)
    }

    fun onWebViewHidden() {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.WEBVIEW_HIDDEN,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cfUi08_webViewClosed(siteKey, "Verification complete")
        CloudflareForensicLogger.cf08_challengeState(Phase.WEBVIEW_HIDDEN.name)
    }

    fun onExtractionRetry(targetUrl: String) {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.EXTRACTION_RETRY,
            currentUrl = targetUrl,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cfUi09_extractionResumed(siteKey, targetUrl)
        CloudflareForensicLogger.cf08_challengeState(Phase.EXTRACTION_RETRY.name)
    }

    fun onPlayback() {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.PLAYBACK,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf08_challengeState(Phase.PLAYBACK.name)
    }

    /**
     * Phase 05Q.3-C: Immediate cancel request transition.
     * Higher priority than uncommitted success.
     */
    fun onCancelRequested() {
        if (currentPhase == Phase.SESSION_PERSISTED) {
            return
        }
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.CANCEL_REQUESTED,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf08_challengeState(Phase.CANCEL_REQUESTED.name)
    }

    /**
     * Phase 05Q.3-C: Transition to CHALLENGE_CANCELLED.
     */
    fun onChallengeCancelled(reason: String = "USER_CANCELLED") {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.CHALLENGE_CANCELLED,
            errorMessage = reason,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cfUi08_webViewClosed(siteKey, reason)
        CloudflareForensicLogger.cf08_challengeState(Phase.CHALLENGE_CANCELLED.name)
    }

    fun onUserCancelled() {
        onCancelRequested()
        onChallengeCancelled("USER_CANCELLED")
    }

    /**
     * Phase 05Q.3-D: Unverified Exit.
     * Triggered when user navigates away, presses Back, or closes screen without completing verification.
     * HARD RULE (Section 7): If SESSION_PERSISTED already committed, it is VERIFIED; ignore exit.
     */
    fun onUnverifiedExit(reason: String = "USER_EXIT") {
        if (currentPhase == Phase.SESSION_PERSISTED) {
            // Success has finality! Do not invalidate already verified session
            return
        }
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.UNVERIFIED_EXIT,
            errorMessage = reason,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cfUi18_unverifiedExit(siteKey, reason)
        CloudflareForensicLogger.cf08_challengeState(Phase.UNVERIFIED_EXIT.name)
        CloudflareForensicLogger.cfTrace("user_exit", host = canonicalHost, state = currentPhase.name)
        CloudflareForensicLogger.cfTrace("unverified_exit", host = canonicalHost)
    }

    fun onAttemptDiscarded(attemptId: Long = 0L) {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.ATTEMPT_DISCARDED,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cfUi19_attemptDiscarded(siteKey, attemptId)
        CloudflareForensicLogger.cf08_challengeState(Phase.ATTEMPT_DISCARDED.name)
        CloudflareForensicLogger.cfTrace("challenge_attempt_discarded")
    }

    fun transitionToWaitingVerification() {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.WAITING_FOR_BROWSER_VERIFICATION,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf08_challengeState(Phase.WAITING_FOR_BROWSER_VERIFICATION.name)
        CloudflareForensicLogger.cf09_browserVerification(method = "natural", status = "WAITING")
    }

    fun onPageVerified(verifiedUrl: String, title: String = "") {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.PAGE_VERIFIED,
            currentUrl = verifiedUrl,
            pageTitle = title,
            isVerified = true,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf08_challengeState(Phase.PAGE_VERIFIED.name)
        CloudflareForensicLogger.cf09_browserVerification(method = "natural", status = "VERIFIED")
    }

    fun onExtractionStarted() {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.EXTRACTION,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf08_challengeState(Phase.EXTRACTION.name)
    }

    fun onNoChallenge(url: String, title: String = "") {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.PAGE_VERIFIED,
            currentUrl = url,
            pageTitle = title,
            isVerified = true,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf07_cloudflareDetected(false, "Clean navigation: no challenge encountered")
        CloudflareForensicLogger.cf08_challengeState(Phase.PAGE_VERIFIED.name)
    }

    fun onTimeout(timeoutMs: Long) {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.CHALLENGE_TIMEOUT,
            errorMessage = "Timed out after ${timeoutMs}ms",
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf08_challengeState(Phase.CHALLENGE_TIMEOUT.name)
    }

    fun onFailed(reason: String) {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.CHALLENGE_FAILED,
            errorMessage = reason,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf08_challengeState(Phase.CHALLENGE_FAILED.name)
    }

    fun onInteractionRequired() {
        _stateFlow.value = _stateFlow.value.copy(
            phase = Phase.CHALLENGE_REQUIRES_INTERACTION,
            requiresInteraction = true,
            timestamp = System.currentTimeMillis()
        )
        CloudflareForensicLogger.cf08_challengeState(Phase.CHALLENGE_REQUIRES_INTERACTION.name)
    }
}
