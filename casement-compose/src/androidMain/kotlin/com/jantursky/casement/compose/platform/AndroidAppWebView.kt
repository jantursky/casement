package com.jantursky.casement.compose.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.bridge.BlockedKind
import com.jantursky.casement.compose.RendererFailure
import com.jantursky.casement.compose.WebViewSpec
import com.jantursky.casement.compose.bridgeShim
import com.jantursky.casement.policy.OriginMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

@Composable
internal actual fun PlatformAppWebView(
    spec: WebViewSpec,
    modifier: Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(webView, lifecycle) {
        val view = webView
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> view?.onPause()
                    Lifecycle.Event.ON_RESUME -> view?.onResume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier,
        factory = { context -> createSandboxedWebView(context, spec).also { webView = it } },
        onRelease = { releaseSandboxedWebView(it) },
    )
}

private const val BRIDGE_NAME = "casementNative"
private const val NOT_FOUND = 404
private const val FORBIDDEN = 403
private const val FULL_TEXT_ZOOM = 100
private val instances = AtomicInteger()

/** Remembers which origin a web view serves, so its storage can be wiped on release. */
private class Sandbox(
    val origin: String,
)

/**
 * A WebView that can run untrusted HTML and talk to the host, and nothing else.
 *
 * The document is served from a made-up https origin through [WebViewClient.shouldInterceptRequest], so it gets a
 * real origin and a `Content-Security-Policy` header the page cannot remove. The message channel is
 * `addWebMessageListener`, limited to that origin and the main frame; there is no `addJavascriptInterface`.
 */
private fun createSandboxedWebView(
    context: Context,
    spec: WebViewSpec,
): WebView {
    val origin = "https://app-${instances.incrementAndGet()}.casement.invalid"
    val documentUrl = "$origin/app.html"
    val webView = WebView(context)
    webView.tag = Sandbox(origin)
    webView.setBackgroundColor(Color.TRANSPARENT)
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false
    webView.overScrollMode = View.OVER_SCROLL_NEVER

    val supported =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    if (!supported) {
        webView.post { spec.onFailed(RendererFailure.Unsupported) }
        return webView
    }

    configure(webView.settings)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

    WebViewCompat.addDocumentStartJavaScript(
        webView,
        bridgeShim(
            send = "function (s) { $BRIDGE_NAME.postMessage(s); }",
            receive = "$BRIDGE_NAME.onmessage = function (e) { deliver(e.data); };",
        ),
        setOf(origin),
    )
    WebViewCompat.addWebMessageListener(
        webView,
        BRIDGE_NAME,
        setOf(origin),
    ) { _, message, sourceOrigin, isMainFrame, reply ->
        val text = message.data
        if (isMainFrame && text != null && sourceOrigin.matches(origin)) {
            when (controlOf(text)) {
                null -> spec.channel.fromApp(text)
                "ready" -> spec.channel.attach { reply.postMessage(it) }
                "csp" -> spec.onBlocked(BlockedAction(BlockedKind.Csp, "The browser blocked a CSP violation"))
            }
        }
    }

    webView.webViewClient = SandboxClient(spec, origin, documentUrl)
    webView.webChromeClient = SandboxChromeClient(spec)
    webView.loadUrl(documentUrl)
    return webView
}

@SuppressLint("SetJavaScriptEnabled")
private fun configure(settings: WebSettings) {
    settings.javaScriptEnabled = true
    // Storage is per origin and every app has its own, wiped on release.
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setGeolocationEnabled(false)
    settings.javaScriptCanOpenWindowsAutomatically = true
    // Both on, so that window.open reaches onCreateWindow, which always refuses and reports it.
    settings.setSupportMultipleWindows(true)
    settings.mediaPlaybackRequiresUserGesture = true
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.textZoom = FULL_TEXT_ZOOM
    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
    }
}

private fun Uri.matches(origin: String): Boolean = "$scheme://$host" == origin

/** The `casement` field of a control message, or null for anything else (which is JSON-RPC for the bridge). */
private fun controlOf(text: String): String? =
    if (text.startsWith("{\"casement\"")) {
        runCatching { (Json.parseToJsonElement(text) as? JsonObject)?.get("casement")?.jsonPrimitive?.content }
            .getOrNull() ?: ""
    } else {
        null
    }

private class SandboxClient(
    private val spec: WebViewSpec,
    private val origin: String,
    private val documentUrl: String,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        spec.onBlocked(BlockedAction(BlockedKind.Navigation, "Blocked navigation to ${request.url.scheme}"))
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        return when {
            url == documentUrl && request.isForMainFrame && request.method == "GET" -> {
                document()
            }

            url.startsWith("$origin/") -> {
                response(NOT_FOUND, "Not Found")
            }

            OriginMatcher.isAllowed(spec.allowedOrigins, url) -> {
                null
            }

            else -> {
                val host = request.url.host
                view.post { spec.onBlocked(BlockedAction(BlockedKind.Network, "Blocked request to $host")) }
                response(FORBIDDEN, "Forbidden")
            }
        }
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        spec.onFailed(RendererFailure.ProcessGone)
        return true
    }

    private fun document(): WebResourceResponse =
        WebResourceResponse(
            "text/html",
            "utf-8",
            200,
            "OK",
            mapOf(
                "Content-Security-Policy" to spec.csp,
                "Permissions-Policy" to spec.permissions.toPermissionsPolicy(),
                "X-Content-Type-Options" to "nosniff",
                "Referrer-Policy" to "no-referrer",
                "Cache-Control" to "no-store",
            ),
            ByteArrayInputStream(spec.html.toByteArray(Charsets.UTF_8)),
        )

    private fun response(
        status: Int,
        reason: String,
    ) = WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

private class SandboxChromeClient(
    private val spec: WebViewSpec,
) : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) {
        spec.onBlocked(BlockedAction(BlockedKind.Permission, "Denied ${request.resources.joinToString()}"))
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        spec.onBlocked(BlockedAction(BlockedKind.Permission, "Denied geolocation"))
        callback.invoke(origin, false, false)
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message,
    ): Boolean {
        spec.onBlocked(BlockedAction(BlockedKind.Window, "Blocked window.open"))
        return false
    }

    override fun onJsAlert(
        view: WebView,
        url: String,
        message: String,
        result: JsResult,
    ): Boolean = result.cancelAndConsume()

    override fun onJsConfirm(
        view: WebView,
        url: String,
        message: String,
        result: JsResult,
    ): Boolean = result.cancelAndConsume()

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult,
    ): Boolean = result.cancelAndConsume()

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        filePathCallback.onReceiveValue(null)
        return true
    }

    private fun JsResult.cancelAndConsume(): Boolean {
        cancel()
        return true
    }
}

private fun releaseSandboxedWebView(webView: WebView) {
    (webView.tag as? Sandbox)?.let { WebStorage.getInstance().deleteOrigin(it.origin) }
    webView.stopLoading()
    webView.webChromeClient = null
    webView.webViewClient = WebViewClient()
    webView.removeAllViews()
    webView.destroy()
}
