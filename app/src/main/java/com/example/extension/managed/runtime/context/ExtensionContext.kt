package com.example.extension.managed.runtime.context

import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.challenge.ChallengeHandler
import com.example.extension.managed.runtime.network.ConnectivityMonitor
import com.example.extension.managed.runtime.network.DefaultConnectivityMonitor
import com.example.extension.managed.web.TrustedEmbedHostPolicy
import com.example.extension.managed.web.WebExtractionEngine

/**
 * Controlled, sandboxed execution environment provided to a managed site scraper.
 * Strictly exposes only non-UI runtime services, network state, session state, and security policies.
 * Never exposes Android Activity, Fragment, Compose UI, Dialogs, NavController, or raw Firebase objects.
 */
data class ExtensionContext(
    val extensionId: String,
    val scraperKey: String,
    val baseUrl: String,
    val runtimeApiVersion: Int,
    val connectivity: ConnectivityMonitor,
    val session: ExtractionSession,
    val webEngine: WebExtractionEngine? = null,
    val challengeHandler: ChallengeHandler? = null,
    val hostPolicy: TrustedEmbedHostPolicy = TrustedEmbedHostPolicy
) {
    fun toManagedExtension(): ManagedExtension {
        return ManagedExtension(
            id = extensionId,
            name = scraperKey,
            baseUrl = baseUrl,
            scraperKey = scraperKey,
            runtimeApiVersion = runtimeApiVersion
        )
    }

    companion object {
        fun from(
            extension: ManagedExtension,
            session: ExtractionSession,
            connectivity: ConnectivityMonitor = DefaultConnectivityMonitor(),
            webEngine: WebExtractionEngine? = null,
            challengeHandler: ChallengeHandler? = null
        ): ExtensionContext {
            return ExtensionContext(
                extensionId = extension.id,
                scraperKey = extension.scraperKey,
                baseUrl = extension.baseUrl,
                runtimeApiVersion = extension.runtimeApiVersion,
                connectivity = connectivity,
                session = session,
                webEngine = webEngine,
                challengeHandler = challengeHandler
            )
        }
    }
}
