package com.jantursky.casement.demo

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.jantursky.casement.compose.LocalWebSandbox
import com.jantursky.casement.compose.WebSandbox
import kotlinx.browser.document
import kotlinx.browser.window

/** The sandbox proxy is served from another origin: port 8081 here, or `?sandbox=` to point elsewhere. */
private fun sandboxUrl(): String {
    val query =
        window.location.search.removePrefix("?").split("&").associate {
            it.substringBefore("=") to it.substringAfter("=", "")
        }
    return query["sandbox"]?.takeIf { it.isNotEmpty() } ?: "http://localhost:8081/sandbox.html"
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        CompositionLocalProvider(LocalWebSandbox provides WebSandbox(sandboxUrl())) { DemoApp() }
    }
}
