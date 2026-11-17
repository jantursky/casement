package com.jantursky.casement.bridge

import com.jantursky.casement.jsonrpc.JsonRpcErrorCode
import com.jantursky.casement.model.DisplayMode
import com.jantursky.casement.model.ToolVisibility
import com.jantursky.casement.model.UiCsp
import com.jantursky.casement.model.UiPermissions
import com.jantursky.casement.policy.HostPolicy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Carries messages to the app. The renderer implements it on top of the platform's message channel. */
public fun interface HostBridgeTransport {
    /** Delivers one JSON-RPC message to the app. May be called from any thread. */
    public fun send(message: String)
}

/** Tells the bridge which tools exist and who may call them. A tool that is not listed does not exist. */
public fun interface ToolCatalog {
    public fun visibilityOf(name: String): Set<ToolVisibility>?

    public companion object {
        /** A catalog backed by a fixed map. */
        public fun of(tools: Map<String, Set<ToolVisibility>>): ToolCatalog = ToolCatalog { tools[it] }

        /** A catalog with no tools. Every `tools/call` from the app is blocked. */
        public val Empty: ToolCatalog = ToolCatalog { null }
    }
}

/** Throw from a [HostBridgeHandler] to refuse a request. The app receives [message] as a JSON-RPC error. */
public class BridgeRejection(
    message: String,
    public val code: Int = JsonRpcErrorCode.REJECTED,
) : Exception(message)

/** What the app asked the host to add to the conversation with `ui/message`. */
public data class AppMessage(
    val role: String,
    val content: JsonElement,
)

/** A tool call the app made. Visibility has already been checked when the handler sees it. */
public data class ToolCallRequest(
    val name: String,
    val arguments: JsonObject?,
)

/** The app's name and version from `ui/initialize`. */
public data class AppInfo(
    val name: String,
    val version: String,
)

/** Why the bridge refused something the app did. */
public enum class BlockedKind {
    /** A message that is not valid JSON-RPC, or has the wrong parameters. */
    Malformed,

    /** A message larger than [HostPolicy.maxMessageLength]. */
    Oversize,

    /** A request before the handshake finished. */
    Premature,

    /** A second `ui/initialize`. */
    Duplicate,

    /** A method the host does not implement, or a capability it did not advertise. */
    Capability,

    /** A `tools/call` for a tool the host does not know. */
    ToolUnknown,

    /** A `tools/call` for a tool without app visibility. */
    ToolNotVisible,

    /** A `ui/open-link` URL the policy does not allow. */
    Link,

    /** A display mode the app or the host did not offer. */
    DisplayMode,

    /** A request the handler refused, for example a tool call the user declined. */
    Rejected,

    /** The app tried to navigate away from its document. */
    Navigation,

    /** The app tried to open a new window. */
    Window,

    /** The app asked the platform for a permission the host did not grant. */
    Permission,

    /** The app tried to load something from a domain its CSP does not allow. */
    Network,

    /** The browser reported a CSP violation. */
    Csp,
}

/** One refused action, reported to [HostBridgeHandler.onBlocked]. Hosts can log or show it. */
public data class BlockedAction(
    val kind: BlockedKind,
    val detail: String,
)

/**
 * The host side of what an app can ask for. Every method has a safe default that refuses or ignores,
 * so a host overrides only what it supports and advertises in [HostCapabilitiesConfig].
 */
public interface HostBridgeHandler {
    /** Runs a tool for the app. Return the `CallToolResult` as JSON. */
    public suspend fun callTool(request: ToolCallRequest): JsonObject =
        throw BridgeRejection("Tool calls are not enabled")

    /** Answers `resources/read`, `resources/list`, `resources/templates/list` and `prompts/list`. */
    public suspend fun forward(
        method: String,
        params: JsonObject?,
    ): JsonObject = throw BridgeRejection("$method is not enabled")

    /** Adds the app's `ui/message` to the conversation. Return false to report an error to the app. */
    public suspend fun onMessage(message: AppMessage): Boolean = false

    /** Stores the app's `ui/update-model-context` for later turns. */
    public suspend fun onUpdateModelContext(params: JsonObject): Unit =
        throw BridgeRejection("Model context updates are not enabled")

    /** Opens [url] outside the app. Return false to report an error to the app. */
    public fun onOpenLink(url: String): Boolean = false

    /** The app asked for a new size, in CSS pixels. A null side is unchanged. */
    public fun onSizeChanged(
        width: Int?,
        height: Int?,
    ) {
    }

    /** Switches to [mode] and returns the mode that is now active, or null to stay in the current mode. */
    public fun onRequestDisplayMode(mode: DisplayMode): DisplayMode? = null

    /** A `notifications/message` log entry from the app. */
    public fun onLog(params: JsonObject) {
    }

    /** The app asked to be closed. Call [HostBridge.teardown] when the host agrees. */
    public fun onRequestTeardown() {
    }

    /** The handshake finished and the app is ready for tool input. */
    public fun onInitialized(app: AppInfo) {
    }

    /** The bridge refused something. */
    public fun onBlocked(action: BlockedAction) {
    }
}

/** Which optional features the host advertises, and therefore accepts. Everything is off by default. */
public data class HostCapabilitiesConfig(
    val openLinks: Boolean = false,
    val serverTools: Boolean = false,
    val serverResources: Boolean = false,
    val logging: Boolean = false,
    val updateModelContext: Boolean = false,
    val message: Boolean = false,
)

/**
 * Settings of one bridge.
 *
 * @property sandboxPermissions Permissions the host actually grants this app. Reported as `sandbox.permissions`.
 * @property sandboxCsp The domains the CSP actually allows. Reported as `sandbox.csp`.
 * @property teardownTimeoutMillis How long [HostBridge.teardown] waits for the app to answer.
 */
public data class HostBridgeConfig(
    val hostName: String,
    val hostVersion: String,
    val capabilities: HostCapabilitiesConfig = HostCapabilitiesConfig(),
    val policy: HostPolicy = HostPolicy(),
    val sandboxPermissions: UiPermissions = UiPermissions.None,
    val sandboxCsp: UiCsp = UiCsp(),
    val teardownTimeoutMillis: Long = 2_000,
)
