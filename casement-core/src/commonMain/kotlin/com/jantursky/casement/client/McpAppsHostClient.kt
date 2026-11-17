package com.jantursky.casement.client

import com.jantursky.casement.bridge.BridgeRejection
import com.jantursky.casement.bridge.ToolCatalog
import com.jantursky.casement.model.UiResource
import kotlinx.serialization.json.JsonObject

/**
 * What a host needs from its MCP connection. Implement it over any MCP client; the `casement-mcp-sdk` module
 * does so for the official Kotlin SDK.
 *
 * Tool results and resource answers are plain JSON in the shapes the MCP spec defines, so no protocol types are
 * copied here.
 */
public interface McpAppsHostClient {
    /**
     * The tools the app may ask about and their visibility. A tool missing here cannot be called by the app.
     * Include tools without a UI too: an app often calls helper tools that have none.
     */
    public val tools: ToolCatalog

    /** Reads a `ui://` resource. Throw if it cannot be read or is not an MCP App document. */
    public suspend fun readResource(uri: String): UiResource

    /** Calls a tool on the server the app came from. Returns the `CallToolResult` as JSON. */
    public suspend fun callTool(
        name: String,
        arguments: JsonObject?,
    ): JsonObject

    /**
     * Answers `resources/read`, `resources/list`, `resources/templates/list` and `prompts/list` for the app.
     * The default refuses, so a host has to opt in.
     */
    public suspend fun forward(
        method: String,
        params: JsonObject?,
    ): JsonObject = throw BridgeRejection("$method is not enabled")
}
