package com.example.extension.managed.runtime.network

/**
 * Standardized network connectivity states.
 */
enum class NetworkState {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN
}

/**
 * Reusable network connectivity abstraction.
 * Prevents individual scrapers or use cases from implementing duplicate network checking logic.
 */
interface ConnectivityMonitor {
    fun isConnected(): Boolean
    fun getNetworkState(): NetworkState
}

/**
 * Default implementation wrapping a lambda checker, ideal for testing and runtime composition.
 */
class DefaultConnectivityMonitor(
    private val isOnlineCheck: () -> Boolean = { true }
) : ConnectivityMonitor {
    override fun isConnected(): Boolean = isOnlineCheck()
    override fun getNetworkState(): NetworkState =
        if (isOnlineCheck()) NetworkState.AVAILABLE else NetworkState.UNAVAILABLE
}
