package com.example.extension.managed.runtime

import com.example.extension.managed.model.ChallengeStatus
import java.util.UUID

/**
 * Encapsulates the execution context of a single extraction operation.
 * Manages runtime credentials internally without leaking unneeded headers to downstream consumers.
 */
data class ExtractionSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val extensionId: String,
    val scraperKey: String,
    val targetPageUrl: String,
    var challengeState: ChallengeStatus = ChallengeStatus.NONE,
    val createdAt: Long = System.currentTimeMillis(),
    val timeoutMs: Long = 15_000L,
    @Volatile var isCancelled: Boolean = false,
    val sessionCookies: MutableMap<String, String> = mutableMapOf(),
    val sessionHeaders: MutableMap<String, String> = mutableMapOf()
) {
    fun cancel() {
        isCancelled = true
    }
}
