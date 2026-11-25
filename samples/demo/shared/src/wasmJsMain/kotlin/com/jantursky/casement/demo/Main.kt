package com.jantursky.casement.demo

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.jantursky.casement.compose.platform.LocalWebSandbox
import com.jantursky.casement.compose.platform.WebSandbox
import kotlinx.browser.document
import kotlinx.browser.window

private fun queryParams(): Map<String, String> =
    window.location.search.removePrefix("?").split("&").filter { it.isNotEmpty() }.associate {
        it.substringBefore("=") to it.substringAfter("=", "")
    }

/** The sandbox proxy is served from another origin: port 8081 here, or `?sandbox=` to point elsewhere. */
private fun sandboxUrl(query: Map<String, String>): String =
    query["sandbox"]?.takeIf { it.isNotEmpty() } ?: "http://localhost:8081/sandbox.html"

/** `?run=policy,csp` opens those scenarios on start, the same way `autoRun` does. The browser tests use it. */
private fun autoRun(query: Map<String, String>): List<String> =
    query["run"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val query = queryParams()
    ComposeViewport(document.body!!) {
        CompositionLocalProvider(LocalWebSandbox provides WebSandbox(sandboxUrl(query))) {
            DemoApp(autoRun = autoRun(query))
        }
    }
}
