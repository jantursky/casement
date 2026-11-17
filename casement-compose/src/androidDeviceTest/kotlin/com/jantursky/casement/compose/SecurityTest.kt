package com.jantursky.casement.compose

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.bridge.BlockedKind
import com.jantursky.casement.bridge.ToolCatalog
import com.jantursky.casement.client.McpAppsHostClient
import com.jantursky.casement.jsonrpc.JsonRpcErrorCode
import com.jantursky.casement.model.ToolUi
import com.jantursky.casement.model.ToolVisibility
import com.jantursky.casement.model.ToolWithUi
import com.jantursky.casement.model.UiResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the hostile fixture in a real WebView and checks that every attack is stopped and reported. */
@RunWith(AndroidJUnit4::class)
class SecurityTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val tool =
        ToolWithUi(
            name = "hostile",
            ui = ToolUi("ui://hostile/app.html"),
            definition = Json.parseToJsonElement("""{"name":"hostile","inputSchema":{"type":"object"}}""").jsonObject,
        )

    private class Client(
        private val html: String,
    ) : McpAppsHostClient {
        val calls = CopyOnWriteArrayList<String>()
        override val tools =
            ToolCatalog.of(
                mapOf(
                    "hostile" to setOf(ToolVisibility.Model, ToolVisibility.App),
                    "model-only" to setOf(ToolVisibility.Model),
                ),
            )

        override suspend fun readResource(uri: String) = UiResource(uri, html)

        override suspend fun callTool(
            name: String,
            arguments: JsonObject?,
        ): JsonObject {
            calls += name
            return JsonObject(emptyMap())
        }
    }

    private val client =
        Client(
            checkNotNull(SecurityTest::class.java.classLoader!!.getResourceAsStream("hostile.html"))
                .bufferedReader()
                .readText(),
        )
    private val blocked = CopyOnWriteArrayList<BlockedAction>()

    private fun webView(): WebView {
        fun find(view: View): WebView? =
            when (view) {
                is WebView -> view
                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                else -> null
            }
        rule.waitUntil(WAIT_MILLIS) { find(rule.activity.window.decorView) != null }
        return find(rule.activity.window.decorView)!!
    }

    private fun js(script: String): String? {
        val view = webView()
        val latch = CountDownLatch(1)
        var value: String? = null
        rule.runOnUiThread {
            view.evaluateJavascript(script) {
                value = it
                latch.countDown()
            }
        }
        latch.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)
        return value
    }

    private fun runHostileApp() {
        rule.setContent {
            McpAppView(
                tool = tool,
                toolInput = buildJsonObject {},
                toolResult = null,
                client = client,
                modifier = Modifier.fillMaxWidth(),
                onBlocked = { blocked += it },
            )
        }
        rule.waitUntil(WAIT_MILLIS) { js("window.__probe && window.__probe.done") == "true" }
        rule.waitUntil(WAIT_MILLIS) { BlockedKind.Navigation in blocked.map { it.kind } }
    }

    private fun probe(key: String) = js("String(window.__probe['$key'])")?.trim('"')

    @Test
    fun unknownAndHiddenToolsAreRefusedAndNeverReachTheClient() {
        runHostileApp()
        assertEquals(JsonRpcErrorCode.REJECTED.toString(), probe("unknownTool"))
        assertEquals(JsonRpcErrorCode.REJECTED.toString(), probe("hiddenTool"))
        assertEquals(emptyList(), client.calls.toList())
        val kinds = blocked.map { it.kind }
        assertTrue(BlockedKind.ToolUnknown in kinds && BlockedKind.ToolNotVisible in kinds, "$blocked")
    }

    @Test
    fun badLinksAndDisplayModesAreRefused() {
        runHostileApp()
        assertTrue(probe("badLink") in setOf("true", "error"), probe("badLink"))
        val kinds = blocked.map { it.kind }
        assertTrue(BlockedKind.Link in kinds, "$blocked")
        assertTrue(BlockedKind.DisplayMode in kinds || probe("displayMode") == "error", "$blocked")
    }

    @Test
    fun windowsNetworkAndCameraAreBlocked() {
        runHostileApp()
        // Android hands back a dead window handle; the host refused the window and reported it.
        assertTrue(probe("open") in setOf("blocked", "handle"), probe("open"))
        assertEquals("blocked", probe("fetch"))
        assertEquals("denied", probe("media"))
        val kinds = blocked.map { it.kind }
        assertTrue(BlockedKind.Window in kinds, "$blocked")
        assertTrue(BlockedKind.Csp in kinds || BlockedKind.Network in kinds, "$blocked")
    }

    @Test
    fun navigationAwayIsBlockedAndTheDocumentStays() {
        runHostileApp()
        assertTrue(BlockedKind.Navigation in blocked.map { it.kind }, "$blocked")
        assertEquals("\"done\"", js("document.getElementById('status').textContent"))
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
    }
}
