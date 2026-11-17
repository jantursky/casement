package com.jantursky.casement.compose.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDocumentsTest {
    @Test
    fun `the policy goes right after the doctype`() {
        val doc = appDocument("<!doctype html><p>a</p>", "default-src 'none'")
        assertTrue(doc.startsWith("<!doctype html><meta http-equiv=\"Content-Security-Policy\""), doc)
        assertTrue(doc.endsWith("<p>a</p>"), doc)
    }

    @Test
    fun `without a doctype the policy goes first`() {
        assertTrue(appDocument("<p>a</p>", "x").startsWith("<meta http-equiv"))
    }

    @Test
    fun `quotes in the policy are escaped`() {
        assertTrue("content=\"a &quot;b&quot;\"" in appDocument("<p></p>", "a \"b\""))
    }

    @Test
    fun `the document reports violations to the page around it`() {
        assertTrue("securitypolicyviolation" in appDocument("<p></p>", "x"))
    }

    @Test
    fun `strings become javascript literals`() {
        assertEquals("\"a\\\"b\\n\"", jsString("a\"b\n"))
    }

    @Test
    fun `the wrapper keeps frames from navigating and relays only json rpc`() {
        val wrapper = wrapperDocument()
        assertTrue("frame-src about:" in wrapper)
        assertTrue("allow-scripts allow-forms" in wrapper && "allow-same-origin" !in wrapper)
        assertTrue("jsonrpc === '2.0'" in wrapper)
    }
}
