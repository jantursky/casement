package com.jantursky.casement.compose.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.bridge.BlockedKind
import com.jantursky.casement.compose.WebViewSpec
import com.jantursky.casement.compose.cspDetail
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.swing.SwingUtilities

@Composable
internal actual fun PlatformAppWebView(
    spec: WebViewSpec,
    modifier: Modifier,
) {
    var ready by remember { mutableStateOf(FxToolkit.isReady) }
    LaunchedEffect(Unit) {
        FxToolkit.await()
        ready = true
    }
    if (!ready) {
        Box(modifier)
        return
    }
    val sandbox = remember(spec) { JavaFxSandbox(spec) }
    DisposableEffect(sandbox) { onDispose { sandbox.close() } }
    SwingPanel(factory = { sandbox.panel }, modifier = modifier)
}

/**
 * Starts the JavaFX toolkit. The first `JFXPanel` runs a nested event loop on the AWT thread while the toolkit comes
 * up, which must not happen in the middle of a Compose frame, so it is done in an event of its own.
 */
private object FxToolkit {
    private val ready = CompletableDeferred<Unit>()
    private var started = false

    val isReady: Boolean get() = ready.isCompleted

    suspend fun await() {
        if (!started) {
            started = true
            SwingUtilities.invokeLater {
                JFXPanel()
                ready.complete(Unit)
            }
        }
        ready.await()
    }
}

private const val MESSAGE_PREFIX = "casement:"

/**
 * An OpenJFX WebView that can run untrusted HTML and talk to the host, and nothing else.
 *
 * JavaFX's WebKit gives the host no request interception and no response headers, so the guarantees differ from the
 * other platforms: the policy is a `<meta>` tag, and the app runs in an iframe with an opaque origin inside a wrapper
 * page whose own policy refuses to let that frame navigate (see [wrapperDocument]). Permission prompts do not exist
 * in this engine, so camera, microphone and location are never available. Requests outside the declared origins are
 * stopped by the policy, not by the host, so they are reported as CSP violations.
 */
internal class JavaFxSandbox(
    private val spec: WebViewSpec,
) {
    val panel = JFXPanel()
    private var engine: WebEngine? = null

    init {
        Platform.setImplicitExit(false)
        Platform.runLater {
            val view = WebView()
            view.isContextMenuEnabled = false
            val web = view.engine
            engine = web
            web.isJavaScriptEnabled = true
            web.onAlert = javafx.event.EventHandler { onAlert(it.data) }
            web.confirmHandler = javafx.util.Callback { false }
            web.promptHandler = javafx.util.Callback { null }
            web.createPopupHandler =
                javafx.util.Callback {
                    blocked(BlockedKind.Window, "Blocked window.open")
                    null
                }
            web.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED && !started) start(web)
            }
            panel.scene = Scene(view, Color.TRANSPARENT)
            web.loadContent(wrapperDocument())
        }
    }

    private var started = false

    private fun start(web: WebEngine) {
        started = true
        web.executeScript("window.__start(${jsString(appDocument(spec.html, spec.csp))})")
    }

    private fun onAlert(data: String) {
        if (!data.startsWith(MESSAGE_PREFIX)) return // A dialog the app asked for: ignored.
        val text = data.removePrefix(MESSAGE_PREFIX)
        when (controlOf(text)) {
            null -> post { spec.channel.fromApp(text) }
            "ready" -> post { spec.channel.attach { toApp(it) } }
            "csp" -> blocked(BlockedKind.Csp, cspDetail(text))
            "navigation" -> blocked(BlockedKind.Navigation, "The app tried to navigate away")
        }
    }

    private fun toApp(text: String) {
        Platform.runLater { engine?.executeScript("window.__toApp(${jsString(text)})") }
    }

    private fun blocked(
        kind: BlockedKind,
        detail: String,
    ) = post { spec.onBlocked(BlockedAction(kind, detail)) }

    private fun post(action: () -> Unit) = SwingUtilities.invokeLater(action)

    fun close() {
        spec.channel.detach()
        Platform.runLater {
            engine?.load(null)
            engine = null
        }
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
