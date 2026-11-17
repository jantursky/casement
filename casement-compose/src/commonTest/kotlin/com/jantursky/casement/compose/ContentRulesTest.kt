package com.jantursky.casement.compose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentRulesTest {
    private fun filters(origins: List<String>): List<String> =
        Json
            .parseToJsonElement(contentRules(origins))
            .jsonArray
            .map {
                it.jsonObject["trigger"]!!
                    .jsonObject["url-filter"]!!
                    .jsonPrimitive.content
            }

    @Test
    fun `without origins everything on the network is blocked`() {
        assertEquals(listOf("^https?://", "^wss?://"), filters(emptyList()))
    }

    @Test
    fun `the list is valid json and every action is block or ignore`() {
        val parsed = Json.parseToJsonElement(contentRules(listOf("https://cdn.example.com"))).jsonArray
        val actions =
            parsed.map {
                it.jsonObject["action"]!!
                    .jsonObject["type"]!!
                    .jsonPrimitive.content
            }
        assertEquals(listOf("block", "block", "ignore-previous-rules", "ignore-previous-rules"), actions)
    }

    @Test
    fun `an origin without a port allows the default port too`() {
        assertEquals(
            listOf("^https://cdn\\.example\\.com[/?#]", "^https://cdn\\.example\\.com:443[/?#]"),
            originFilters("https://cdn.example.com"),
        )
        assertEquals(listOf("^http://localhost:8080[/?#]"), originFilters("http://localhost:8080"))
    }

    @Test
    fun `a wildcard covers subdomains only`() {
        assertEquals(
            listOf("^https://[^/:?#]+\\.example\\.com[/?#]", "^https://[^/:?#]+\\.example\\.com:443[/?#]"),
            originFilters("https://*.example.com"),
        )
    }

    @Test
    fun `patterns use only what WebKit understands`() {
        val origins = listOf("https://a.example.com", "wss://*.b.example.com:8443", "http://localhost:3000")
        filters(origins).forEach { pattern ->
            assertTrue('(' !in pattern && '|' !in pattern && '{' !in pattern, pattern)
        }
    }

    @Test
    fun `origins that are not understood are dropped`() {
        listOf("ftp://x.com", "https://", "https://user@x.com", "https://x.*.com", "https://x.com:abc", "nonsense")
            .forEach { assertEquals(emptyList(), originFilters(it), it) }
    }
}
