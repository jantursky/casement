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
import com.jantursky.casement.model.ToolUi
import com.jantursky.casement.model.ToolWithUi
import com.jantursky.casement.model.UiResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Real ext-apps examples, built unmodified by `scripts/build-fixtures.sh`, run through McpAppView in a real WebView
 * with the tool results their servers returned. Apps that were not built are skipped.
 */
@RunWith(AndroidJUnit4::class)
class ConformanceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val blocked = CopyOnWriteArrayList<BlockedAction>()

    private class Recording(
        val html: String,
        val tools: JsonArray,
        val results: JsonObject,
    ) {
        val calls = CopyOnWriteArrayList<String>()

        val client =
            object : McpAppsHostClient {
                override val tools: ToolCatalog =
                    ToolCatalog.of(
                        this@Recording.tools.associate { tool ->
                            val definition = tool.jsonObject
                            definition["name"]!!.jsonPrimitive.content to
                                (ToolUi.fromToolMeta(definition["_meta"] as? JsonObject)?.visibility ?: all())
                        },
                    )

                override suspend fun readResource(uri: String) = UiResource(uri, html)

                override suspend fun callTool(
                    name: String,
                    arguments: JsonObject?,
                ): JsonObject {
                    calls += name
                    return results[name] as? JsonObject ?: error("No recording for $name")
                }
            }

        private fun all() =
            com.jantursky.casement.model.ToolVisibility.entries
                .toSet()
    }

    private fun load(name: String): Recording {
        val loader = ConformanceTest::class.java.classLoader!!
        val html = loader.getResourceAsStream("$name.html")
        assumeTrue("$name.html was not built (scripts/build-fixtures.sh)", html != null)
        val json =
            Json.parseToJsonElement(
                checkNotNull(loader.getResourceAsStream("$name.json")).bufferedReader().readText(),
            )
        return Recording(
            html!!.bufferedReader().readText(),
            json.jsonObject["tools"]!!.jsonArray,
            json.jsonObject["results"]!!.jsonObject,
        )
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

    /** Shows the example's main tool with its recorded result and waits until the app has drawn something. */
    private fun run(name: String): Recording {
        val recording = load(name)
        val main =
            recording.tools.map { it.jsonObject }.first {
                ToolUi.fromToolMeta(
                    it["_meta"] as? JsonObject,
                ) != null
            }
        val ui = ToolUi.fromToolMeta(main["_meta"] as? JsonObject)!!
        val tool = ToolWithUi(main["name"]!!.jsonPrimitive.content, ui, main)
        rule.setContent {
            McpAppView(
                tool = tool,
                toolInput = JsonObject(emptyMap()),
                toolResult = recording.results[tool.name] as JsonObject,
                client = recording.client,
                modifier = Modifier.fillMaxWidth(),
                onBlocked = { blocked += it },
            )
        }
        rule.waitUntil(WAIT_MILLIS) { (js("document.body.innerText.trim().length")?.toIntOrNull() ?: 0) > MIN_TEXT }
        return recording
    }

    /**
     * The apps use zod, which probes whether `new Function` works and catches the failure. The spec's default policy
     * has no `unsafe-eval`, so the probe is reported as a CSP violation. It is harmless and the only one allowed.
     */
    private fun assertNoViolations() {
        val unexpected =
            blocked.filterNot {
                it.kind == BlockedKind.Csp &&
                    it.detail == "The browser blocked eval (script-src)"
            }
        assertEquals(emptyList(), unexpected, "Unexpected: $unexpected")
    }

    @Test
    fun budgetAllocatorRenders() {
        run("budget-allocator")
        assertNoViolations()
    }

    @Test
    fun cohortHeatmapRenders() {
        run("cohort-heatmap")
        assertNoViolations()
    }

    @Test
    fun integrationAppRenders() {
        run("integration")
        assertNoViolations()
    }

    @Test
    fun systemMonitorPollsItsAppOnlyTool() {
        val recording = run("system-monitor")
        rule.waitUntil(WAIT_MILLIS) { "poll-system-stats" in recording.calls }
        assertEquals(emptyList(), blocked.filter { it.kind == BlockedKind.ToolNotVisible })
        assertNoViolations()
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
        const val MIN_TEXT = 20
    }
}
