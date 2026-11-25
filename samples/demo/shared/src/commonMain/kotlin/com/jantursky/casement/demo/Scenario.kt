package com.jantursky.casement.demo

import com.jantursky.casement.client.McpAppsHostClient
import com.jantursky.casement.model.DisplayMode
import com.jantursky.casement.model.ToolUi
import com.jantursky.casement.model.ToolWithUi
import com.jantursky.casement.model.UiPermissions
import com.jantursky.casement.policy.CspBuilder
import com.jantursky.casement.policy.HostPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One thing the demo assistant can show: a tool with a UI, the connection it belongs to, and how to get the result
 * the app is opened with (a built-in value, or a real `tools/call` on a server).
 *
 * [policy] and [displayModes] are what the host gives this one app, so each scenario can show a different rule.
 */
internal class Scenario(
    val id: String,
    val label: String,
    val reply: String,
    val tool: ToolWithUi,
    val client: McpAppsHostClient,
    val policy: HostPolicy = HostPolicy(),
    val displayModes: List<DisplayMode> = listOf(DisplayMode.Inline),
    val result: suspend () -> JsonObject?,
)

/** The host grants the camera and nothing else, so an app that asks for camera and microphone gets only the camera. */
private val capabilityPolicy = HostPolicy(grantedPermissions = UiPermissions(camera = true))

/** The CSP header the host builds from the demo's content-policy app, and the domains it dropped. */
private fun cspReply(): String {
    val built = CspBuilder.build(DemoClient.CSP)
    val dropped = if (built.rejected.isEmpty()) "Nothing was dropped." else "Dropped: ${built.rejected.joinToString()}."
    return "The host sends: ${built.header}\n$dropped"
}

/** What the host grants of the permissions the capabilities app declares. */
private fun capabilitiesReply(): String {
    val granted = capabilityPolicy.resolvePermissions(DemoClient.PERMISSIONS)
    return "The app asks for camera and microphone. Camera granted: ${granted.camera}. " +
        "Microphone granted: ${granted.microphone}."
}

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
        Scenario(
            "policy",
            "Link policy",
            "This app asks to open two links. The host allows only https.",
            tool("policy", "Link policy", "ui://demo/policy"),
            client,
            policy = HostPolicy(allowedLinkSchemes = setOf("https")),
        ) { null },
        Scenario(
            "display",
            "Fullscreen request",
            "This app asks for fullscreen. The host offers fullscreen in this demo.",
            tool("display", "Fullscreen request", "ui://demo/display"),
            client,
            displayModes = listOf(DisplayMode.Inline, DisplayMode.Fullscreen),
        ) { null },
        Scenario(
            "csp",
            "Content policy",
            cspReply(),
            tool("csp", "Content policy", "ui://demo/csp"),
            client,
        ) { null },
        Scenario(
            "capabilities",
            "Permissions",
            capabilitiesReply(),
            tool("capabilities", "Permissions", "ui://demo/capabilities"),
            client,
            policy = capabilityPolicy,
        ) { null },
        Scenario(
            "theme",
            "Host theme",
            "Switch the theme above. This app follows the host.",
            tool("theme", "Host theme", "ui://demo/theme"),
            client,
        ) { null },
    )
}
