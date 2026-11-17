@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.jantursky.casement.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.bridge.BlockedKind
import com.jantursky.casement.compose.RendererFailure
import com.jantursky.casement.compose.WebViewSpec
import com.jantursky.casement.compose.bridgeShim
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.CoreGraphics.CGRectMake
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.create
import platform.UIKit.UIColor
import platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior
import platform.WebKit.WKContentRuleList
import platform.WebKit.WKContentRuleListStore
import platform.WebKit.WKContentWorld
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject

@Composable
internal actual fun PlatformAppWebView(
    spec: WebViewSpec,
    modifier: Modifier,
) {
    val sandbox = remember(spec) { IosSandbox(spec) }
    UIKitView(
        factory = { sandbox.createWebView() },
        modifier = modifier,
        onRelease = { sandbox.release(it) },
    )
}

private const val BRIDGE_NAME = "casementNative"
private const val SCHEME = "casement-app"
private const val NOT_FOUND = 404L
private const val FORBIDDEN = 403L
private const val OK = 200L

/**
 * A WKWebView that can run untrusted HTML and talk to the host, and nothing else.
 *
 * The document comes from a custom URL scheme, so it gets a real origin and a `Content-Security-Policy` header
 * the page cannot remove. A content rule list blocks every network request the CSP did not declare. The only
 * script message handler is registered for the main frame of that origin, and every navigation is cancelled.
 */
private class IosSandbox(
    private val spec: WebViewSpec,
) : NSObject(),
    WKScriptMessageHandlerProtocol,
    WKNavigationDelegateProtocol,
    WKUIDelegateProtocol,
    WKURLSchemeHandlerProtocol {
    private val documentUrl = "$SCHEME://app/app.html"
    private var loaded = false

    fun createWebView(): WKWebView {
        val configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        configuration.allowsInlineMediaPlayback = true
        configuration.setURLSchemeHandler(this, forURLScheme = SCHEME)
        configuration.userContentController.addScriptMessageHandler(this, WKContentWorld.pageWorld, BRIDGE_NAME)
        configuration.userContentController.addUserScript(
            WKUserScript(
                source =
                    bridgeShim(
                        send = "function (s) { window.webkit.messageHandlers.$BRIDGE_NAME.postMessage(s); }",
                        receive = "window.__casementReceive = deliver;",
                    ),
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
            ),
        )

        val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
        webView.navigationDelegate = this
        webView.UIDelegate = this
        webView.opaque = false
        webView.backgroundColor = UIColor.clearColor
        webView.scrollView.backgroundColor = UIColor.clearColor
        webView.scrollView.scrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior =
            UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
        webView.allowsLinkPreview = false

        spec.channel.attach { text ->
            val literal = Json.encodeToString(String.serializer(), text)
            webView.evaluateJavaScript("window.__casementReceive && window.__casementReceive($literal)", null)
        }
        installNetworkRules(webView)
        return webView
    }

    /** Blocks all network requests except the declared origins, then loads the document. Fails closed. */
    private fun installNetworkRules(webView: WKWebView) {
        val store = WKContentRuleListStore.defaultStore()
        store?.compileContentRuleListForIdentifier(
            identifier = "casement-${hashCode()}",
            encodedContentRuleList = contentRules(spec.allowedOrigins),
        ) { list: WKContentRuleList?, error ->
            if (list == null || error != null) {
                spec.onFailed(RendererFailure.Unsupported)
            } else {
                webView.configuration.userContentController.addContentRuleList(list)
                webView.loadRequest(NSURLRequest(NSURL(string = documentUrl)))
            }
        } ?: spec.onFailed(RendererFailure.Unsupported)
    }

    fun release(webView: WKWebView) {
        spec.channel.detach()
        webView.stopLoading()
        webView.navigationDelegate = null
        webView.UIDelegate = null
        val controller = webView.configuration.userContentController
        controller.removeScriptMessageHandlerForName(BRIDGE_NAME, WKContentWorld.pageWorld)
        controller.removeAllUserScripts()
        controller.removeAllContentRuleLists()
        webView.removeFromSuperview()
    }

    // --- messages from the app ---------------------------------------------------------------------------------

    override fun userContentController(
        userContentController: platform.WebKit.WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val frame = didReceiveScriptMessage.frameInfo
        val origin = frame.securityOrigin
        if (!frame.mainFrame || origin.protocol != SCHEME || origin.host != "app") return
        val text = didReceiveScriptMessage.body as? String ?: return
        when (controlOf(text)) {
            null -> spec.channel.fromApp(text)
            "csp" -> spec.onBlocked(BlockedAction(BlockedKind.Csp, "The browser blocked a CSP violation"))
        }
    }

    // --- document and everything it asks for ---------------------------------------------------------------------

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        startURLSchemeTask: WKURLSchemeTaskProtocol,
    ) {
        val url = startURLSchemeTask.request.URL
        val isDocument = url?.absoluteString == documentUrl && startURLSchemeTask.request.HTTPMethod == "GET"
        val status = if (isDocument) OK else NOT_FOUND
        val body = if (isDocument) spec.html.encodeToByteArray() else ByteArray(0)
        val headers =
            if (isDocument) {
                mapOf<Any?, Any?>(
                    "Content-Type" to "text/html; charset=utf-8",
                    "Content-Security-Policy" to spec.csp,
                    "Permissions-Policy" to spec.permissions.toPermissionsPolicy(),
                    "X-Content-Type-Options" to "nosniff",
                    "Referrer-Policy" to "no-referrer",
                    "Cache-Control" to "no-store",
                )
            } else {
                mapOf<Any?, Any?>("Content-Type" to "text/plain")
            }
        val response =
            NSHTTPURLResponse(
                uRL = url ?: NSURL(string = documentUrl)!!,
                statusCode = status,
                HTTPVersion = "HTTP/1.1",
                headerFields = headers,
            )
        startURLSchemeTask.didReceiveResponse(response)
        startURLSchemeTask.didReceiveData(body.toNSData())
        startURLSchemeTask.didFinish()
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        stopURLSchemeTask: WKURLSchemeTaskProtocol,
    ) = Unit

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val target = decidePolicyForNavigationAction.request.URL?.absoluteString
        if (!loaded && target == documentUrl) {
            loaded = true
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            spec.onBlocked(BlockedAction(BlockedKind.Navigation, "Blocked navigation to ${scheme(target)}"))
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        spec.onFailed(RendererFailure.ProcessGone)
    }

    // --- windows, dialogs and permissions ------------------------------------------------------------------------

    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        spec.onBlocked(BlockedAction(BlockedKind.Window, "Blocked window.open"))
        return null
    }

    override fun webView(
        webView: WKWebView,
        requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        val allowed =
            when (type) {
                WKMediaCaptureType.WKMediaCaptureTypeCamera -> spec.permissions.camera
                WKMediaCaptureType.WKMediaCaptureTypeMicrophone -> spec.permissions.microphone
                else -> spec.permissions.camera && spec.permissions.microphone
            }
        if (!allowed) spec.onBlocked(BlockedAction(BlockedKind.Permission, "Denied camera or microphone"))
        val decision =
            if (allowed) {
                WKPermissionDecision.WKPermissionDecisionGrant
            } else {
                WKPermissionDecision.WKPermissionDecisionDeny
            }
        decisionHandler(decision)
    }

    override fun webView(
        webView: WKWebView,
        runJavaScriptAlertPanelWithMessage: String,
        initiatedByFrame: WKFrameInfo,
        completionHandler: () -> Unit,
    ) = completionHandler()

    override fun webView(
        webView: WKWebView,
        runJavaScriptConfirmPanelWithMessage: String,
        initiatedByFrame: WKFrameInfo,
        completionHandler: (Boolean) -> Unit,
    ) = completionHandler(false)

    override fun webView(
        webView: WKWebView,
        runJavaScriptTextInputPanelWithPrompt: String,
        defaultText: String?,
        initiatedByFrame: WKFrameInfo,
        completionHandler: (String?) -> Unit,
    ) = completionHandler(null)
}

private fun scheme(url: String?): String = url?.substringBefore(':', "unknown") ?: "unknown"

/** The `casement` field of a control message, or null for anything else (which is JSON-RPC for the bridge). */
private fun controlOf(text: String): String? =
    if (text.startsWith("{\"casement\"")) {
        runCatching { (Json.parseToJsonElement(text) as? JsonObject)?.get("casement")?.jsonPrimitive?.content }
            .getOrNull() ?: ""
    } else {
        null
    }

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/**
 * A content rule list in WebKit's JSON format: block every http(s) and ws(s) request, then re-allow the declared
 * origins. The document itself uses a custom scheme, so these rules never touch it.
 */
internal fun contentRules(allowedOrigins: List<String>): String {
    val rules = mutableListOf("""{"trigger":{"url-filter":"^(https?|wss?)://"},"action":{"type":"block"}}""")
    allowedOrigins.forEach { origin ->
        rules += """{"trigger":{"url-filter":"${originRegex(origin)}"},"action":{"type":"ignore-previous-rules"}}"""
    }
    return rules.joinToString(prefix = "[", postfix = "]", separator = ",")
}

private fun originRegex(origin: String): String {
    val scheme = origin.substringBefore("://")
    val authority = origin.substringAfter("://")
    val wildcard = authority.startsWith("*.")
    val hostAndPort = authority.removePrefix("*.")
    val host = hostAndPort.substringBefore(':')
    val port = hostAndPort.substringAfter(':', "")
    val hostPattern = (if (wildcard) "([^/:]*\\\\.)?" else "") + host.replace(".", "\\\\.")
    val portPattern = if (port.isEmpty()) "" else ":$port"
    return "^$scheme://$hostPattern$portPattern(/|\\\\?|#|$)"
}
