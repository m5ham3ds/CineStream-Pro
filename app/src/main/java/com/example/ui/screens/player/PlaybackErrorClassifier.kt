package com.example.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Authoritative Media3 / ExoPlayer error classification.
 * Differentiates recoverable transport glitches (which require buffering and auto-resume)
 * from permanently invalid playback URLs (which require orchestrator cache invalidation and re-extraction).
 */
object PlaybackErrorClassifier {

    fun isTransientNetworkError(error: PlaybackException): Boolean {
        // 1. Inspect underlying cause chain first for explicit permanent HTTP responses
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                val code = cause.responseCode
                // 401 Unauthorized, 403 Forbidden, 404 Not Found, 410 Gone indicate dead / revoked stream URLs
                if (code in listOf(401, 403, 404, 410)) {
                    return false
                }
                // 5xx gateway/server hiccups are temporary
                if (code in 500..599) {
                    return true
                }
            }
            if (cause is SocketTimeoutException ||
                cause is UnknownHostException ||
                cause is ConnectException ||
                cause is NoRouteToHostException ||
                cause is InterruptedIOException
            ) {
                return true
            }
            cause = cause.cause
        }

        // 2. Specific permanent ExoPlayer parser/decoder error codes
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED -> return false
        }

        // 3. Media3 I/O network error codes
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
            else -> false
        }
    }

    fun isPermanentFailure(error: PlaybackException): Boolean {
        return !isTransientNetworkError(error)
    }
}
