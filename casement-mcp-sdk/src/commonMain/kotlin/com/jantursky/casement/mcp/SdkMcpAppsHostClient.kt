package com.jantursky.casement.mcp

import com.jantursky.casement.bridge.BridgeRejection
import com.jantursky.casement.bridge.ToolCatalog
import com.jantursky.casement.client.McpAppsHostClient
import com.jantursky.casement.model.EXTENSION_ID
import com.jantursky.casement.model.MCP_APP_MIME_TYPE
import com.jantursky.casement.model.ToolUi
import com.jantursky.casement.model.ToolVisibility
import com.jantursky.casement.model.ToolWithUi
import com.jantursky.casement.model.UiResource
import com.jantursky.casement.model.UiResourceMeta
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The capabilities to give the SDK `Client` so servers know this host renders MCP Apps. Without the
 * `io.modelcontextprotocol/ui` extension, a server may leave the UI out of its tools.
 */
public fun mcpAppsClientCapabilities(base: ClientCapabilities = ClientCapabilities()): ClientCapabilities =
    base.copy(
        extensions =
            (base.extensions ?: emptyMap()) +
                (
                    EXTENSION_ID to
                        buildJsonObject {
                            putJsonArray(
                                "mimeTypes",
                            ) { add(kotlinx.serialization.json.JsonPrimitive(MCP_APP_MIME_TYPE)) }
                        }
                ),
    )

/**
 * A [McpAppsHostClient] on top of the official MCP Kotlin SDK [Client]. The client must be connected, and was best
 * created with [mcpAppsClientCapabilities]. Call [refreshTools] after connecting and whenever the server's tool list
 * changes: it fills the tool catalog and returns the tools that carry a UI.
 */
public class SdkMcpAppsHostClient(
    private val client: Client,
) : McpAppsHostClient {
    private var visibility: Map<String, Set<ToolVisibility>> = emptyMap()

    override val tools: ToolCatalog = ToolCatalog { visibility[it] }

    /** Lists every tool on the server, remembers who may call it, and returns the ones that declare a UI. */
    public suspend fun refreshTools(): List<ToolWithUi> {
        val all = mutableListOf<Tool>()
        var cursor: String? = null
        do {
            val page = client.listTools(ListToolsRequest(cursor?.let { PaginatedRequestParams(cursor = it) }))
            all += page.tools
            cursor = page.nextCursor
        } while (cursor != null)
        visibility = toolVisibility(all)
        return toolsWithUi(all)
    }

    override suspend fun readResource(uri: String): UiResource =
        toUiResource(uri, client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri))))

    override suspend fun callTool(
        name: String,
        arguments: JsonObject?,
    ): JsonObject {
        val result: CallToolResult = client.callTool(CallToolRequest(CallToolRequestParams(name, arguments)))
        return McpJson.encodeToJsonElement(CallToolResult.serializer(), result).jsonObject
    }
}

/** Who may call each tool. A tool without a UI declaration is visible to the model and the app. */
internal fun toolVisibility(tools: List<Tool>): Map<String, Set<ToolVisibility>> =
    tools.associate { tool ->
        tool.name to (ToolUi.fromToolMeta(tool.meta)?.visibility ?: setOf(ToolVisibility.Model, ToolVisibility.App))
    }

/** The tools that declare a `ui://` resource, with their definitions as JSON for the app's `toolInfo`. */
internal fun toolsWithUi(tools: List<Tool>): List<ToolWithUi> =
    tools.mapNotNull { tool ->
        ToolUi.fromToolMeta(tool.meta)?.let { ui ->
            ToolWithUi(tool.name, ui, McpJson.encodeToJsonElement(Tool.serializer(), tool).jsonObject)
        }
    }

/** Picks the MCP App document out of a `resources/read` answer. Throws [BridgeRejection] if there is none. */
@OptIn(ExperimentalEncodingApi::class)
internal fun toUiResource(
    uri: String,
    result: ReadResourceResult,
): UiResource {
    if (!uri.startsWith("ui://")) throw BridgeRejection("Not a ui:// resource: $uri")
    val content =
        result.contents.firstOrNull { isAppMimeType(it.mimeType) }
            ?: throw BridgeRejection("$uri has no $MCP_APP_MIME_TYPE content")
    val html =
        when (content) {
            is TextResourceContents -> content.text
            is BlobResourceContents -> Base64.decode(content.blob).decodeToString()
            else -> throw BridgeRejection("$uri has an unsupported content type")
        }
    return UiResource(uri, html, UiResourceMeta.fromResourceMeta(content.meta))
}

private fun isAppMimeType(mimeType: String?): Boolean =
    mimeType?.filterNot { it == ' ' }?.lowercase() == MCP_APP_MIME_TYPE

/**
 * Connects to an MCP server over Streamable HTTP and returns a ready [SdkMcpAppsHostClient]: the UI extension is
 * advertised, and the tool list is loaded, so [SdkMcpAppsHostClient.tools] answers right away. The [httpClient] needs
 * an engine for the platform; the SSE plugin is added here.
 *
 * @param url The server's MCP endpoint, for example `http://localhost:3001/mcp`.
 */
public suspend fun connectMcpAppsHost(
    httpClient: HttpClient,
    url: String,
    clientInfo: Implementation = Implementation(name = "casement-host", version = "1.0.0"),
): SdkMcpAppsHostClient {
    val http = httpClient.config { install(SSE) }
    val client = Client(clientInfo, ClientOptions(capabilities = mcpAppsClientCapabilities()))
    client.connect(http.mcpStreamableHttpTransport(url))
    return SdkMcpAppsHostClient(client).also { it.refreshTools() }
}
