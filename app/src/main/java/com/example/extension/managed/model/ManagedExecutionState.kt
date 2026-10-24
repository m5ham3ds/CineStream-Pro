package com.example.extension.managed.model

/**
 * Normalized execution states across the Managed Extension lifecycle and execution pipeline.
 */
enum class ManagedExecutionState {
    IDLE,
    CHECKING_NETWORK,
    RESOLVING_EXTENSION,
    SEARCHING,
    LOADING_DETAILS,
    LOADING_EPISODES,
    DISCOVERING_SERVERS,
    LOADING_EMBED,
    WAITING_FOR_CHALLENGE,
    EXTRACTING_MEDIA,
    READY,
    FAILED,
    CANCELLED
}
