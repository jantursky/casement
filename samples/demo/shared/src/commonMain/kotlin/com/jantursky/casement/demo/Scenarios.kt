package com.jantursky.casement.demo

import com.jantursky.casement.model.ToolUi
import com.jantursky.casement.model.ToolWithUi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One thing the demo assistant can show: a tool with a UI and the result it comes back with. */
internal class Scenario(
    val id: String,
    val label: String,
    val reply: String,
    val resourceUri: String,
    val hostile: Boolean = false,
    val result: (DemoClient) -> JsonObject?,
) {
    val tool: ToolWithUi =
        ToolWithUi(
            name = "show-$id",
            ui = ToolUi(resourceUri),
            definition =
                buildJsonObject {
                    put("name", "show-$id")
                    put("description", label)
                },
        )
}

internal val SCENARIOS =
    listOf(
        Scenario("time", "Show the time", "Here is the server time.", "ui://demo/time") {
            buildJsonObject { put("time", "2026-11-17T10:00:00Z") }.let { structured ->
                buildJsonObject { put("structuredContent", structured) }
            }
        },
        Scenario("form", "Contact form", "Fill this in and I will pass it on.", "ui://demo/form") { null },
        Scenario("chart", "Sales chart", "Sales for the last six months.", "ui://demo/chart") {
            buildJsonObject { put("structuredContent", it.series()) }
        },
        Scenario(
            "hostile",
            "Hostile app",
            "This app tries to escape. Watch the blocked list.",
            "ui://demo/hostile",
            hostile = true,
        ) { null },
    )
