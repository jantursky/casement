package com.jantursky.casement.mcp

import com.jantursky.casement.bridge.BridgeRejection
import com.jantursky.casement.model.EXTENSION_ID
import com.jantursky.casement.model.MCP_APP_MIME_TYPE
import com.jantursky.casement.model.ToolVisibility
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class AdapterMappingTest {
    private fun uiMeta(
        uri: String,
        visibility: List<String>? = null,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("ui") {
                put("resourceUri", uri)
                visibility?.let { v ->
                    putJsonArray("visibility") { v.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                }
            }
        }

    private val tools =
        listOf(
            Tool("show", ToolSchema(), meta = uiMeta("ui://app/show")),
            Tool("helper", ToolSchema(), meta = uiMeta("ui://app/show", listOf("app"))),
            Tool("plain", ToolSchema()),
            Tool("model-only", ToolSchema(), meta = uiMeta("ui://app/other", listOf("model"))),
        )

    @Test
    fun `visibility comes from the tool meta and defaults to both`() {
        val map = toolVisibility(tools)
        assertEquals(setOf(ToolVisibility.Model, ToolVisibility.App), map["show"])
        assertEquals(setOf(ToolVisibility.App), map["helper"])
        assertEquals(setOf(ToolVisibility.Model, ToolVisibility.App), map["plain"])
        assertEquals(setOf(ToolVisibility.Model), map["model-only"])
    }

    @Test
    fun `only tools with a ui resource are returned with their definition`() {
        val withUi = toolsWithUi(tools)
        assertEquals(listOf("show", "helper", "model-only"), withUi.map { it.name })
        assertEquals("ui://app/show", withUi.first().ui.resourceUri)
        assertEquals(
            "show",
            withUi
                .first()
                .definition["name"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `text content becomes a ui resource with its metadata`() {
        val meta = buildJsonObject { putJsonObject("ui") { put("prefersBorder", true) } }
        val result =
            ReadResourceResult(
                listOf(
                    TextResourceContents(
                        text = "<html></html>",
                        uri = "ui://a/b",
                        mimeType = MCP_APP_MIME_TYPE,
                        meta = meta,
                    ),
                ),
            )
        val resource = toUiResource("ui://a/b", result)
        assertEquals("<html></html>", resource.html)
        assertEquals(true, resource.meta.prefersBorder)
    }

    @Test
    fun `blob content is decoded and the mime type may have spaces`() {
        val blob = Base64.encode("<p>hi</p>".encodeToByteArray())
        val result =
            ReadResourceResult(
                listOf(BlobResourceContents(blob = blob, uri = "ui://a/b", mimeType = "Text/HTML; profile=mcp-app")),
            )
        assertEquals("<p>hi</p>", toUiResource("ui://a/b", result).html)
    }

    @Test
    fun `resources that are not mcp apps are rejected`() {
        val plain =
            ReadResourceResult(listOf(TextResourceContents(text = "x", uri = "ui://a/b", mimeType = "text/html")))
        assertFailsWith<BridgeRejection> { toUiResource("ui://a/b", plain) }
        assertFailsWith<BridgeRejection> { toUiResource("https://example.com/x", plain) }
        assertFailsWith<BridgeRejection> { toUiResource("ui://a/b", ReadResourceResult(emptyList())) }
        val noMime = ReadResourceResult(listOf(TextResourceContents(text = "x", uri = "ui://a/b", mimeType = null)))
        assertFailsWith<BridgeRejection> { toUiResource("ui://a/b", noMime) }
    }

    @Test
    fun `the first app content wins over other items and meta is optional`() {
        val result =
            ReadResourceResult(
                listOf(
                    TextResourceContents(text = "skip", uri = "ui://a/b", mimeType = "text/plain"),
                    TextResourceContents(text = "<b>ok</b>", uri = "ui://a/b", mimeType = MCP_APP_MIME_TYPE),
                ),
            )
        val resource = toUiResource("ui://a/b", result)
        assertEquals("<b>ok</b>", resource.html)
        assertEquals(null, resource.meta.prefersBorder)
    }

    @Test
    fun `client capabilities advertise the ui extension and keep existing ones`() {
        val base = ClientCapabilities(extensions = mapOf("other" to JsonObject(emptyMap())))
        val caps = mcpAppsClientCapabilities(base)
        assertTrue("other" in caps.extensions!!)
        val ui = caps.extensions!![EXTENSION_ID]!!
        assertEquals(
            MCP_APP_MIME_TYPE,
            ui["mimeTypes"]!!
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
        assertEquals(setOf(EXTENSION_ID), mcpAppsClientCapabilities().extensions!!.keys)
    }
}
