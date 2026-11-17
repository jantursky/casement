package com.jantursky.casement.bridge

import com.jantursky.casement.jsonrpc.JsonRpcCodec
import com.jantursky.casement.jsonrpc.JsonRpcError
import com.jantursky.casement.jsonrpc.JsonRpcErrorCode
import com.jantursky.casement.jsonrpc.JsonRpcErrorResponse
import com.jantursky.casement.jsonrpc.JsonRpcFormatException
import com.jantursky.casement.jsonrpc.JsonRpcMessage
import com.jantursky.casement.jsonrpc.JsonRpcNotification
import com.jantursky.casement.jsonrpc.JsonRpcRequest
import com.jantursky.casement.jsonrpc.JsonRpcResponse
import com.jantursky.casement.model.DisplayMode
import com.jantursky.casement.model.HostContext
import com.jantursky.casement.model.SPEC_VERSION
import com.jantursky.casement.model.ToolVisibility
import com.jantursky.casement.model.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Where a bridge is in the MCP Apps lifecycle. */
public enum class BridgeState {
    /** Waiting for the app's `ui/initialize` request. */
    AwaitingInitialize,

    /** Answered `ui/initialize`, waiting for `ui/notifications/initialized`. */
    AwaitingInitialized,

    /** The handshake is done. Tool input and results flow. */
    Ready,

    /** Torn down. Nothing more is sent or accepted. */
    Closed,
}

/**
 * The host side of the MCP Apps JSON-RPC conversation with one app.
 *
 * Feed it what the app sends with [receive]. It answers through [transport], enforces the handshake order,
 * tool visibility and the host [HostBridgeConfig.policy], and hands allowed requests to [handler].
 * Messages the host sends before the handshake finishes are held back and delivered in order.
 *
 * Everything runs on one coroutine, so state needs no locking. [receive] and the `send*` methods are safe to
 * call from any thread.
 */
public class HostBridge(
    private val config: HostBridgeConfig,
    private val handler: HostBridgeHandler,
    private val transport: HostBridgeTransport,
    scope: CoroutineScope,
    private val tools: ToolCatalog = ToolCatalog.Empty,
    initialContext: HostContext = HostContext(),
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job)
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private var currentState = BridgeState.AwaitingInitialize
    private var context = initialContext
    private var contextSentToApp: HostContext? = null
    private var appDisplayModes: List<DisplayMode>? = null
    private var inputSent = false
    private var finished = false
    private val held = ArrayDeque<JsonRpcNotification>()
    private var teardownId: JsonElement? = null
    private var teardownDone: CompletableDeferred<Unit>? = null
    private var nextRequestId = 1
    private var appInfo: AppInfo? = null

    private val isReady: Boolean get() = currentState == BridgeState.Ready

    /** The current lifecycle state. Read it for tests and diagnostics only. */
    public val state: BridgeState get() = currentState

    init {
        this.scope.launch { for (command in commands) process(command) }
    }

    /** Hands the bridge one raw message from the app. */
    public fun receive(text: String) {
        commands.trySend(Command.Incoming(text))
    }

    /** Sends `ui/notifications/tool-input` once. Later calls are ignored. */
    public fun sendToolInput(arguments: JsonObject) {
        commands.trySend(Command.ToolInput(arguments))
    }

    /** Sends `ui/notifications/tool-input-partial`. Ignored once the final input has been sent. */
    public fun sendToolInputPartial(arguments: JsonObject) {
        commands.trySend(Command.ToolInputPartial(arguments))
    }

    /** Sends the tool's `CallToolResult`. Sends empty final input first if none was sent. */
    public fun sendToolResult(result: JsonObject) {
        commands.trySend(Command.ToolResult(result))
    }

    /** Sends `ui/notifications/tool-cancelled`. Ignored after a result or an earlier cancellation. */
    public fun sendToolCancelled(reason: String? = null) {
        commands.trySend(Command.ToolCancelled(reason))
    }

    /** Pushes a new host context. Only the fields that changed are sent. */
    public fun updateContext(newContext: HostContext) {
        commands.trySend(Command.Context(newContext))
    }

    /**
     * Asks the app to clean up with `ui/resource-teardown`, waits up to the configured timeout for its answer,
     * then closes the bridge. Safe to call twice.
     */
    public suspend fun teardown(reason: String = "host closed the app") {
        val done = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Teardown(reason, done)).isSuccess) {
            withTimeoutOrNull(config.teardownTimeoutMillis + TEARDOWN_GRACE_MILLIS) { done.await() }
        }
        close()
    }

    /**
     * Sends `ui/resource-teardown` without waiting for the answer, then closes the bridge. Use it when the view
     * goes away and nothing can wait. Prefer [teardown] when it can.
     */
    public fun closeAfterTeardown(reason: String = "host closed the app") {
        commands.trySend(Command.Teardown(reason, CompletableDeferred()))
        commands.trySend(Command.Close)
    }

    /** Closes the bridge without telling the app. Pending handler calls are cancelled. */
    public fun close() {
        currentState = BridgeState.Closed
        commands.close()
        scope.cancel()
    }

    private sealed interface Command {
        data class Incoming(
            val text: String,
        ) : Command

        data class ToolInput(
            val arguments: JsonObject,
        ) : Command

        data class ToolInputPartial(
            val arguments: JsonObject,
        ) : Command

        data class ToolResult(
            val result: JsonObject,
        ) : Command

        data class ToolCancelled(
            val reason: String?,
        ) : Command

        data class Context(
            val context: HostContext,
        ) : Command

        data class Teardown(
            val reason: String,
            val done: CompletableDeferred<Unit>,
        ) : Command

        data object Close : Command
    }

    private fun process(command: Command) {
        if (currentState == BridgeState.Closed) return
        when (command) {
            is Command.Incoming -> onIncoming(command.text)
            is Command.ToolInput -> onToolInput(command.arguments, partial = false)
            is Command.ToolInputPartial -> onToolInput(command.arguments, partial = true)
            is Command.ToolResult -> onToolResult(command.result)
            is Command.ToolCancelled -> onToolCancelled(command.reason)
            is Command.Context -> onContext(command.context)
            is Command.Teardown -> onTeardown(command)
            Command.Close -> close()
        }
    }

    private fun onToolInput(
        arguments: JsonObject,
        partial: Boolean,
    ) {
        if (inputSent || finished) return
        inputSent = !partial
        val method = if (partial) "ui/notifications/tool-input-partial" else "ui/notifications/tool-input"
        notify(method, buildJsonObject { put("arguments", arguments) })
    }

    private fun onToolResult(result: JsonObject) {
        if (finished) return
        if (!inputSent) onToolInput(JsonObject(emptyMap()), partial = false)
        finished = true
        notify("ui/notifications/tool-result", result)
    }

    private fun onToolCancelled(reason: String?) {
        if (finished) return
        finished = true
        notify("ui/notifications/tool-cancelled", buildJsonObject { reason?.let { put("reason", it) } })
    }

    private fun onContext(newContext: HostContext) {
        context = newContext
        val sent = contextSentToApp ?: return
        val changes = newContext.changesSince(sent)
        if (changes.isEmpty()) return
        contextSentToApp = newContext
        notify("ui/notifications/host-context-changed", changes)
    }

    private fun onTeardown(command: Command.Teardown) {
        if (currentState == BridgeState.AwaitingInitialize) {
            command.done.complete(Unit)
            return
        }
        val id = JsonPrimitive("host-${nextRequestId++}")
        teardownId = id
        teardownDone = command.done
        // Teardown must reach the app even when the handshake never finished, so it skips the hold-back queue.
        send(
            JsonRpcRequest(
                id,
                "ui/resource-teardown",
                buildJsonObject { put("reason", command.reason) },
            ),
        )
    }

    private fun onIncoming(text: String) {
        if (text.length > config.policy.maxMessageLength) {
            blocked(BlockedKind.Oversize, "Message of ${text.length} characters")
            return
        }
        val message: JsonRpcMessage =
            try {
                JsonRpcCodec.decode(text)
            } catch (e: JsonRpcFormatException) {
                blocked(BlockedKind.Malformed, e.message.orEmpty())
                if (e.code == JsonRpcErrorCode.PARSE_ERROR) send(errorResponse(null, e.code, "Parse error"))
                return
            }
        when (message) {
            is JsonRpcRequest -> onRequest(message)
            is JsonRpcNotification -> onNotification(message)
            is JsonRpcResponse -> onAnswer(message.id)
            is JsonRpcErrorResponse -> onAnswer(message.id)
        }
    }

    private fun onAnswer(id: JsonElement?) {
        if (id != null && id == teardownId) {
            teardownId = null
            teardownDone?.complete(Unit)
        }
    }

    private fun onRequest(request: JsonRpcRequest) {
        val method = request.method
        if (method == "ping") {
            respond(request, JsonObject(emptyMap()))
            return
        }
        if (method == "ui/initialize") {
            onInitialize(request)
            return
        }
        if (currentState != BridgeState.Ready) {
            blocked(BlockedKind.Premature, "$method before the handshake finished")
            fail(request, JsonRpcErrorCode.REJECTED, "The handshake has not finished")
            return
        }
        val caps = config.capabilities
        when (method) {
            "ui/message" -> {
                if (caps.message) onUiMessage(request) else unsupported(request)
            }

            "ui/open-link" -> {
                if (caps.openLinks) onOpenLink(request) else unsupported(request)
            }

            "ui/update-model-context" -> {
                if (caps.updateModelContext) onUpdateModelContext(request) else unsupported(request)
            }

            "ui/request-display-mode" -> {
                onDisplayMode(request)
            }

            "tools/call" -> {
                if (caps.serverTools) onToolCall(request) else unsupported(request)
            }

            "resources/read", "resources/list", "resources/templates/list", "prompts/list" -> {
                if (caps.serverResources) onForward(request) else unsupported(request)
            }

            else -> {
                unsupported(request)
            }
        }
    }

    private fun onInitialize(request: JsonRpcRequest) {
        if (currentState != BridgeState.AwaitingInitialize) {
            blocked(BlockedKind.Duplicate, "Second ui/initialize")
            fail(request, JsonRpcErrorCode.REJECTED, "Already initialized")
            return
        }
        val params = request.params
        val info = params?.get("appInfo") as? JsonObject
        val name = info?.string("name")
        val version = info?.string("version")
        if (name == null || version == null) {
            blocked(BlockedKind.Malformed, "ui/initialize without appInfo")
            fail(request, JsonRpcErrorCode.INVALID_PARAMS, "appInfo.name and appInfo.version are required")
            return
        }
        val appCaps = params["appCapabilities"] as? JsonObject
        appDisplayModes =
            (appCaps?.get("availableDisplayModes") as? JsonArray)
                ?.mapNotNull { DisplayMode.fromWire((it as? JsonPrimitive)?.content) }
        appInfo = AppInfo(name, version)
        contextSentToApp = context
        currentState = BridgeState.AwaitingInitialized
        respond(
            request,
            buildJsonObject {
                put("protocolVersion", SPEC_VERSION)
                putJsonObject("hostInfo") {
                    put("name", config.hostName)
                    put("version", config.hostVersion)
                }
                put("hostCapabilities", hostCapabilities())
                put("hostContext", context.toJson())
            },
        )
    }

    private fun hostCapabilities(): JsonObject =
        buildJsonObject {
            val caps = config.capabilities
            val empty = JsonObject(emptyMap())
            if (caps.openLinks) put("openLinks", empty)
            if (caps.serverTools) put("serverTools", empty)
            if (caps.serverResources) put("serverResources", empty)
            if (caps.logging) put("logging", empty)
            if (caps.updateModelContext) {
                putJsonObject("updateModelContext") {
                    put("text", empty)
                    put("structuredContent", empty)
                }
            }
            if (caps.message) putJsonObject("message") { put("text", empty) }
            putJsonObject("sandbox") {
                val granted = config.sandboxPermissions
                putJsonObject("permissions") {
                    if (granted.camera) put("camera", empty)
                    if (granted.microphone) put("microphone", empty)
                    if (granted.geolocation) put("geolocation", empty)
                    if (granted.clipboardWrite) put("clipboardWrite", empty)
                }
                val csp = config.sandboxCsp
                putJsonObject("csp") {
                    putStrings("connectDomains", csp.connectDomains)
                    putStrings("resourceDomains", csp.resourceDomains)
                    putStrings("frameDomains", csp.frameDomains)
                    putStrings("baseUriDomains", csp.baseUriDomains)
                }
            }
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStrings(
        key: String,
        values: List<String>,
    ) {
        if (values.isNotEmpty()) putJsonArray(key) { values.forEach { add(JsonPrimitive(it)) } }
    }

    private fun onNotification(notification: JsonRpcNotification) {
        when (notification.method) {
            "ui/notifications/initialized" -> {
                if (currentState == BridgeState.AwaitingInitialized) {
                    currentState = BridgeState.Ready
                    flushHeld()
                    appInfo?.let(handler::onInitialized)
                } else {
                    blocked(BlockedKind.Premature, "initialized in state $currentState")
                }
            }

            "ui/notifications/size-changed" -> {
                onSizeChanged(notification.params)
            }

            "ui/notifications/request-teardown" -> {
                if (isReady) handler.onRequestTeardown()
            }

            "notifications/message" -> {
                if (config.capabilities.logging && isReady) {
                    notification.params?.let(handler::onLog)
                }
            }

            else -> {
                blocked(BlockedKind.Capability, "Ignored notification ${notification.method}")
            }
        }
    }

    private fun onSizeChanged(params: JsonObject?) {
        fun side(key: String): Int? =
            (params?.get(key) as? JsonPrimitive)
                ?.doubleOrNull
                ?.takeIf { it.isFinite() && it >= 0 }
                ?.coerceAtMost(MAX_SIDE)
                ?.toInt()
        val width = side("width")
        val height = side("height")
        if (width != null || height != null) handler.onSizeChanged(width, height)
    }

    private fun onUiMessage(request: JsonRpcRequest) {
        val params = request.params
        val role = params?.string("role")
        val content = params?.get("content")
        if (role == null || content == null) return invalidParams(request, "role and content are required")
        launchReply(request) {
            if (handler.onMessage(AppMessage(role, content))) JsonObject(emptyMap()) else errorResult()
        }
    }

    private fun onOpenLink(request: JsonRpcRequest) {
        val url = request.params?.string("url") ?: return invalidParams(request, "url is required")
        if (!config.policy.allowsLink(url)) {
            blocked(BlockedKind.Link, "Blocked link scheme in ${url.take(MAX_DETAIL)}")
            respond(request, errorResult())
            return
        }
        respond(request, if (handler.onOpenLink(url)) JsonObject(emptyMap()) else errorResult())
    }

    private fun onUpdateModelContext(request: JsonRpcRequest) {
        val params = request.params ?: JsonObject(emptyMap())
        launchReply(request) {
            handler.onUpdateModelContext(params)
            JsonObject(emptyMap())
        }
    }

    private fun onDisplayMode(request: JsonRpcRequest) {
        val requested = DisplayMode.fromWire(request.params?.string("mode"))
        val current = context.displayMode ?: DisplayMode.Inline
        val offeredByApp = appDisplayModes?.let { requested in it } ?: true
        if (requested == null || requested !in context.availableDisplayModes || !offeredByApp) {
            blocked(BlockedKind.DisplayMode, "Display mode ${request.params?.string("mode")} is not available")
            respond(request, buildJsonObject { put("mode", current.wire) })
            return
        }
        val actual = handler.onRequestDisplayMode(requested) ?: current
        respond(request, buildJsonObject { put("mode", actual.wire) })
        if (actual != current) onContext(context.copy(displayMode = actual))
    }

    private fun onToolCall(request: JsonRpcRequest) {
        val name = request.params?.string("name") ?: return invalidParams(request, "name is required")
        val visibility = tools.visibilityOf(name)
        if (visibility == null) {
            blocked(BlockedKind.ToolUnknown, "Unknown tool $name")
            fail(request, JsonRpcErrorCode.REJECTED, "Unknown tool: $name")
            return
        }
        if (ToolVisibility.App !in visibility) {
            blocked(BlockedKind.ToolNotVisible, "Tool $name is not visible to apps")
            fail(request, JsonRpcErrorCode.REJECTED, "Tool $name is not available to apps")
            return
        }
        val arguments = request.params["arguments"] as? JsonObject
        launchReply(request) { handler.callTool(ToolCallRequest(name, arguments)) }
    }

    private fun onForward(request: JsonRpcRequest) {
        launchReply(request) { handler.forward(request.method, request.params) }
    }

    /** Runs [block] off the bridge coroutine and answers with its result, or with an error if it throws. */
    private fun launchReply(
        request: JsonRpcRequest,
        block: suspend () -> JsonObject,
    ) {
        scope.launch {
            val reply =
                try {
                    JsonRpcResponse(request.id, block())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BridgeRejection) {
                    blocked(BlockedKind.Rejected, "${request.method}: ${e.message}")
                    errorResponse(request.id, e.code, e.message.orEmpty())
                } catch (e: Exception) {
                    val failure = "The host failed to handle ${request.method}"
                    errorResponse(request.id, JsonRpcErrorCode.INTERNAL_ERROR, failure)
                }
            send(reply)
        }
    }

    private fun notify(
        method: String,
        params: JsonObject,
    ) {
        val notification = JsonRpcNotification(method, params)
        if (currentState == BridgeState.Ready) send(notification) else held.addLast(notification)
    }

    private fun flushHeld() {
        while (held.isNotEmpty()) send(held.removeFirst())
        // The context may have changed between the initialize answer and the app becoming ready.
        onContext(context)
    }

    private fun respond(
        request: JsonRpcRequest,
        result: JsonObject,
    ) = send(JsonRpcResponse(request.id, result))

    private fun fail(
        request: JsonRpcRequest,
        code: Int,
        message: String,
    ) = send(errorResponse(request.id, code, message))

    private fun invalidParams(
        request: JsonRpcRequest,
        message: String,
    ) {
        blocked(BlockedKind.Malformed, "${request.method}: $message")
        fail(request, JsonRpcErrorCode.INVALID_PARAMS, message)
    }

    private fun unsupported(request: JsonRpcRequest) {
        blocked(BlockedKind.Capability, "Unsupported ${request.method}")
        fail(request, JsonRpcErrorCode.METHOD_NOT_FOUND, "Method not found: ${request.method}")
    }

    private fun errorResponse(
        id: JsonElement?,
        code: Int,
        message: String,
    ): JsonRpcMessage = JsonRpcErrorResponse(id, JsonRpcError(code, message))

    private fun errorResult(): JsonObject = buildJsonObject { put("isError", true) }

    private fun send(message: JsonRpcMessage) {
        if (currentState != BridgeState.Closed) transport.send(JsonRpcCodec.encode(message))
    }

    private fun blocked(
        kind: BlockedKind,
        detail: String,
    ) = handler.onBlocked(BlockedAction(kind, detail.take(MAX_DETAIL)))

    private companion object {
        const val MAX_SIDE: Double = 100_000.0
        const val MAX_DETAIL: Int = 200
        const val TEARDOWN_GRACE_MILLIS: Long = 250
    }
}
