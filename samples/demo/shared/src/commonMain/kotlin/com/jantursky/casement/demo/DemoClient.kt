package com.jantursky.casement.demo

import com.jantursky.casement.bridge.BridgeRejection
import com.jantursky.casement.bridge.ToolCatalog
import com.jantursky.casement.client.McpAppsHostClient
import com.jantursky.casement.model.ToolVisibility
import com.jantursky.casement.model.UiResource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock

/**
 * A stand-in for an MCP server so the demo runs without one: it serves the bundled apps and answers the few tools
 * they call. The real thing is `SdkMcpAppsHostClient` from `casement-mcp-sdk`.
 */
internal class DemoClient(
    private val readFile: suspend (String) -> String,
) : McpAppsHostClient {
    private val both = setOf(ToolVisibility.Model, ToolVisibility.App)
    private var tickets = 0

    override val tools: ToolCatalog =
        ToolCatalog.of(
            mapOf(
                "show-time" to both,
                "show-form" to both,
                "show-chart" to both,
                "show-hostile" to both,
                "get-time" to setOf(ToolVisibility.App),
                "submit-form" to setOf(ToolVisibility.App),
                "refresh-data" to setOf(ToolVisibility.App),
                "model-only" to setOf(ToolVisibility.Model),
            ),
        )

    override suspend fun readResource(uri: String): UiResource {
        val file = FILES[uri] ?: throw BridgeRejection("Unknown resource $uri")
        return UiResource(uri, readFile(file))
    }

    override suspend fun callTool(
        name: String,
        arguments: JsonObject?,
    ): JsonObject =
        when (name) {
            "get-time" -> {
                result("The time is now", buildJsonObject { put("time", Clock.System.now().toString()) })
            }

            "submit-form" -> {
                tickets += 1
                result("Saved", buildJsonObject { put("ticket", "T-${1000 + tickets}") })
            }

            "refresh-data" -> {
                result("Refreshed", series())
            }

            else -> {
                throw BridgeRejection("Unknown tool $name")
            }
        }

    internal fun series(): JsonObject =
        buildJsonObject {
            put(
                "series",
                buildJsonArray {
                    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun").forEach { label ->
                        add(
                            buildJsonObject {
                                put("label", label)
                                put("value", Random.nextInt(20, 100))
                            },
                        )
                    }
                },
            )
        }

    private fun result(
        text: String,
        structured: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                },
            )
            put("structuredContent", structured)
        }

    internal companion object {
        val FILES =
            mapOf(
                "ui://demo/time" to "get-time.html",
                "ui://demo/form" to "form.html",
                "ui://demo/chart" to "chart.html",
                "ui://demo/hostile" to "hostile.html",
            )
    }
}
