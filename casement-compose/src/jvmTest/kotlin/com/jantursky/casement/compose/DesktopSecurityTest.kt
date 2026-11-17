package com.jantursky.casement.compose

import androidx.compose.ui.awt.ComposeWindow
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.bridge.BlockedKind
import com.jantursky.casement.bridge.ToolCatalog
import com.jantursky.casement.client.McpAppsHostClient
import com.jantursky.casement.model.ToolUi
import com.jantursky.casement.model.ToolVisibility
import com.jantursky.casement.model.ToolWithUi
import com.jantursky.casement.model.UiResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the hostile fixture in the real desktop renderer (OpenJFX WebView). It needs a display, so it is skipped
 * where there is none.
 */
class DesktopSecurityTest {
    private val calls = CopyOnWriteArrayList<String>()

    private val client =
        object : McpAppsHostClient {
            override val tools =
                ToolCatalog.of(
                    mapOf(
                        "hostile" to setOf(ToolVisibility.Model, ToolVisibility.App),
                        "model-only" to setOf(ToolVisibility.Model),
                    ),
                )

            override suspend fun readResource(uri: String) =
                UiResource(uri, File("../fixtures/hostile/hostile.html").readText())

            override suspend fun callTool(
                name: String,
                arguments: JsonObject?,
            ): JsonObject {
                calls += name
                return JsonObject(emptyMap())
            }
        }

    @Test
    fun theHostileAppIsContainedAndReported() {
        if (GraphicsEnvironment.isHeadless()) return
        val blocked = CopyOnWriteArrayList<BlockedAction>()
        val tool =
            ToolWithUi(
                name = "hostile",
                ui = ToolUi("ui://hostile/app.html"),
                definition = Json.parseToJsonElement("""{"name":"hostile"}""").jsonObject,
            )
        var window: ComposeWindow? = null
        SwingUtilities.invokeAndWait {
            window =
                ComposeWindow().apply {
                    setSize(480, 640)
                    setContent {
                        McpAppView(
                            tool = tool,
                            toolInput = null,
                            toolResult = null,
                            client = client,
                            onBlocked = { blocked += it },
                        )
                    }
                    isVisible = true
                }
        }
        try {
            val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
            while (BlockedKind.Navigation !in blocked.map { it.kind } && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MILLIS)
            }
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
        }
        val kinds = blocked.map { it.kind }.toSet()
        listOf(
            BlockedKind.ToolUnknown,
            BlockedKind.ToolNotVisible,
            BlockedKind.Link,
            BlockedKind.DisplayMode,
            BlockedKind.Csp,
            BlockedKind.Navigation,
        ).forEach { assertTrue(it in kinds, "$it missing from $blocked") }
        assertEquals(emptyList(), calls.toList())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
        const val POLL_MILLIS = 250L
    }
}
