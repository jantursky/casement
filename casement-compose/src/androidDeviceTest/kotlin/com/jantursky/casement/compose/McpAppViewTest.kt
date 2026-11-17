package com.jantursky.casement.compose

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jantursky.casement.bridge.AppMessage
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.bridge.ToolCatalog
import com.jantursky.casement.client.McpAppsHostClient
import com.jantursky.casement.model.HostContext
import com.jantursky.casement.model.Theme
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** Runs the real ext-apps `get-time` example through McpAppView in a real WebView. */
@RunWith(AndroidJUnit4::class)
class McpAppViewTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val tool =
        ToolWithUi(
            name = "get-time",
            ui = ToolUi("ui://get-time/mcp-app.html"),
            definition = Json.parseToJsonElement("""{"name":"get-time","inputSchema":{"type":"object"}}""").jsonObject,
        )
    private val result: JsonObject =
        Json
            .parseToJsonElement(
                """{"content":[{"type":"text","text":"2026-11-17T10:00:00Z"}],"structuredContent":{"time":"2026-11-17T10:00:00Z"}}""",
            ).jsonObject

    private class FakeClient(
        private val html: String,
    ) : McpAppsHostClient {
        val calls = mutableListOf<String>()
        override val tools = ToolCatalog.of(mapOf("get-time" to setOf(ToolVisibility.Model, ToolVisibility.App)))

        override suspend fun readResource(uri: String) = UiResource(uri, html)

        override suspend fun callTool(
            name: String,
            arguments: JsonObject?,
        ): JsonObject {
            calls += name
            return Json
                .parseToJsonElement(
                    """{"content":[],"structuredContent":{"time":"2030-01-01T00:00:00Z"}}""",
                ).jsonObject
        }
    }

    private val client =
        FakeClient(
            checkNotNull(McpAppViewTest::class.java.classLoader!!.getResourceAsStream("get-time.html"))
                .bufferedReader()
                .readText(),
        )
    private val messages = mutableListOf<AppMessage>()
    private val links = mutableListOf<String>()
    private val blocked = mutableListOf<BlockedAction>()
    private val context = mutableStateOf(HostContext(theme = Theme.Light))

    private fun show(toolResult: JsonObject? = result) {
        rule.setContent {
            McpAppView(
                tool = tool,
                toolInput = buildJsonObject {},
                toolResult = toolResult,
                client = client,
                modifier = Modifier.fillMaxWidth(),
                hostContext = context.value,
                onMessage = { messages += it },
                onOpenLink = { links += it },
                onBlocked = { blocked += it },
            )
        }
    }

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

    /** Evaluates [script] in the page and returns its JSON-encoded result. */
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

    private fun waitForJs(
        script: String,
        expected: String,
    ) = rule.waitUntil(WAIT_MILLIS) { js(script) == expected }

    @Test
    fun handshakeCompletesAndToolResultReachesTheApp() {
        show()
        waitForJs("document.getElementById('server-time').textContent", "\"2026-11-17T10:00:00Z\"")
    }

    @Test
    fun appCanCallATool() {
        show()
        waitForJs("document.getElementById('server-time').textContent", "\"2026-11-17T10:00:00Z\"")
        js("document.getElementById('get-time-btn').click()")
        rule.waitUntil(WAIT_MILLIS) { client.calls == listOf("get-time") }
        waitForJs("document.getElementById('server-time').textContent", "\"2030-01-01T00:00:00Z\"")
    }

    @Test
    fun appCanSendAMessageAndOpenALink() {
        show()
        waitForJs("document.getElementById('server-time').textContent", "\"2026-11-17T10:00:00Z\"")
        js("document.getElementById('send-message-btn').click()")
        js("document.getElementById('open-link-btn').click()")
        rule.waitUntil(WAIT_MILLIS) { messages.isNotEmpty() && links.isNotEmpty() }
        assertEquals("user", messages.single().role)
        assertEquals(listOf("https://modelcontextprotocol.io/"), links)
    }

    @Test
    fun themeChangesReachTheApp() {
        show()
        waitForJs("document.documentElement.getAttribute('data-theme')", "\"light\"")
        context.value = HostContext(theme = Theme.Dark)
        waitForJs("document.documentElement.getAttribute('data-theme')", "\"dark\"")
    }

    private companion object {
        const val WAIT_MILLIS = 20_000L
    }
}
