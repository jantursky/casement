package com.jantursky.casement.mcp

import com.jantursky.casement.model.EXTENSION_ID
import com.jantursky.casement.model.MCP_APP_MIME_TYPE
import com.jantursky.casement.model.ToolVisibility
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** A real SDK server and client talking over an in-memory transport, with the adapter in between. */
class SdkClientIntegrationTest {
    private suspend fun connected(): SdkMcpAppsHostClient {
        val server =
            Server(
                Implementation("server", "1"),
                ServerOptions(
                    ServerCapabilities(tools = ServerCapabilities.Tools(), resources = ServerCapabilities.Resources()),
                ),
            )
        server.addTool(
            name = "show",
            description = "Shows the app",
            inputSchema = ToolSchema(),
            meta = buildJsonObject { putJsonObject("ui") { put("resourceUri", "ui://app/show") } },
        ) {
            val structured = buildJsonObject { put("n", 1) }
            CallToolResult(listOf(TextContent("shown")), structuredContent = structured)
        }
        server.addResource("ui://app/show", "show", "The app", MCP_APP_MIME_TYPE) {
            val contents =
                TextResourceContents(text = "<html>app</html>", uri = "ui://app/show", mimeType = MCP_APP_MIME_TYPE)
            ReadResourceResult(listOf(contents))
        }
        val pair = ChannelTransport.createLinkedPair()
        server.createSession(pair.serverTransport)
        val options = ClientOptions(capabilities = mcpAppsClientCapabilities())
        val client = Client(Implementation("client", "1"), options)
        client.connect(pair.clientTransport)
        assertEquals(true, client.serverCapabilities?.tools != null)
        return SdkMcpAppsHostClient(client)
    }

    @Test
    fun `tools resources and calls work through the sdk`() =
        runBlocking {
            val host = connected()
            val withUi = host.refreshTools()
            assertEquals(listOf("show"), withUi.map { it.name })
            assertEquals(setOf(ToolVisibility.Model, ToolVisibility.App), host.tools.visibilityOf("show"))
            assertEquals(null, host.tools.visibilityOf("missing"))

            assertEquals("<html>app</html>", host.readResource("ui://app/show").html)

            val result = host.callTool("show", null)
            val text =
                result["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            assertEquals("shown", text)
            assertEquals(
                1,
                result["structuredContent"]!!
                    .jsonObject["n"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun `the client capabilities reach the server`() =
        runBlocking {
            val serverOptions = ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools()))
            val server = Server(Implementation("server", "1"), serverOptions)
            val pair = ChannelTransport.createLinkedPair()
            val session = server.createSession(pair.serverTransport)
            val options = ClientOptions(capabilities = mcpAppsClientCapabilities())
            val client = Client(Implementation("client", "1"), options)
            client.connect(pair.clientTransport)
            assertEquals(true, session.clientCapabilities?.extensions?.containsKey(EXTENSION_ID))
        }

    @Test
    fun `connecting to a server that refuses fails`() =
        runBlocking {
            val http = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
            assertFails { connectMcpAppsHost(http, "http://localhost:1/mcp") }
            Unit
        }
}
