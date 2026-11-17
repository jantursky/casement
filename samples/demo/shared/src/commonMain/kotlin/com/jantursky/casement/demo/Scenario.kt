package com.jantursky.casement.demo

import com.jantursky.casement.client.McpAppsHostClient
import com.jantursky.casement.model.ToolUi
import com.jantursky.casement.model.ToolWithUi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One thing the demo assistant can show: a tool with a UI, the connection it belongs to, and how to get the result
 * the app is opened with (a built-in value, or a real `tools/call` on a server).
 */
internal class Scenario(
    val id: String,
    val label: String,
    val reply: String,
    val tool: ToolWithUi,
    val client: McpAppsHostClient,
    val result: suspend () -> JsonObject?,
)

internal fun builtInScenarios(client: DemoClient): List<Scenario> {
    fun tool(
        id: String,
        label: String,
        uri: String,
    ) = ToolWithUi(
        name = "show-$id",
        ui = ToolUi(uri),
        definition =
            buildJsonObject {
                put("name", "show-$id")
                put("description", label)
            },
    )

    return listOf(
        Scenario(
            "time",
            "Show the time",
            "Here is the server time.",
            tool("time", "Show the time", "ui://demo/time"),
            client,
        ) {
            buildJsonObject { put("structuredContent", buildJsonObject { put("time", "2026-11-17T10:00:00Z") }) }
        },
        Scenario(
            "form",
            "Contact form",
            "Fill this in and I will pass it on.",
            tool("form", "Contact form", "ui://demo/form"),
            client,
        ) {
            null
        },
        Scenario(
            "chart",
            "Sales chart",
            "Sales for the last six months.",
            tool("chart", "Sales chart", "ui://demo/chart"),
            client,
        ) {
            buildJsonObject { put("structuredContent", client.series()) }
        },
        Scenario(
            "hostile",
            "Hostile app",
            "This app tries to escape. Watch the blocked list.",
            tool("hostile", "Hostile app", "ui://demo/hostile"),
            client,
        ) { null },
    )
}
