package com.jantursky.casement.policy

import com.jantursky.casement.model.UiCsp
import com.jantursky.casement.model.UiPermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyTest {
    @Test
    fun `no declaration gives the locked down default`() {
        val csp = CspBuilder.build(null)
        assertContains(csp.header, "default-src 'none'")
        assertContains(csp.header, "connect-src 'none'")
        assertContains(csp.header, "frame-src 'none'")
        assertContains(csp.header, "object-src 'none'")
        assertContains(csp.header, "base-uri 'self'")
        assertContains(csp.header, "form-action 'none'")
        assertContains(csp.header, "img-src 'self' data:")
        assertTrue(csp.rejected.isEmpty())
    }

    @Test
    fun `declared domains are added to the matching directives`() {
        val csp =
            CspBuilder.build(
                UiCsp(
                    connectDomains = listOf("https://api.example.com", "wss://live.example.com:8443"),
                    resourceDomains = listOf("https://*.cdn.example.com"),
                    frameDomains = listOf("https://embed.example.com"),
                    baseUriDomains = listOf("https://base.example.com"),
                ),
            )
        assertContains(csp.header, "connect-src https://api.example.com wss://live.example.com:8443")
        assertContains(csp.header, "script-src 'self' 'unsafe-inline' https://*.cdn.example.com")
        assertContains(csp.header, "font-src 'self' https://*.cdn.example.com")
        assertContains(csp.header, "frame-src https://embed.example.com")
        assertContains(csp.header, "base-uri https://base.example.com")
    }

    @Test
    fun `entries that could break out of the header are dropped`() {
        val bad =
            listOf(
                "https://a.com; script-src *",
                "*",
                "https://*",
                "http://evil.com",
                "https://a.com'",
                "javascript:alert(1)",
                "https://a.com\r\nX: y",
                "ftp://a.com",
                "data:",
                "",
            )
        val csp =
            CspBuilder.build(
                UiCsp(connectDomains = bad, resourceDomains = listOf("https://ok.com", "https://ok.com")),
            )
        assertEquals(bad, csp.rejected)
        assertContains(csp.header, "connect-src 'none'")
        assertFalse(csp.header.contains("evil"))
        assertEquals(
            1,
            Regex("https://ok.com").findAll(csp.header.substringAfter("script-src").substringBefore(";")).count(),
        )
    }

    @Test
    fun `plain http and ws are accepted for loopback only`() {
        assertTrue(CspBuilder.isAllowedOrigin("http://localhost:8080"))
        assertTrue(CspBuilder.isAllowedOrigin("ws://127.0.0.1:9000"))
        assertFalse(CspBuilder.isAllowedOrigin("http://example.com"))
        assertFalse(CspBuilder.isAllowedOrigin("ws://10.0.2.2"))
    }

    @Test
    fun `links need an allowed scheme and no whitespace`() {
        val policy = HostPolicy()
        assertTrue(policy.allowsLink("https://example.com/a?b=c"))
        assertTrue(policy.allowsLink("HTTPS://example.com"))
        assertTrue(policy.allowsLink("mailto:a@b.c"))
        assertFalse(policy.allowsLink("javascript:alert(1)"))
        assertFalse(policy.allowsLink("data:text/html,x"))
        assertFalse(policy.allowsLink("file:///etc/passwd"))
        assertFalse(policy.allowsLink("intent://x#Intent;end"))
        assertFalse(policy.allowsLink("https://a.com/ b"))
        assertFalse(policy.allowsLink("https://a.com/\u0000"))
        assertFalse(policy.allowsLink("//example.com"))
        assertFalse(policy.allowsLink(""))
        assertTrue(HostPolicy(allowedLinkSchemes = setOf("tel")).allowsLink("tel:123"))
    }

    @Test
    fun `permissions are denied unless the host grants them`() {
        val asked = UiPermissions(camera = true, microphone = true)
        assertTrue(HostPolicy().resolvePermissions(asked).isEmpty)
        val granted = HostPolicy(grantedPermissions = UiPermissions(camera = true))
        assertEquals(UiPermissions(camera = true), granted.resolvePermissions(asked))
    }

    @Test
    fun `origin matcher checks scheme host and port`() {
        val allowed = listOf("https://api.example.com", "https://*.cdn.example.com", "wss://live.example.com:8443")
        assertTrue(OriginMatcher.isAllowed(allowed, "https://api.example.com/v1/x?y=1"))
        assertTrue(OriginMatcher.isAllowed(allowed, "HTTPS://API.EXAMPLE.COM:443/"))
        assertTrue(OriginMatcher.isAllowed(allowed, "https://a.b.cdn.example.com/lib.js"))
        assertTrue(OriginMatcher.isAllowed(allowed, "wss://live.example.com:8443/socket"))
        assertFalse(OriginMatcher.isAllowed(allowed, "https://cdn.example.com/lib.js"))
        assertFalse(OriginMatcher.isAllowed(allowed, "https://evilcdn.example.com/lib.js"))
        assertFalse(OriginMatcher.isAllowed(allowed, "https://api.example.com.evil.com/"))
        assertFalse(OriginMatcher.isAllowed(allowed, "https://api.example.com@evil.com/"))
        assertFalse(OriginMatcher.isAllowed(allowed, "https://api.example.com:8080/"))
        assertFalse(OriginMatcher.isAllowed(allowed, "http://api.example.com/"))
        assertFalse(OriginMatcher.isAllowed(allowed, "wss://live.example.com/"))
        assertFalse(OriginMatcher.isAllowed(allowed, "data:text/html,x"))
        assertFalse(OriginMatcher.isAllowed(allowed, "https://[::1]/"))
        assertFalse(OriginMatcher.isAllowed(allowed, "https:///path"))
        assertFalse(OriginMatcher.isAllowed(allowed, "https://api.example.com:abc/"))
        assertFalse(OriginMatcher.isAllowed(emptyList(), "https://api.example.com/"))
        assertFalse(OriginMatcher.isAllowed(listOf("https://*"), "https://anything.com/"))
        assertFalse(OriginMatcher.isAllowed(listOf("not a url"), "https://anything.com/"))
    }
}
