package com.jantursky.casement.policy

import com.jantursky.casement.model.UiCsp
import com.jantursky.casement.model.UiPermissions

/**
 * What the host allows an app to do. Everything not granted here is denied.
 *
 * @property grantedPermissions Permissions the host is willing to grant. An app gets the intersection of this
 * and what it declared in `_meta.ui.permissions`. The default grants nothing.
 * @property allowedLinkSchemes URL schemes `ui/open-link` may use.
 * @property maxHtmlLength Largest UI document the host renders, in UTF-16 characters.
 * @property maxMessageLength Largest JSON-RPC message accepted from the app, in UTF-16 characters.
 */
public data class HostPolicy(
    val grantedPermissions: UiPermissions = UiPermissions.None,
    val allowedLinkSchemes: Set<String> = setOf("https", "http", "mailto"),
    val maxHtmlLength: Int = 2_000_000,
    val maxMessageLength: Int = 4_000_000,
) {
    /** True when [url] has a scheme the policy allows. Whitespace and control characters are never allowed. */
    public fun allowsLink(url: String): Boolean {
        if (url.isEmpty() || url.any { it.isWhitespace() || it.isISOControl() }) return false
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme.isNotEmpty() && scheme in allowedLinkSchemes
    }

    /** What an app that declared [declared] actually gets. */
    public fun resolvePermissions(declared: UiPermissions): UiPermissions = declared intersect grantedPermissions
}

/**
 * The CSP header for an app, the domains that were dropped while building it, and the domains that made it in.
 * Report [accepted] to the app as `sandbox.csp`.
 */
public data class CspResult(
    val header: String,
    val rejected: List<String>,
    val accepted: UiCsp,
)

/**
 * Builds the `Content-Security-Policy` value for an app from its declared [UiCsp].
 *
 * The rules follow the spec's example, and are stricter in three places: a connection to the app's own origin
 * is not allowed (the origin only serves the document), forms cannot be submitted, and an entry that is not a
 * plain `https://` or `wss://` origin is dropped. Plain `http://` and `ws://` are accepted for loopback hosts only.
 */
public object CspBuilder {
    private val origin =
        Regex("""^(https|wss|http|ws)://(\*\.)?[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$""")
    private val loopback = setOf("localhost", "127.0.0.1")

    /** Builds the header. With no declaration, the spec's locked-down default applies: no network at all. */
    public fun build(csp: UiCsp?): CspResult {
        val rejected = mutableListOf<String>()

        fun clean(domains: List<String>): List<String> =
            domains
                .filter {
                    isAllowedOrigin(it) ||
                        run {
                            rejected += it
                            false
                        }
                }.distinct()

        val resources = clean(csp?.resourceDomains.orEmpty())
        val connect = clean(csp?.connectDomains.orEmpty())
        val frames = clean(csp?.frameDomains.orEmpty())
        val bases = clean(csp?.baseUriDomains.orEmpty())

        fun sources(
            vararg base: String,
            extra: List<String> = emptyList(),
        ): String = (base.toList() + extra).joinToString(" ")

        val directives =
            listOf(
                "default-src 'none'",
                "script-src ${sources("'self'", "'unsafe-inline'", extra = resources)}",
                "style-src ${sources("'self'", "'unsafe-inline'", extra = resources)}",
                "img-src ${sources("'self'", "data:", extra = resources)}",
                "font-src ${sources("'self'", extra = resources)}",
                "media-src ${sources("'self'", "data:", extra = resources)}",
                "connect-src ${if (connect.isEmpty()) "'none'" else connect.joinToString(" ")}",
                "frame-src ${if (frames.isEmpty()) "'none'" else frames.joinToString(" ")}",
                "object-src 'none'",
                "base-uri ${if (bases.isEmpty()) "'self'" else bases.joinToString(" ")}",
                "form-action 'none'",
            )
        return CspResult(directives.joinToString("; "), rejected, UiCsp(connect, resources, frames, bases))
    }

    /** True for `https` and `wss` origins, a leading wildcard label, and loopback `http` and `ws`, with a port. */
    public fun isAllowedOrigin(entry: String): Boolean {
        if (!origin.matches(entry)) return false
        val scheme = entry.substringBefore("://")
        if (scheme == "https" || scheme == "wss") return true
        val host = entry.substringAfter("://").substringBefore(':')
        return host in loopback
    }
}

/** Decides whether a request URL falls under the origins a CSP declared. Used as a second wall behind the CSP. */
public object OriginMatcher {
    private val defaultPorts = mapOf("https" to 443, "wss" to 443, "http" to 80, "ws" to 80)

    /** True when [url] has the scheme, host and port of one of [allowed]. A leading `*.` matches any subdomain. */
    public fun isAllowed(
        allowed: List<String>,
        url: String,
    ): Boolean {
        val target = Origin.parse(url) ?: return false
        return allowed.any { entry -> Origin.parse(entry, wildcard = true)?.let { target.isCoveredBy(it) } == true }
    }

    private data class Origin(
        val scheme: String,
        val host: String,
        val port: Int,
        val wildcard: Boolean,
    ) {
        fun isCoveredBy(allowed: Origin): Boolean {
            if (scheme != allowed.scheme || port != allowed.port) return false
            return if (allowed.wildcard) host.endsWith(".${allowed.host}") else host == allowed.host
        }

        companion object {
            fun parse(
                text: String,
                wildcard: Boolean = false,
            ): Origin? {
                val scheme = text.substringBefore("://", "").lowercase()
                val defaultPort = defaultPorts[scheme] ?: return null
                val authority = text.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
                if ('@' in authority || '[' in authority || authority.isEmpty()) return null
                val hostPart = authority.substringBeforeLast(':').lowercase()
                val portText = authority.substringAfterLast(':', missingDelimiterValue = "")
                val port = if (portText.isEmpty()) defaultPort else portText.toIntOrNull() ?: return null
                val isWildcard = wildcard && hostPart.startsWith("*.")
                val host = if (isWildcard) hostPart.removePrefix("*.") else hostPart
                if (host.isEmpty() || '*' in host) return null
                return Origin(scheme, host, port, isWildcard)
            }
        }
    }
}
