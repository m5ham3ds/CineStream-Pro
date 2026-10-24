package com.example.extension.managed.runtime.web

import android.os.Handler
import android.os.Looper
import com.example.extension.managed.model.ContentType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 05Q.1: Interactive Challenge Controller.
 *
 * Coordinates the natural interactive Cloudflare recovery flow when automated
 * background extraction triggers a verification challenge.
 *
 * Invariants:
 * 1. Single active challenge lock: Pauses scrapers and guarantees one active challenge per site/session.
 * 2. Original Context Preservation: Retains full playback/extraction context (mediaId, title, contentType,
 *    season, episode, extensionId, serverId, quality) to resume automatically without user re-trigger.
 * 3. Zero Firebase calls: Completely offline / client-side lifecycle and cookie store.
 * 4. Deterministic state transitions: IDLE -> CHALLENGE_DETECTED -> INTERACTIVE_RECOVERY_REQUIRED ->
 *    WEBVIEW_VISIBLE -> WAITING_FOR_USER -> VERIFYING -> SESSION_ESTABLISHED -> SESSION_PERSISTED ->
 *    WEBVIEW_HIDDEN -> EXTRACTION_RETRY -> PLAYBACK.
 */
class InteractiveChallengeController(
    private val sessionStoreProvider: () -> PerSiteSessionStore = { PerSiteSessionStore.getInstance() }
) {
    data class ExtractionContext(
        val mediaId: String = "",
        val title: String = "",
        val originalTitle: String? = null,
        val year: String = "",
        val contentType: ContentType = ContentType.MOVIE,
        val season: Int = 1,
        val episode: Int = 1,
        val extensionId: String = "",
        val scraperKey: String = "",
        val serverId: String? = null,
        val serverName: String? = null,
        val quality: String = "Auto",
        val targetUrl: String = "",
        val script: String = "",
        val expectedOrigin: String = ""
    )

    data class UiState(
        val phase: ChallengeStateMachine.Phase = ChallengeStateMachine.Phase.IDLE,
        val isVisible: Boolean = false,
        val siteKey: String = "",
        val canonicalHost: String = "",
        val challengeUrl: String = "",
        val challengeKey: String = "",
        val attemptId: Long = 0L,
        val hadExistingValidSession: Boolean = false,
        val originalContext: ExtractionContext? = null,
        val errorMessage: String? = null,
        val remainingSeconds: Int = 60
    )

    private val _stateFlow = MutableStateFlow(UiState())
    val stateFlow: StateFlow<UiState> = _stateFlow.asStateFlow()

    private val challengeLock = Mutex()
    private var pendingDeferred: CompletableDeferred<Result<Map<String, String>>>? = null
    private var countdownJob: Job? = null
    private var stateMachine: ChallengeStateMachine? = null
    @Volatile private var activeChallengeKey: String? = null
    private var currentAttemptId: Long = 0L
    private val cancelledAttemptIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val cancelledChallengeKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var webViewCleanupCallback: (() -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        @Volatile
        private var instance: InteractiveChallengeController? = null

        fun getInstance(): InteractiveChallengeController {
            return instance ?: synchronized(this) {
                instance ?: InteractiveChallengeController().also { instance = it }
            }
        }

        fun computeChallengeKey(
            siteKey: String,
            canonicalHost: String,
            challengeUrl: String,
            candidateId: String? = null
        ): String {
            val normUrl = challengeUrl.trim().removeSuffix("/").lowercase()
            val normHost = PerSiteSessionStore.canonicalizeHost(canonicalHost.ifBlank { challengeUrl })
            val normCandidate = (candidateId ?: siteKey).trim().lowercase()
            return "$normCandidate:$normHost:$normUrl"
        }
    }

    fun registerWebViewCleanup(cleanup: () -> Unit) {
        webViewCleanupCallback = cleanup
    }

    fun unregisterWebViewCleanup() {
        webViewCleanupCallback = null
    }

    fun isAttemptCancelled(attemptId: Long = _stateFlow.value.attemptId, key: String? = null): Boolean {
        return cancelledAttemptIds.contains(attemptId) ||
                (key != null && cancelledChallengeKeys.contains(key)) ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.CANCEL_REQUESTED ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.CHALLENGE_CANCELLED ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.USER_CANCELLED ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.UNVERIFIED_EXIT ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.ATTEMPT_DISCARDED
    }

    /**
     * Initiates the interactive challenge recovery flow.
     * Pauses the scraper and displays the embedded WebView dialog inside the playback screen.
     */
    suspend fun launchInteractiveChallenge(
        siteKey: String,
        canonicalHost: String,
        challengeUrl: String,
        context: ExtractionContext,
        timeoutMs: Long = 60_000L
    ): Result<Map<String, String>> {
        val challengeKey = computeChallengeKey(siteKey, canonicalHost, challengeUrl, context.extensionId)

        // Phase 05Q.5-C: If candidate is already cancelled in current PlaybackAttempt, immediately abort!
        val isAlreadyCancelled = com.example.extension.managed.playback.PlaybackAttempt.current()?.let { attempt ->
            attempt.isCandidateCancelled(context.extensionId) || attempt.isCandidateCancelled(siteKey)
        } ?: false

        if (isAlreadyCancelled) {
            CloudflareForensicLogger.cfTrace("candidate_already_cancelled_skip", candidateId = context.extensionId, host = canonicalHost)
            return Result.failure(ProtectedWebRuntimeError.CloudflareChallengeCancelled(siteKey))
        }

        // Fast-path without lock: If identical challenge is already actively waiting or verifying, await it!
        val existingActiveDeferred = pendingDeferred
        if (activeChallengeKey == challengeKey && existingActiveDeferred != null && existingActiveDeferred.isActive) {
            val curPhase = _stateFlow.value.phase
            if (curPhase == ChallengeStateMachine.Phase.WAITING_FOR_USER ||
                curPhase == ChallengeStateMachine.Phase.VERIFYING ||
                curPhase == ChallengeStateMachine.Phase.WEBVIEW_VISIBLE) {
                return existingActiveDeferred.await()
            }
        }

        // Fast-path: If an active and valid session with clearance is already stored, reuse without challenge
        val existingSession = sessionStoreProvider().getSession(siteKey, canonicalHost)
        if (existingSession != null && existingSession.isValid() && existingSession.hasCfClearance) {
            return Result.success(existingSession.cookies)
        }

        val deferredToAwait: CompletableDeferred<Result<Map<String, String>>> = challengeLock.withLock {
            // Re-check under lock
            cancelledChallengeKeys.remove(challengeKey)

            val sessionUnderLock = sessionStoreProvider().getSession(siteKey, canonicalHost)
            if (sessionUnderLock != null && sessionUnderLock.isValid() && sessionUnderLock.hasCfClearance) {
                return Result.success(sessionUnderLock.cookies)
            }

            val curDeferred = pendingDeferred
            if (activeChallengeKey == challengeKey && curDeferred != null && curDeferred.isActive) {
                val curPhase = _stateFlow.value.phase
                if (curPhase == ChallengeStateMachine.Phase.WAITING_FOR_USER ||
                    curPhase == ChallengeStateMachine.Phase.VERIFYING ||
                    curPhase == ChallengeStateMachine.Phase.WEBVIEW_VISIBLE) {
                    return curDeferred.await()
                }
            }

            val attemptId = ++currentAttemptId
            activeChallengeKey = challengeKey

            CloudflareForensicLogger.cfUi20_newAttemptStarted(siteKey, attemptId)
            CloudflareForensicLogger.cfTrace("new_playback_attempt", host = canonicalHost, candidateId = context.extensionId)
            CloudflareForensicLogger.cfTrace("challenge_recreated", host = canonicalHost)

            // 1. Initialize State Machine
            val sm = ChallengeStateMachine(siteKey, canonicalHost)
            stateMachine = sm

            sm.onInteractiveRecoveryRequired("Interactive Cloudflare verification challenge initiated")
            sm.onWebViewVisible(challengeUrl)
            sm.onWaitingForUser()

            val deferred = CompletableDeferred<Result<Map<String, String>>>()
            pendingDeferred = deferred

            val initialSeconds = (timeoutMs / 1000).toInt().coerceAtLeast(10)
            _stateFlow.value = UiState(
                phase = ChallengeStateMachine.Phase.WAITING_FOR_USER,
                isVisible = true,
                siteKey = siteKey,
                canonicalHost = canonicalHost,
                challengeUrl = challengeUrl,
                challengeKey = challengeKey,
                attemptId = attemptId,
                hadExistingValidSession = sessionUnderLock != null && sessionUnderLock.isValid(),
                originalContext = context,
                remainingSeconds = initialSeconds
            )

            // Start countdown job
            countdownJob?.cancel()
            countdownJob = scope.launch {
                var secs = initialSeconds
                while (secs > 0 && deferred.isActive) {
                    delay(1000)
                    secs--
                    _stateFlow.value = _stateFlow.value.copy(remainingSeconds = secs)
                }
                if (secs <= 0 && deferred.isActive) {
                    onTimeout(timeoutMs)
                }
            }

            deferred
        }

        try {
            return deferredToAwait.await()
        } finally {
            countdownJob?.cancel()
            countdownJob = null
        }
    }

    fun onSiteLoaded(url: String) {
        CloudflareForensicLogger.cfUi03_siteLoaded(url)
    }

    fun onUserVerificationStarted() {
        val currentPhase = _stateFlow.value.phase
        if (currentPhase == ChallengeStateMachine.Phase.VERIFYING ||
            currentPhase == ChallengeStateMachine.Phase.SESSION_ESTABLISHED ||
            currentPhase == ChallengeStateMachine.Phase.SESSION_PERSISTED ||
            currentPhase == ChallengeStateMachine.Phase.CANCEL_REQUESTED ||
            currentPhase == ChallengeStateMachine.Phase.CHALLENGE_CANCELLED ||
            currentPhase == ChallengeStateMachine.Phase.USER_CANCELLED) {
            return
        }
        stateMachine?.onVerifying()
        _stateFlow.value = _stateFlow.value.copy(phase = ChallengeStateMachine.Phase.VERIFYING)
    }

    fun onVerificationCompleted(cookies: Map<String, String>) {
        onVerificationCompleted(_stateFlow.value.attemptId, _stateFlow.value.challengeKey, cookies)
    }

    fun onVerificationCompleted(attemptId: Long, challengeKey: String, cookies: Map<String, String>) {
        val current = _stateFlow.value
        val host = current.canonicalHost
        val siteKey = current.siteKey
        val curPhase = current.phase

        // Phase 05Q.3-C & 05Q.3-D Hard Rule (Section 6, 7 & 12): CANCEL / UNVERIFIED EXIT MUST WIN OVER SUCCESS!
        // If user cancelled, or exited unverified, or callback is from an old attempt, drop late success and do NOT persist session!
        if (attemptId != current.attemptId ||
            challengeKey != current.challengeKey ||
            curPhase == ChallengeStateMachine.Phase.CANCEL_REQUESTED ||
            curPhase == ChallengeStateMachine.Phase.CHALLENGE_CANCELLED ||
            curPhase == ChallengeStateMachine.Phase.USER_CANCELLED ||
            curPhase == ChallengeStateMachine.Phase.UNVERIFIED_EXIT ||
            curPhase == ChallengeStateMachine.Phase.ATTEMPT_DISCARDED ||
            cancelledAttemptIds.contains(attemptId) ||
            cancelledChallengeKeys.contains(challengeKey)) {
            CloudflareForensicLogger.cfTrace("late_success_ignored_after_cancel", host = host, state = curPhase.name)
            return
        }

        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        stateMachine?.onSessionEstablished(host)
        val hasClearance = cookies.containsKey("cf_clearance") && !cookies["cf_clearance"].isNullOrBlank()

        // Persist session to secure store
        val session = ProtectedSiteSession(
            siteKey = siteKey,
            canonicalHost = host,
            cookies = cookies,
            state = ProtectedSessionState.ACTIVE,
            lastValidatedAt = System.currentTimeMillis()
        )
        sessionStoreProvider().saveSession(session)

        stateMachine?.onSessionPersisted(host, hasClearance)
        stateMachine?.onWebViewHidden()

        val targetUrl = current.originalContext?.targetUrl ?: current.challengeUrl
        stateMachine?.onExtractionRetry(targetUrl)

        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.WEBVIEW_HIDDEN,
            isVisible = false
        )

        pendingDeferred?.complete(Result.success(cookies))
    }

    fun onVerificationFailed(reason: String) {
        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        stateMachine?.onFailed(reason)
        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.CHALLENGE_FAILED,
            errorMessage = reason
        )
        val siteKey = _stateFlow.value.siteKey
        pendingDeferred?.complete(
            Result.failure(ProtectedWebRuntimeError.CloudflareChallengeFailed(siteKey, reason))
        )
    }

    fun cancelCurrentChallenge() = cancelChallenge()

    fun cancelChallenge() {
        if (_stateFlow.value.phase == ChallengeStateMachine.Phase.SESSION_PERSISTED) {
            return
        }
        val attemptId = _stateFlow.value.attemptId
        val key = _stateFlow.value.challengeKey
        if (attemptId > 0L) {
            cancelledAttemptIds.add(attemptId)
        }
        if (key.isNotBlank()) {
            cancelledChallengeKeys.add(key)
        }
        activeChallengeKey?.let { cancelledChallengeKeys.add(it) }

        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        val siteKey = _stateFlow.value.siteKey
        val candidateId = _stateFlow.value.originalContext?.extensionId ?: siteKey

        // Phase 05Q.5-C: Record cancelled candidate in current PlaybackAttempt
        val currentAttempt = com.example.extension.managed.playback.PlaybackAttempt.current()
        currentAttempt?.cancelCandidate(candidateId)
        currentAttempt?.cancelCandidate(siteKey)
        _stateFlow.value.originalContext?.extensionId?.let { currentAttempt?.cancelCandidate(it) }

        // 1. Transition state machine: CANCEL_REQUESTED -> CHALLENGE_CANCELLED
        stateMachine?.onCancelRequested()
        stateMachine?.onChallengeCancelled("USER_CANCELLED")

        // 2. Perform WebView cleanup
        try {
            webViewCleanupCallback?.invoke()
        } catch (_: Throwable) {}
        webViewCleanupCallback = null

        // 3. Mark UiState as cancelled and hidden
        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.CHALLENGE_CANCELLED,
            isVisible = false,
            errorMessage = "USER_CANCELLED"
        )

        CloudflareForensicLogger.cfUi16_recoveryCancelled(siteKey)
        CloudflareForensicLogger.cfTrace("challenge_cancelled", candidateId = candidateId, host = _stateFlow.value.canonicalHost)

        // 4. Complete pending deferred with failure so caller aborts candidate
        pendingDeferred?.complete(
            Result.failure(ProtectedWebRuntimeError.CloudflareChallengeCancelled(siteKey))
        )
        pendingDeferred = null
    }

    /**
     * Phase 05Q.3-D: Unverified Exit.
     * Called when user exits/closes screen, presses Back, or navigates away without completing verification.
     * Discards current challenge attempt, clears transient state, stops WebView and polling,
     * completes pendingDeferred with CloudflareChallengeUnverifiedExit, and does NOT save session.
     * Existing valid sessions in PerSiteSessionStore remain intact.
     */
    fun onUnverifiedExit(reason: String = "USER_EXIT") {
        val current = _stateFlow.value
        val curPhase = current.phase

        // HARD RULE (Section 7): If SESSION_PERSISTED already committed, it is VERIFIED; ignore exit.
        if (curPhase == ChallengeStateMachine.Phase.SESSION_PERSISTED) {
            return
        }

        val attemptId = current.attemptId
        val siteKey = current.siteKey
        val key = current.challengeKey

        if (attemptId > 0L) {
            cancelledAttemptIds.add(attemptId)
        }

        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        // 1. Transition state machine
        stateMachine?.onUnverifiedExit(reason)
        stateMachine?.onAttemptDiscarded(attemptId)

        // 2. Perform WebView cleanup
        try {
            webViewCleanupCallback?.invoke()
        } catch (_: Throwable) {}
        webViewCleanupCallback = null

        // 3. Mark UiState as unverified exit and hidden
        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.UNVERIFIED_EXIT,
            isVisible = false,
            errorMessage = "UNVERIFIED_EXIT"
        )

        CloudflareForensicLogger.cfUi18_unverifiedExit(siteKey, reason)
        CloudflareForensicLogger.cfUi19_attemptDiscarded(siteKey, attemptId)
        CloudflareForensicLogger.cfTrace("user_exit", host = current.canonicalHost)
        CloudflareForensicLogger.cfTrace("unverified_exit", host = current.canonicalHost)
        CloudflareForensicLogger.cfTrace("challenge_attempt_discarded", host = current.canonicalHost)
        CloudflareForensicLogger.cfTrace("transient_state_cleared", host = current.canonicalHost)
        CloudflareForensicLogger.cfTrace("webview_cleaned", host = current.canonicalHost)

        // 4. Complete pending deferred with CloudflareChallengeUnverifiedExit
        pendingDeferred?.complete(
            Result.failure(ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit(siteKey))
        )
        pendingDeferred = null
    }

    fun onTimeout(timeoutMs: Long = 60_000L) {
        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        val siteKey = _stateFlow.value.siteKey
        stateMachine?.onTimeout(timeoutMs)
        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT,
            errorMessage = "TIMEOUT"
        )
        pendingDeferred?.complete(
            Result.failure(ProtectedWebRuntimeError.CloudflareChallengeTimeout(siteKey, timeoutMs))
        )
    }

    fun retryChallenge() {
        val current = _stateFlow.value
        val ctx = current.originalContext ?: return
        scope.launch {
            launchInteractiveChallenge(
                siteKey = current.siteKey,
                canonicalHost = current.canonicalHost,
                challengeUrl = current.challengeUrl,
                context = ctx
            )
        }
    }

    fun reset() {
        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null
        webViewCleanupCallback = null
        pendingDeferred?.cancel()
        pendingDeferred = null
        stateMachine?.onIdle()
        _stateFlow.value = UiState()
    }
}
