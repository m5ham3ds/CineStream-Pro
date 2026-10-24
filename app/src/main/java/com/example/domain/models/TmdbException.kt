package com.example.domain.models

sealed class TmdbException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthException(
        message: String = "TMDB API authentication failed (HTTP 401/403). Valid API key required.",
        cause: Throwable? = null
    ) : TmdbException(message, cause)

    class NetworkException(
        message: String = "Unable to connect to TMDB. Check internet connection.",
        cause: Throwable? = null
    ) : TmdbException(message, cause)

    class ServerException(
        val statusCode: Int,
        message: String = "TMDB server error ($statusCode).",
        cause: Throwable? = null
    ) : TmdbException(message, cause)

    class UnknownException(
        message: String,
        cause: Throwable? = null
    ) : TmdbException(message, cause)
}
