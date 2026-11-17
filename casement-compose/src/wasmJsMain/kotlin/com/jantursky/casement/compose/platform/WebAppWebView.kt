@file:OptIn(ExperimentalWasmJsInterop::class, ExperimentalComposeUiApi::class)

package com.jantursky.casement.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.bridge.BlockedKind
import com.jantursky.casement.compose.RendererFailure
import com.jantursky.casement.compose.WebViewSpec
import com.jantursky.casement.compose.cspDetail
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.MessageChannel
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.toJsString

/**
 * Where the sandbox proxy page lives. The page is `web-sandbox/sandbox.html` from the Casement repository. It has to
 * be served from an origin that is not the host page's origin, otherwise the app could reach the host's cookies,
 * storage and DOM. Provide it with `CompositionLocalProvider(LocalWebSandbox provides WebSandbox(url))`.
 */
public class WebSandbox(
    public val proxyUrl: String,
)

/** The sandbox proxy to use on the web. Without one, apps are not rendered. */
public val LocalWebSandbox: androidx.compose.runtime.ProvidableCompositionLocal<WebSandbox?> =
    compositionLocalOf { null }

@Composable
internal actual fun PlatformAppWebView(
    spec: WebViewSpec,
    modifier: Modifier,
) {
    val sandbox = LocalWebSandbox.current
    val proxyOrigin = sandbox?.let { originOf(it.proxyUrl) }
    if (sandbox == null || proxyOrigin == null || proxyOrigin == window.location.origin) {
        // Same origin would hand the app the host page. Refuse, as the spec requires a different origin.
        LaunchedEffect(spec) { spec.onFailed(RendererFailure.Unsupported) }
        return
    }
    HtmlElementView(
        factory = { createSandboxFrame(sandbox.proxyUrl, proxyOrigin, spec) },
        modifier = modifier,
        onRelease = { releaseFrame(it, spec) },
    )
}

private const val PORT_FIELD = "casementPort"

private fun originOf(url: String): String? =
    runCatching { urlOrigin(url).toString() }.getOrNull()?.takeIf { it != "null" }

private fun urlOrigin(url: String): JsString = js("new URL(url, window.location.href).origin")

private fun setField(
    target: JsAny,
    key: String,
    value: JsAny?,
) {
    js("target[key] = value")
}

private fun getField(
    target: JsAny,
    key: String,
): JsAny? = js("target[key]")

private fun initMessage(
    html: String,
    csp: String,
    allow: String,
): JsAny = js("({ casement: 'init', html: html, csp: csp, allow: allow })")

private fun postWithPort(
    target: JsAny,
    message: JsAny,
    origin: String,
    port: JsAny,
) {
    js("target.postMessage(message, origin, [port])")
}

private fun portPost(
    port: JsAny,
    text: String,
) {
    js("port.postMessage(text)")
}

private fun portOnMessage(
    port: JsAny,
    handler: (String) -> Unit,
) {
    js("port.onmessage = function (e) { handler(e.data); }")
}

private fun portClose(port: JsAny) {
    js("port.close()")
}

private fun controlOf(text: String): String? =
    if (text.startsWith("{\"casement\"")) text.substringAfter("\"casement\":\"").substringBefore('"') else null

private fun allowAttribute(spec: WebViewSpec): String =
    listOfNotNull(
        "camera".takeIf { spec.permissions.camera },
        "microphone".takeIf { spec.permissions.microphone },
        "geolocation".takeIf { spec.permissions.geolocation },
        "clipboard-write".takeIf { spec.permissions.clipboardWrite },
    ).joinToString("; ")

/** The outer iframe: the proxy page, cross-origin, which hosts the app in a nested iframe. */
private fun createSandboxFrame(
    proxyUrl: String,
    proxyOrigin: String,
    spec: WebViewSpec,
): HTMLIFrameElement {
    val frame = document.createElement("iframe") as HTMLIFrameElement
    frame.setAttribute("sandbox", "allow-scripts allow-same-origin")
    frame.setAttribute("referrerpolicy", "origin")
    frame.setAttribute("title", "MCP App")
    frame.style.setProperty("border", "0")
    frame.style.setProperty("background", "transparent")
    frame.style.setProperty("color-scheme", "normal")

    val channel = MessageChannel()
    val hostPort: JsAny = channel.port1
    setField(frame, PORT_FIELD, hostPort)
    portOnMessage(hostPort) { text ->
        when (controlOf(text)) {
            null -> spec.channel.fromApp(text)
            "csp" -> spec.onBlocked(BlockedAction(BlockedKind.Csp, cspDetail(text)))
            "navigation" -> spec.onBlocked(BlockedAction(BlockedKind.Navigation, "The app tried to navigate away"))
        }
    }
    spec.channel.attach { portPost(hostPort, it) }

    // The proxy announces itself once its script has run. Only that frame, from that origin, is believed.
    lateinit var listener: (Event) -> Unit
    listener = { event ->
        val message = event as MessageEvent
        if (message.source == frame.contentWindow && message.origin == proxyOrigin && isProxyReady(message.data)) {
            window.removeEventListener("message", listener)
            postWithPort(
                frame.contentWindow!!.unsafeCast<JsAny>(),
                initMessage(spec.html, spec.csp, allowAttribute(spec)),
                proxyOrigin,
                channel.port2,
            )
        }
    }
    window.addEventListener("message", listener)
    frame.src = proxyUrl
    return frame
}

private fun isProxyReady(data: JsAny?): Boolean = js("data != null && data.casement === 'proxy-ready'")

private fun releaseFrame(
    frame: HTMLIFrameElement,
    spec: WebViewSpec,
) {
    spec.channel.detach()
    getField(frame, PORT_FIELD)?.let(::portClose)
    frame.src = "about:blank"
    frame.remove()
}
