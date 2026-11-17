package com.jantursky.casement.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.jantursky.casement.bridge.AppMessage
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.bridge.BridgeRejection
import com.jantursky.casement.bridge.HostBridge
import com.jantursky.casement.bridge.HostBridgeConfig
import com.jantursky.casement.bridge.HostBridgeHandler
import com.jantursky.casement.bridge.HostCapabilitiesConfig
import com.jantursky.casement.bridge.ToolCallRequest
import com.jantursky.casement.client.McpAppsHostClient
import com.jantursky.casement.compose.platform.PlatformAppWebView
import com.jantursky.casement.compose.platform.grantablePermissions
import com.jantursky.casement.model.ContainerDimensions
import com.jantursky.casement.model.HostContext
import com.jantursky.casement.model.ToolWithUi
import com.jantursky.casement.model.UiResource
import com.jantursky.casement.policy.CspBuilder
import com.jantursky.casement.policy.HostPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Shows the interactive UI of an MCP tool in a sandboxed web view and runs the host side of the MCP Apps bridge.
 *
 * The view fetches [tool]'s `ui://` resource through [client], renders it with the CSP the resource declared (and
 * nothing the [policy] does not allow), and keeps the app up to date: tool input and result as they arrive, theme
 * and other [hostContext] changes live. Its height follows the app's size notifications, so it sits in a
 * `LazyColumn` like any other item.
 *
 * The app can read no files, reach no network beyond its declared domains, use no camera, microphone or
 * location unless [policy] grants it, and cannot navigate or open windows. Links go through [onOpenLink].
 *
 * @param tool The tool that carries the UI declaration. Its definition is sent to the app as `toolInfo`.
 * @param toolInput The tool arguments, as far as they are known. Null until the call starts.
 * @param toolInputComplete False while [toolInput] is still streaming in, true once it is final.
 * @param toolResult The `CallToolResult` as JSON, or null while the tool is running.
 * @param client The MCP connection the app belongs to.
 * @param hostContext Theme, style variables, locale and so on. Defaults to the current Material 3 theme.
 * @param policy What the app may do beyond the defaults, which deny everything.
 * @param onMessage The app sent a `ui/message`. Add it to the conversation.
 * @param onOpenLink The app asked to open a link whose scheme the [policy] allows.
 * @param approveToolCall Asked before a tool call from the app reaches [client]. Return false to refuse.
 * @param onBlocked The sandbox or the bridge refused something the app did.
 * @param onError The app could not be shown.
 */
@Composable
public fun McpAppView(
    tool: ToolWithUi,
    toolInput: JsonObject?,
    toolResult: JsonObject?,
    client: McpAppsHostClient,
    modifier: Modifier = Modifier,
    toolInputComplete: Boolean = true,
    hostContext: HostContext = rememberHostContext(),
    policy: HostPolicy = HostPolicy(),
    onMessage: (AppMessage) -> Unit = {},
    onOpenLink: (String) -> Unit = defaultOpenLink(),
    approveToolCall: suspend (ToolCallRequest) -> Boolean = { true },
    onBlocked: (BlockedAction) -> Unit = {},
    onError: (Throwable) -> Unit = {},
) {
    val currentOnError by rememberUpdatedState(onError)
    val load by produceState<Load>(Load.Loading, tool.ui.resourceUri, client) {
        value =
            try {
                Load.Ready(client.readResource(tool.ui.resourceUri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
    }
    when (val state = load) {
        Load.Loading -> {
            Box(modifier.fillMaxWidth().height(INITIAL_HEIGHT.dp))
        }

        is Load.Failed -> {
            LaunchedEffect(state) { currentOnError(state.error) }
            Failure(modifier)
        }

        is Load.Ready -> {
            if (state.resource.html.length > policy.maxHtmlLength) {
                val error = IllegalArgumentException("The app document is larger than the policy allows")
                LaunchedEffect(state) { currentOnError(error) }
                Failure(modifier)
            } else {
                LiveApp(
                    tool = tool,
                    resource = state.resource,
                    toolInput = toolInput,
                    toolInputComplete = toolInputComplete,
                    toolResult = toolResult,
                    client = client,
                    modifier = modifier,
                    hostContext = hostContext,
                    policy = policy,
                    onMessage = onMessage,
                    onOpenLink = onOpenLink,
                    approveToolCall = approveToolCall,
                    onBlocked = onBlocked,
                    onError = onError,
                )
            }
        }
    }
}

/** An action that opens a link with the platform's URI handler. */
@Composable
public fun defaultOpenLink(): (String) -> Unit {
    val handler = LocalUriHandler.current
    return { url -> handler.openUri(url) }
}

private sealed interface Load {
    data object Loading : Load

    data class Ready(
        val resource: UiResource,
    ) : Load

    data class Failed(
        val error: Throwable,
    ) : Load
}

@Composable
private fun Failure(modifier: Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = INITIAL_HEIGHT.dp)) {
        Text("This app could not be loaded.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LiveApp(
    tool: ToolWithUi,
    resource: UiResource,
    toolInput: JsonObject?,
    toolInputComplete: Boolean,
    toolResult: JsonObject?,
    client: McpAppsHostClient,
    modifier: Modifier,
    hostContext: HostContext,
    policy: HostPolicy,
    onMessage: (AppMessage) -> Unit,
    onOpenLink: (String) -> Unit,
    approveToolCall: suspend (ToolCallRequest) -> Boolean,
    onBlocked: (BlockedAction) -> Unit,
    onError: (Throwable) -> Unit,
) {
    // A dead web content process needs a fresh view, a fresh bridge and a new handshake.
    var generation by remember { mutableIntStateOf(0) }
    val currentOnError = rememberUpdatedState(onError)
    key(resource, generation) {
        val session =
            rememberSession(
                tool = tool,
                resource = resource,
                client = client,
                policy = policy,
                hostContext = hostContext,
                onMessage = rememberUpdatedState(onMessage),
                onOpenLink = rememberUpdatedState(onOpenLink),
                approveToolCall = rememberUpdatedState(approveToolCall),
                onBlocked = rememberUpdatedState(onBlocked),
                onFailed = { failure ->
                    when (failure) {
                        RendererFailure.ProcessGone -> {
                            generation++
                        }

                        RendererFailure.Unsupported -> {
                            currentOnError.value(IllegalStateException("This web view cannot sandbox MCP Apps"))
                        }
                    }
                },
            )
        LaunchedEffect(session.bridge, toolInput, toolInputComplete) {
            toolInput?.let {
                if (toolInputComplete) {
                    session.bridge.sendToolInput(
                        it,
                    )
                } else {
                    session.bridge.sendToolInputPartial(it)
                }
            }
        }
        LaunchedEffect(session.bridge, toolResult) { toolResult?.let(session.bridge::sendToolResult) }
        LaunchedEffect(session.bridge, session.context) { session.bridge.updateContext(session.context) }
        DisposableEffect(session.bridge) {
            onDispose {
                session.channel.detach()
                session.bridge.closeAfterTeardown()
            }
        }

        val density = LocalDensity.current
        val shape = RoundedCornerShape(CORNER.dp)
        val bordered =
            if (resource.meta.prefersBorder ==
                true
            ) {
                Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            } else {
                Modifier
            }
        Box(
            modifier
                .fillMaxWidth()
                .onSizeChanged {
                    session.width =
                        with(density) {
                            it.width
                                .toDp()
                                .value
                                .toInt()
                        }
                }.height(session.height.dp)
                .clip(shape)
                .then(bordered),
        ) {
            PlatformAppWebView(session.spec, Modifier.fillMaxSize())
        }
    }
}

/** The state of one running app: its bridge, channel, renderer spec and measured size. */
private class Session(
    val bridge: HostBridge,
    val channel: AppChannel,
    val spec: WebViewSpec,
    initialContext: HostContext,
) {
    private var base by mutableStateOf(initialContext)
    var width by mutableIntStateOf(0)
    var height by mutableIntStateOf(INITIAL_HEIGHT)

    private val maxHeight: Int? get() = base.containerDimensions?.height

    /** The context to send now: the host's own, plus what only this view knows. */
    val context: HostContext
        get() =
            base.copy(
                containerDimensions =
                    ContainerDimensions(
                        height = maxHeight,
                        width = width.takeIf { it > 0 },
                        fixedWidth = true,
                    ),
            )

    fun updateBase(context: HostContext) {
        base = context
    }

    fun resize(requested: Int) {
        height = maxHeight?.let { requested.coerceAtMost(it) } ?: requested
    }
}

@Composable
private fun rememberSession(
    tool: ToolWithUi,
    resource: UiResource,
    client: McpAppsHostClient,
    policy: HostPolicy,
    hostContext: HostContext,
    onMessage: State<(AppMessage) -> Unit>,
    onOpenLink: State<(String) -> Unit>,
    approveToolCall: State<suspend (ToolCallRequest) -> Boolean>,
    onBlocked: State<(BlockedAction) -> Unit>,
    onFailed: (RendererFailure) -> Unit,
): Session {
    val scope = rememberCoroutineScope()
    val baseContext = hostContext.copy(toolInfo = tool)
    val failed = rememberUpdatedState(onFailed)
    // The bridge handler needs the session to resize, and the session needs the bridge, so one points at the other.
    return remember(resource) {
        val csp = CspBuilder.build(resource.meta.csp)
        val permissions = policy.resolvePermissions(resource.meta.permissions) intersect grantablePermissions()
        val channel = AppChannel()
        lateinit var session: Session
        val handler =
            object : HostBridgeHandler {
                override suspend fun callTool(request: ToolCallRequest): JsonObject {
                    if (!approveToolCall.value(request)) throw BridgeRejection("The user declined this tool call")
                    return client.callTool(request.name, request.arguments)
                }

                override suspend fun onMessage(message: AppMessage): Boolean {
                    onMessage.value(message)
                    return true
                }

                override fun onOpenLink(url: String): Boolean {
                    onOpenLink.value(url)
                    return true
                }

                override fun onSizeChanged(
                    width: Int?,
                    height: Int?,
                ) {
                    height?.let(session::resize)
                }

                override fun onBlocked(action: BlockedAction) = onBlocked.value(action)
            }
        val bridge =
            HostBridge(
                config =
                    HostBridgeConfig(
                        hostName = HOST_NAME,
                        hostVersion = HOST_VERSION,
                        capabilities = HostCapabilitiesConfig(openLinks = true, serverTools = true, message = true),
                        policy = policy,
                        sandboxPermissions = permissions,
                        sandboxCsp = csp.accepted,
                    ),
                handler = handler,
                transport = { channel.toApp(it) },
                scope = scope,
                tools = client.tools,
                initialContext = baseContext,
            )
        channel.onFromApp = bridge::receive
        val allowed = with(csp.accepted) { connectDomains + resourceDomains + frameDomains }.distinct()
        val spec =
            WebViewSpec(
                html = resource.html,
                csp = csp.header,
                allowedOrigins = allowed,
                permissions = permissions,
                channel = channel,
                onBlocked = { onBlocked.value(it) },
                onFailed = { failed.value(it) },
            )
        Session(bridge, channel, spec, baseContext).also { session = it }
    }.also { it.updateBase(baseContext) }
}

private const val INITIAL_HEIGHT = 150
private const val CORNER = 12
private const val HOST_NAME = "Casement"
private const val HOST_VERSION = "1.0.0"
