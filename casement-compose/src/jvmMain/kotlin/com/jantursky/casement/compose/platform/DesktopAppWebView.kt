package com.jantursky.casement.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.bridge.BlockedKind
import com.jantursky.casement.compose.RendererFailure
import com.jantursky.casement.compose.WebViewSpec
import com.jantursky.casement.compose.bridgeShim
import com.jantursky.casement.policy.OriginMatcher
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRendering
import org.cef.callback.CefCallback
import org.cef.callback.CefMediaAccessCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefPermissionHandler
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

@Composable
internal actual fun PlatformAppWebView(
    spec: WebViewSpec,
    modifier: Modifier,
) {
    val sandbox = remember(spec) { DesktopSandbox.create(spec) }
    if (sandbox == null) {
        LaunchedEffect(spec) { spec.onFailed(RendererFailure.Unsupported) }
        return
    }
    DisposableEffect(sandbox) { onDispose { sandbox.close() } }
    SwingPanel(factory = { sandbox.browser.uiComponent }, modifier = modifier)
}

private const val BRIDGE = "cefQuery"
private const val OK = 200
private const val NOT_FOUND = 404
private val instances = AtomicInteger()

/**
 * A Chromium (CEF) browser that can run untrusted HTML and talk to the host, and nothing else.
 *
 * The document is served by a resource handler from a made-up https origin, so it gets a real origin and a
 * `Content-Security-Policy` header the page cannot remove. CEF has no document-start script, so the bridge shim
 * is put in front of the document's own markup. Every request outside the declared origins is cancelled, and
 * navigation, popups, dialogs and permission prompts are refused.
 */
private class DesktopSandbox private constructor(
    private val spec: WebViewSpec,
    private val client: KCEFClient,
) {
    private val origin = "https://app-${instances.incrementAndGet()}.casement.invalid"
    private val documentUrl = "$origin/app.html"
    private var documentLoaded = false
    private val router = CefMessageRouter.create()

    val browser: KCEFBrowser

    init {
        router.addHandler(
            object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(
                    browser: CefBrowser,
                    frame: CefFrame,
                    queryId: Long,
                    request: String,
                    persistent: Boolean,
                    callback: CefQueryCallback,
                ): Boolean {
                    if (frame.isMain && frame.url == documentUrl) onMessage(request)
                    callback.success("")
                    return true
                }
            },
            true,
        )
        client.addMessageRouter(router)
        client.addRequestHandler(RequestGate())
        client.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: CefBrowser,
                    frame: CefFrame,
                    targetUrl: String,
                    targetFrameName: String,
                ): Boolean {
                    blocked(BlockedKind.Window, "Blocked window.open")
                    return true
                }
            },
        )
        client.addPermissionHandler(
            object : CefPermissionHandler {
                override fun onRequestMediaAccessPermission(
                    browser: CefBrowser,
                    frame: CefFrame,
                    requestingUrl: String,
                    requestedPermissions: Int,
                    callback: CefMediaAccessCallback,
                ): Boolean {
                    blocked(BlockedKind.Permission, "Denied camera or microphone")
                    callback.Cancel()
                    return true
                }
            },
        )
        client.addJSDialogHandler(
            object : org.cef.handler.CefJSDialogHandlerAdapter() {
                override fun onJSDialog(
                    browser: CefBrowser,
                    originUrl: String,
                    dialogType: org.cef.handler.CefJSDialogHandler.JSDialogType,
                    messageText: String,
                    defaultPromptText: String,
                    callback: org.cef.callback.CefJSDialogCallback,
                    suppressMessage: BoolRef,
                ): Boolean {
                    suppressMessage.set(true)
                    return false
                }
            },
        )
        browser = client.createBrowser(documentUrl, CefRendering.DEFAULT, true)
        spec.channel.attach { text ->
            val literal = Json.encodeToString(String.serializer(), text)
            browser.mainFrame.executeJavaScript(
                "window.__casementReceive && window.__casementReceive($literal)",
                documentUrl,
                0,
            )
        }
    }

    fun close() {
        spec.channel.detach()
        browser.dispose()
        router.dispose()
        client.dispose()
    }

    private fun onMessage(text: String) {
        val control = controlOf(text)
        SwingUtilities.invokeLater {
            when (control) {
                null -> spec.channel.fromApp(text)
                "csp" -> spec.onBlocked(BlockedAction(BlockedKind.Csp, "The browser blocked a CSP violation"))
            }
        }
    }

    private fun blocked(
        kind: BlockedKind,
        detail: String,
    ) = SwingUtilities.invokeLater { spec.onBlocked(BlockedAction(kind, detail)) }

    /** The page as served: the shim first, so it runs before anything the app brings. */
    private fun document(): ByteArray {
        val shim =
            bridgeShim(
                send = "function (s) { window.$BRIDGE({ request: s }); }",
                receive = "window.__casementReceive = deliver;",
            )
        return injectScript(spec.html, shim).toByteArray(Charsets.UTF_8)
    }

    private inner class RequestGate : CefRequestHandlerAdapter() {
        override fun onBeforeBrowse(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            userGesture: Boolean,
            isRedirect: Boolean,
        ): Boolean {
            if (!documentLoaded && request.url == documentUrl) {
                documentLoaded = true
                return false
            }
            blocked(BlockedKind.Navigation, "Blocked navigation to ${request.url.substringBefore(':')}")
            return true
        }

        override fun getResourceRequestHandler(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String,
            disableDefaultHandling: BoolRef,
        ): CefResourceRequestHandler = ResourceGate()

        override fun onRenderProcessTerminated(
            browser: CefBrowser,
            status: CefRequestHandler.TerminationStatus,
        ) {
            SwingUtilities.invokeLater { spec.onFailed(RendererFailure.ProcessGone) }
        }
    }

    private inner class ResourceGate : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
        ): Boolean {
            val url = request.url
            val allowed =
                url == documentUrl ||
                    url.startsWith("data:") ||
                    url.startsWith("blob:") ||
                    OriginMatcher.isAllowed(spec.allowedOrigins, url)
            if (!allowed && !url.startsWith("$origin/")) {
                blocked(BlockedKind.Network, "Blocked request to ${url.substringAfter("://").substringBefore('/')}")
            }
            return !allowed && !url.startsWith("$origin/")
        }

        override fun getResourceHandler(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
        ): CefResourceHandler? =
            when {
                request.url == documentUrl && request.method == "GET" -> StaticResponse(OK, document(), headers())
                request.url.startsWith("$origin/") -> StaticResponse(NOT_FOUND, ByteArray(0), emptyMap())
                else -> null
            }
    }

    private fun headers(): Map<String, String> =
        mapOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Security-Policy" to spec.csp,
            "Permissions-Policy" to spec.permissions.toPermissionsPolicy(),
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Cache-Control" to "no-store",
        )

    companion object {
        /** A sandbox, or null while the browser engine is not initialized (see `initializeDesktopRenderer`). */
        fun create(spec: WebViewSpec): DesktopSandbox? {
            val client = KCEF.newClientOrNullBlocking() ?: return null
            return DesktopSandbox(spec, client)
        }
    }
}

private class StaticResponse(
    private val status: Int,
    private val body: ByteArray,
    private val headers: Map<String, String>,
) : CefResourceHandler {
    private var offset = 0

    override fun processRequest(
        request: CefRequest,
        callback: CefCallback,
    ): Boolean {
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(
        response: CefResponse,
        responseLength: IntRef,
        redirectUrl: StringRef,
    ) {
        response.status = status
        response.mimeType = "text/html"
        headers.forEach { (name, value) -> response.setHeaderByName(name, value, true) }
        responseLength.set(body.size)
    }

    override fun readResponse(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefCallback,
    ): Boolean {
        val count = minOf(bytesToRead, body.size - offset)
        if (count <= 0) return false
        System.arraycopy(body, offset, dataOut, 0, count)
        offset += count
        bytesRead.set(count)
        return true
    }

    override fun cancel() = Unit
}

/** Puts [script] at the start of [html]: after the doctype if there is one, so quirks mode is not triggered. */
internal fun injectScript(
    html: String,
    script: String,
): String {
    val tag = "<script>$script</script>"
    val doctype = Regex("^\\s*(<!--[\\s\\S]*?-->\\s*)*<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)
    return if (doctype !=
        null
    ) {
        html.substring(0, doctype.range.last + 1) + tag + html.substring(doctype.range.last + 1)
    } else {
        tag + html
    }
}

/** The `casement` field of a control message, or null for anything else (which is JSON-RPC for the bridge). */
private fun controlOf(text: String): String? =
    if (text.startsWith("{\"casement\"")) {
        runCatching { (Json.parseToJsonElement(text) as? JsonObject)?.get("casement")?.jsonPrimitive?.content }
            .getOrNull() ?: ""
    } else {
        null
    }
