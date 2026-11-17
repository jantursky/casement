package com.jantursky.casement.compose

private val defaultPorts = mapOf("https" to "443", "wss" to "443", "http" to "80", "ws" to "80")

/**
 * A WebKit content rule list (JSON) that blocks every http(s) and ws(s) request and then re-allows the declared
 * origins, with the same meaning as `OriginMatcher`: scheme, host and port must match, `*.` covers subdomains only.
 *
 * WebKit's `url-filter` is not a full regular expression: it has no groups and no alternation, only literals,
 * character classes and the `* + ?` quantifiers. A list with anything else fails to compile, so every pattern here
 * is flat. The document itself uses a custom scheme, so these rules never touch it.
 */
internal fun contentRules(allowedOrigins: List<String>): String {
    val rules =
        mutableListOf(
            """{"trigger":{"url-filter":"^https?://"},"action":{"type":"block"}}""",
            """{"trigger":{"url-filter":"^wss?://"},"action":{"type":"block"}}""",
        )
    allowedOrigins.flatMap(::originFilters).forEach { filter ->
        val escaped = filter.replace("\\", "\\\\")
        rules += """{"trigger":{"url-filter":"$escaped"},"action":{"type":"ignore-previous-rules"}}"""
    }
    return rules.joinToString(prefix = "[", postfix = "]", separator = ",")
}

/** Flat url-filter patterns for one origin. Empty when the origin is not understood. */
internal fun originFilters(origin: String): List<String> {
    val scheme = origin.substringBefore("://", "").lowercase()
    val defaultPort = defaultPorts[scheme] ?: return emptyList()
    val authority = origin.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()
    if (authority.isEmpty() || '@' in authority || '[' in authority) return emptyList()
    val wildcard = authority.startsWith("*.")
    val hostAndPort = authority.removePrefix("*.")
    val host = hostAndPort.substringBeforeLast(':')
    val port = hostAndPort.substringAfterLast(':', "")
    if (host.isEmpty() || '*' in host || (port.isNotEmpty() && port.toIntOrNull() == null)) return emptyList()
    val hostPattern = (if (wildcard) "[^/:?#]+\\." else "") + host.replace(".", "\\.")
    val ports = if (port.isEmpty()) listOf("", ":$defaultPort") else listOf(":$port")
    return ports.map { "^$scheme://$hostPattern$it[/?#]" }
}
