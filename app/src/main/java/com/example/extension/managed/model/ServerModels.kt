package com.example.extension.managed.model

enum class ServerType {
    DIRECT,
    EMBED,
    HLS,
    DASH,
    UNKNOWN
}

data class ServerItem(
    val id: String,
    val name: String,
    val link: String,
    val isDirectStream: Boolean = false,
    val extraData: Map<String, String> = emptyMap(),
    val sourceUrl: String = link,
    val serverType: ServerType = if (isDirectStream) ServerType.DIRECT else ServerType.EMBED,
    val requiresWebView: Boolean = !isDirectStream,
    val capabilities: Set<String> = emptySet(),
    val metadata: Map<String, String> = extraData
)

data class ServerDiscoveryRequest(
    val targetUrl: String,
    val mediaTitle: String = "",
    val isMovie: Boolean = true,
    val season: Int = 1,
    val episode: Int = 1,
    val headers: Map<String, String> = emptyMap(),
    val contentType: ContentType? = null
)

data class ServerDiscoveryResult(
    val servers: List<ServerItem>,
    val sourcePageUrl: String,
    val metadata: Map<String, String> = emptyMap()
)

typealias ServerList = ServerDiscoveryResult
