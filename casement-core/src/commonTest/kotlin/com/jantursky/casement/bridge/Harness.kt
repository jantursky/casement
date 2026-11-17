package com.jantursky.casement.bridge

import com.jantursky.casement.model.DisplayMode
import com.jantursky.casement.model.HostContext
import com.jantursky.casement.model.ToolVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Records what the host sends and what the handler is asked to do. */
class RecordingHandler : HostBridgeHandler {
    val toolCalls = mutableListOf<ToolCallRequest>()
    val messages = mutableListOf<AppMessage>()
    val links = mutableListOf<String>()
    val sizes = mutableListOf<Pair<Int?, Int?>>()
    val blocked = mutableListOf<BlockedAction>()
    val logs = mutableListOf<JsonObject>()
    val forwarded = mutableListOf<String>()
    val modelContexts = mutableListOf<JsonObject>()
    var initialized: AppInfo? = null
    var teardownRequests = 0
    var toolResult: JsonObject = Json.parseToJsonElement("""{"content":[{"type":"text","text":"ok"}]}""").jsonObject
    var failWith: Exception? = null
    var messageAccepted = true
    var linkOpened = true
    var displayModeAnswer: DisplayMode? = null

    override suspend fun callTool(request: ToolCallRequest): JsonObject {
        toolCalls += request
        failWith?.let { throw it }
        return toolResult
    }

    override suspend fun forward(
        method: String,
        params: JsonObject?,
    ): JsonObject {
        forwarded += method
        return JsonObject(emptyMap())
    }

    override suspend fun onMessage(message: AppMessage): Boolean {
        messages += message
        return messageAccepted
    }

    override suspend fun onUpdateModelContext(params: JsonObject) {
        modelContexts += params
    }

    override fun onOpenLink(url: String): Boolean {
        links += url
        return linkOpened
    }

    override fun onSizeChanged(
        width: Int?,
        height: Int?,
    ) {
        sizes += width to height
    }

    override fun onRequestDisplayMode(mode: DisplayMode): DisplayMode? = displayModeAnswer

    override fun onLog(params: JsonObject) {
        logs += params
    }

    override fun onRequestTeardown() {
        teardownRequests++
    }

    override fun onInitialized(app: AppInfo) {
        initialized = app
    }

    override fun onBlocked(action: BlockedAction) {
        blocked += action
    }
}

/** A bridge wired to a recording handler and transport, with helpers that play the app. */
class Harness(
    scope: CoroutineScope,
    capabilities: HostCapabilitiesConfig = ALL,
    context: HostContext = HostContext(),
    config: HostBridgeConfig = HostBridgeConfig("host", "1.0", capabilities),
    val handler: RecordingHandler = RecordingHandler(),
) {
    val sent = mutableListOf<JsonObject>()
    val bridge =
        HostBridge(
            config = config,
            handler = handler,
            transport = { sent += Json.parseToJsonElement(it).jsonObject },
            scope = scope,
            tools =
                ToolCatalog.of(
                    mapOf(
                        "both" to setOf(ToolVisibility.Model, ToolVisibility.App),
                        "app_only" to setOf(ToolVisibility.App),
                        "model_only" to setOf(ToolVisibility.Model),
                    ),
                ),
            initialContext = context,
        )

    fun app(text: String) = bridge.receive(text)

    fun request(
        id: Int,
        method: String,
        params: String = "{}",
    ) = app("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")

    fun notification(
        method: String,
        params: String = "{}",
    ) = app("""{"jsonrpc":"2.0","method":"$method","params":$params}""")

    fun handshake() {
        request(
            1,
            "ui/initialize",
            """{"appInfo":{"name":"app","version":"2"},"appCapabilities":{},"protocolVersion":"2026-01-26"}""",
        )
        notification("ui/notifications/initialized")
    }

    fun methods(): List<String?> = sent.map { it["method"]?.jsonPrimitive?.content }

    fun response(id: Int): JsonObject = sent.last { it["id"]?.jsonPrimitive?.content == id.toString() }

    companion object {
        val ALL =
            HostCapabilitiesConfig(
                openLinks = true,
                serverTools = true,
                serverResources = true,
                logging = true,
                updateModelContext = true,
                message = true,
            )
    }
}
