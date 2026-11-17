package com.jantursky.casement.bridge

import com.jantursky.casement.jsonrpc.JsonRpcErrorCode
import com.jantursky.casement.model.ContainerDimensions
import com.jantursky.casement.model.DisplayMode
import com.jantursky.casement.model.HostContext
import com.jantursky.casement.model.Theme
import com.jantursky.casement.model.UiCsp
import com.jantursky.casement.model.UiPermissions
import com.jantursky.casement.policy.HostPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostBridgeTest {
    private fun TestScope.harness(
        capabilities: HostCapabilitiesConfig = Harness.ALL,
        context: HostContext = HostContext(),
        config: HostBridgeConfig = HostBridgeConfig("host", "1.0", capabilities),
    ) = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), capabilities, context, config)

    private fun errorCode(message: JsonObject): Int? =
        message["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content
            ?.toInt()

    private fun initWithModes(modes: String): String =
        """{"appInfo":{"name":"a","version":"1"},"appCapabilities":{"availableDisplayModes":$modes}}"""

    private fun JsonObject.params(): JsonObject = this["params"]!!.jsonObject

    // --- handshake -------------------------------------------------------------------------------------------

    @Test
    fun `initialize answers with the pinned version host info capabilities and context`() =
        runTest {
            val h = harness(context = HostContext(theme = Theme.Dark))
            h.request(
                1,
                "ui/initialize",
                """{"appInfo":{"name":"a","version":"1"},"appCapabilities":{},"protocolVersion":"x"}""",
            )
            val result = h.response(1)["result"]!!.jsonObject
            assertEquals("2026-01-26", result["protocolVersion"]!!.jsonPrimitive.content)
            assertEquals("host", result["hostInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("dark", result["hostContext"]!!.jsonObject["theme"]!!.jsonPrimitive.content)
            val caps = result["hostCapabilities"]!!.jsonObject
            listOf(
                "openLinks",
                "serverTools",
                "serverResources",
                "logging",
                "updateModelContext",
                "message",
                "sandbox",
            ).forEach {
                assertNotNull(caps[it], it)
            }
            assertEquals(BridgeState.AwaitingInitialized, h.bridge.state)
        }

    @Test
    fun `capabilities that are off are not advertised`() =
        runTest {
            val h = harness(capabilities = HostCapabilitiesConfig())
            h.request(1, "ui/initialize", """{"appInfo":{"name":"a","version":"1"}}""")
            val caps = h.response(1)["result"]!!.jsonObject["hostCapabilities"]!!.jsonObject
            assertEquals(setOf("sandbox"), caps.keys)
        }

    @Test
    fun `sandbox capability reports what the host really grants`() =
        runTest {
            val h =
                harness(
                    config =
                        HostBridgeConfig(
                            "h",
                            "1",
                            sandboxPermissions =
                                UiPermissions(
                                    camera = true,
                                    microphone = true,
                                    geolocation = true,
                                    clipboardWrite = true,
                                ),
                            sandboxCsp =
                                UiCsp(
                                    listOf("https://a"),
                                    listOf("https://b"),
                                    listOf("https://c"),
                                    listOf("https://d"),
                                ),
                        ),
                )
            h.request(1, "ui/initialize", """{"appInfo":{"name":"a","version":"1"}}""")
            val sandbox =
                h
                    .response(1)["result"]!!
                    .jsonObject["hostCapabilities"]!!
                    .jsonObject["sandbox"]!!
                    .jsonObject
            assertEquals(
                setOf("camera", "microphone", "geolocation", "clipboardWrite"),
                sandbox["permissions"]!!.jsonObject.keys,
            )
            assertEquals(
                setOf("connectDomains", "resourceDomains", "frameDomains", "baseUriDomains"),
                sandbox["csp"]!!.jsonObject.keys,
            )
        }

    @Test
    fun `the handshake completes in order and reports the app`() =
        runTest {
            val h = harness()
            h.handshake()
            assertEquals(BridgeState.Ready, h.bridge.state)
            assertEquals(AppInfo("app", "2"), h.handler.initialized)
        }

    @Test
    fun `initialize needs appInfo`() =
        runTest {
            val h = harness()
            h.request(1, "ui/initialize", """{"appInfo":{"name":"a"}}""")
            assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(h.response(1)))
            assertEquals(
                BlockedKind.Malformed,
                h.handler.blocked
                    .single()
                    .kind,
            )
            assertEquals(BridgeState.AwaitingInitialize, h.bridge.state)
        }

    @Test
    fun `a second initialize is refused`() =
        runTest {
            val h = harness()
            h.handshake()
            h.request(2, "ui/initialize", """{"appInfo":{"name":"a","version":"1"}}""")
            assertEquals(JsonRpcErrorCode.REJECTED, errorCode(h.response(2)))
            assertEquals(
                BlockedKind.Duplicate,
                h.handler.blocked
                    .single()
                    .kind,
            )
        }

    @Test
    fun `requests before the handshake finishes are refused except ping`() =
        runTest {
            val h = harness()
            h.request(1, "tools/call", """{"name":"both"}""")
            assertEquals(JsonRpcErrorCode.REJECTED, errorCode(h.response(1)))
            h.request(2, "ping")
            assertNotNull(h.response(2)["result"])
            h.request(3, "ui/initialize", """{"appInfo":{"name":"a","version":"1"}}""")
            h.request(4, "ui/message", """{"role":"user","content":[]}""")
            assertEquals(JsonRpcErrorCode.REJECTED, errorCode(h.response(4)))
            assertTrue(h.handler.toolCalls.isEmpty())
            assertEquals(listOf(BlockedKind.Premature, BlockedKind.Premature), h.handler.blocked.map { it.kind })
        }

    @Test
    fun `initialized in the wrong state is ignored`() =
        runTest {
            val h = harness()
            h.notification("ui/notifications/initialized")
            assertEquals(BridgeState.AwaitingInitialize, h.bridge.state)
            assertEquals(
                BlockedKind.Premature,
                h.handler.blocked
                    .single()
                    .kind,
            )
        }

    // --- host to app -----------------------------------------------------------------------------------------

    @Test
    fun `tool input sent early is held until the app is ready`() =
        runTest {
            val h = harness()
            h.bridge.sendToolInput(buildJsonObject { put("q", "x") })
            h.bridge.sendToolResult(buildJsonObject { put("isError", false) })
            assertTrue(h.sent.isEmpty())
            h.request(1, "ui/initialize", """{"appInfo":{"name":"a","version":"1"}}""")
            assertEquals(1, h.sent.size)
            h.notification("ui/notifications/initialized")
            assertEquals(listOf(null, "ui/notifications/tool-input", "ui/notifications/tool-result"), h.methods())
            assertEquals(
                "x",
                h.sent[1]
                    .params()["arguments"]!!
                    .jsonObject["q"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `partial input is sent before the final input and never after`() =
        runTest {
            val h = harness()
            h.handshake()
            h.bridge.sendToolInputPartial(buildJsonObject { put("q", "x") })
            h.bridge.sendToolInputPartial(buildJsonObject { put("q", "xy") })
            h.bridge.sendToolInput(buildJsonObject { put("q", "xyz") })
            h.bridge.sendToolInput(buildJsonObject { put("q", "again") })
            h.bridge.sendToolInputPartial(buildJsonObject { put("q", "late") })
            assertEquals(
                listOf(
                    null,
                    "ui/notifications/tool-input-partial",
                    "ui/notifications/tool-input-partial",
                    "ui/notifications/tool-input",
                ),
                h.methods(),
            )
        }

    @Test
    fun `a result without input sends empty input first`() =
        runTest {
            val h = harness()
            h.handshake()
            h.bridge.sendToolResult(buildJsonObject { put("isError", false) })
            assertEquals(listOf(null, "ui/notifications/tool-input", "ui/notifications/tool-result"), h.methods())
            assertEquals(
                0,
                h.sent[1]
                    .params()["arguments"]!!
                    .jsonObject.size,
            )
        }

    @Test
    fun `nothing follows a result or a cancellation`() =
        runTest {
            val h = harness()
            h.handshake()
            h.bridge.sendToolCancelled("user stopped")
            h.bridge.sendToolCancelled()
            h.bridge.sendToolResult(JsonObject(emptyMap()))
            h.bridge.sendToolInput(JsonObject(emptyMap()))
            assertEquals(listOf(null, "ui/notifications/tool-cancelled"), h.methods())
            assertEquals(
                "user stopped",
                h.sent[1]
                    .params()["reason"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `a cancellation without a reason has empty params`() =
        runTest {
            val h = harness()
            h.handshake()
            h.bridge.sendToolCancelled()
            assertEquals(0, h.sent[1].params().size)
        }

    @Test
    fun `context changes send only the difference`() =
        runTest {
            val h = harness(context = HostContext(theme = Theme.Light))
            h.handshake()
            h.bridge.updateContext(HostContext(theme = Theme.Dark))
            h.bridge.updateContext(HostContext(theme = Theme.Dark))
            assertEquals(listOf(null, "ui/notifications/host-context-changed"), h.methods())
            assertEquals(setOf("theme"), h.sent[1].params().keys)
        }

    @Test
    fun `context changed before initialize is part of the initialize answer`() =
        runTest {
            val h = harness(context = HostContext(theme = Theme.Light))
            h.bridge.updateContext(HostContext(theme = Theme.Dark))
            h.request(1, "ui/initialize", """{"appInfo":{"name":"a","version":"1"}}""")
            assertEquals(
                "dark",
                h
                    .response(1)["result"]!!
                    .jsonObject["hostContext"]!!
                    .jsonObject["theme"]!!
                    .jsonPrimitive.content,
            )
            h.notification("ui/notifications/initialized")
            assertEquals(1, h.sent.size)
        }

    @Test
    fun `context changed between initialize and initialized is delivered once ready`() =
        runTest {
            val h = harness(context = HostContext(theme = Theme.Light))
            h.request(1, "ui/initialize", """{"appInfo":{"name":"a","version":"1"}}""")
            h.bridge.updateContext(HostContext(theme = Theme.Dark))
            assertEquals(1, h.sent.size)
            h.notification("ui/notifications/initialized")
            assertEquals(listOf(null, "ui/notifications/host-context-changed"), h.methods())
        }

    // --- app to host: tools ----------------------------------------------------------------------------------

    @Test
    fun `app can call tools that are visible to apps`() =
        runTest {
            val h = harness()
            h.handshake()
            h.request(2, "tools/call", """{"name":"app_only","arguments":{"a":1}}""")
            h.request(3, "tools/call", """{"name":"both"}""")
            assertEquals(listOf("app_only", "both"), h.handler.toolCalls.map { it.name })
            assertEquals(
                1,
                h.handler.toolCalls[0]
                    .arguments
                    ?.size,
            )
            assertNull(h.handler.toolCalls[1].arguments)
            assertNotNull(h.response(2)["result"]!!.jsonObject["content"])
        }

    @Test
    fun `app cannot call model only or unknown tools`() =
        runTest {
            val h = harness()
            h.handshake()
            h.request(2, "tools/call", """{"name":"model_only"}""")
            h.request(3, "tools/call", """{"name":"nope"}""")
            assertEquals(JsonRpcErrorCode.REJECTED, errorCode(h.response(2)))
            assertEquals(JsonRpcErrorCode.REJECTED, errorCode(h.response(3)))
            assertTrue(h.handler.toolCalls.isEmpty())
            assertEquals(listOf(BlockedKind.ToolNotVisible, BlockedKind.ToolUnknown), h.handler.blocked.map { it.kind })
        }

    @Test
    fun `tool calls without a name are invalid`() =
        runTest {
            val h = harness()
            h.handshake()
            h.request(2, "tools/call", """{"arguments":{}}""")
            assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(h.response(2)))
        }

    @Test
    fun `a handler rejection reaches the app with its message`() =
        runTest {
            val h = harness()
            h.handler.failWith = BridgeRejection("User declined")
            h.handshake()
            h.request(2, "tools/call", """{"name":"both"}""")
            val error = h.response(2)["error"]!!.jsonObject
            assertEquals("User declined", error["message"]!!.jsonPrimitive.content)
            assertEquals(
                BlockedKind.Rejected,
                h.handler.blocked
                    .single()
                    .kind,
            )
        }

    @Test
    fun `an unexpected handler failure does not leak its message`() =
        runTest {
            val h = harness()
            h.handler.failWith = IllegalStateException("secret path /data/token")
            h.handshake()
            h.request(2, "tools/call", """{"name":"both"}""")
            val error = h.response(2)["error"]!!.jsonObject
            assertEquals(JsonRpcErrorCode.INTERNAL_ERROR, error["code"]!!.jsonPrimitive.content.toInt())
            assertTrue("secret" !in error["message"]!!.jsonPrimitive.content)
        }

    @Test
    fun `tool calls fail closed when the capability is off`() =
        runTest {
            val h = harness(capabilities = HostCapabilitiesConfig())
            h.handshake()
            h.request(2, "tools/call", """{"name":"both"}""")
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND, errorCode(h.response(2)))
            assertTrue(h.handler.toolCalls.isEmpty())
            assertEquals(
                BlockedKind.Capability,
                h.handler.blocked
                    .single()
                    .kind,
            )
        }

    // --- app to host: everything else ------------------------------------------------------------------------

    @Test
    fun `messages and model context go to the handler`() =
        runTest {
            val h = harness()
            h.handshake()
            h.request(2, "ui/message", """{"role":"user","content":[{"type":"text","text":"hi"}]}""")
            h.request(3, "ui/update-model-context", """{"structuredContent":{"k":1}}""")
            assertEquals(
                "user",
                h.handler.messages
                    .single()
                    .role,
            )
            assertEquals(0, h.response(2)["result"]!!.jsonObject.size)
            assertEquals(1, h.handler.modelContexts.size)
            assertNotNull(h.response(3)["result"])
            h.handler.messageAccepted = false
            h.request(4, "ui/message", """{"role":"user","content":"x"}""")
            assertEquals(
                true,
                h
                    .response(4)["result"]!!
                    .jsonObject["isError"]!!
                    .jsonPrimitive.content
                    .toBoolean(),
            )
        }

    @Test
    fun `messages need a role and content`() =
        runTest {
            val h = harness()
            h.handshake()
            h.request(2, "ui/message", """{"role":"user"}""")
            assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(h.response(2)))
        }

    @Test
    fun `links with other schemes never reach the handler`() =
        runTest {
            val h = harness()
            h.handshake()
            h.request(2, "ui/open-link", """{"url":"https://example.com"}""")
            h.request(3, "ui/open-link", """{"url":"javascript:alert(1)"}""")
            h.request(4, "ui/open-link", """{"url":"file:///etc/passwd"}""")
            h.request(5, "ui/open-link", """{}""")
            assertEquals(listOf("https://example.com"), h.handler.links)
            assertNotNull(h.response(2)["result"])
            assertEquals(
                true,
                h
                    .response(3)["result"]!!
                    .jsonObject["isError"]!!
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(h.response(5)))
            assertEquals(2, h.handler.blocked.count { it.kind == BlockedKind.Link })
        }

    @Test
    fun `a link the handler declines is reported as an error`() =
        runTest {
            val h = harness()
            h.handler.linkOpened = false
            h.handshake()
            h.request(2, "ui/open-link", """{"url":"https://example.com"}""")
            assertEquals(
                true,
                h
                    .response(2)["result"]!!
                    .jsonObject["isError"]!!
                    .jsonPrimitive.content
                    .toBoolean(),
            )
        }

    @Test
    fun `resource requests are forwarded only when enabled`() =
        runTest {
            val h = harness()
            h.handshake()
            h.request(2, "resources/read", """{"uri":"file:///x"}""")
            h.request(3, "prompts/list")
            assertEquals(listOf("resources/read", "prompts/list"), h.handler.forwarded)
            val off = harness(capabilities = HostCapabilitiesConfig())
            off.handshake()
            off.request(2, "resources/read", """{"uri":"x"}""")
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND, errorCode(off.response(2)))
            assertTrue(off.handler.forwarded.isEmpty())
        }

    @Test
    fun `unknown methods and sampling are not found`() =
        runTest {
            val h = harness()
            h.handshake()
            h.request(2, "sampling/createMessage", """{"messages":[]}""")
            h.request(3, "ui/download-file", """{"contents":[]}""")
            h.request(4, "made/up")
            listOf(2, 3, 4).forEach { assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND, errorCode(h.response(it))) }
        }

    @Test
    fun `size changes are clamped and bad values ignored`() =
        runTest {
            val h = harness()
            h.handshake()
            h.notification("ui/notifications/size-changed", """{"width":300.7,"height":480}""")
            h.notification("ui/notifications/size-changed", """{"height":1e12}""")
            h.notification("ui/notifications/size-changed", """{"width":-5,"height":"tall"}""")
            h.notification("ui/notifications/size-changed", """{}""")
            assertEquals(listOf<Pair<Int?, Int?>>(300 to 480, null to 100000), h.handler.sizes)
        }

    @Test
    fun `size changes before the handshake finishes are still accepted`() =
        runTest {
            val h = harness()
            h.notification("ui/notifications/size-changed", """{"height":50}""")
            assertEquals(listOf<Pair<Int?, Int?>>(null to 50), h.handler.sizes)
        }

    @Test
    fun `logs and teardown requests are passed on`() =
        runTest {
            val h = harness()
            h.handshake()
            h.notification("notifications/message", """{"level":"info","data":"x"}""")
            h.notification("ui/notifications/request-teardown")
            assertEquals(1, h.handler.logs.size)
            assertEquals(1, h.handler.teardownRequests)
            val noLogging = harness(capabilities = HostCapabilitiesConfig())
            noLogging.handshake()
            noLogging.notification("notifications/message", """{"data":"x"}""")
            assertTrue(noLogging.handler.logs.isEmpty())
        }

    @Test
    fun `unknown notifications are ignored and reported`() =
        runTest {
            val h = harness()
            h.handshake()
            h.notification("ui/notifications/surprise")
            assertEquals(
                BlockedKind.Capability,
                h.handler.blocked
                    .single()
                    .kind,
            )
        }

    @Test
    fun `display mode requests honour the host and the app`() =
        runTest {
            val context = HostContext(availableDisplayModes = listOf(DisplayMode.Inline, DisplayMode.Fullscreen))
            val h = harness(context = context)
            h.request(1, "ui/initialize", initWithModes("""["inline","fullscreen"]"""))
            h.notification("ui/notifications/initialized")
            h.request(2, "ui/request-display-mode", """{"mode":"pip"}""")
            assertEquals(
                "inline",
                h
                    .response(2)["result"]!!
                    .jsonObject["mode"]!!
                    .jsonPrimitive.content,
            )
            h.request(3, "ui/request-display-mode", """{"mode":"fullscreen"}""")
            assertEquals(
                "inline",
                h
                    .response(3)["result"]!!
                    .jsonObject["mode"]!!
                    .jsonPrimitive.content,
            )
            h.handler.displayModeAnswer = DisplayMode.Fullscreen
            h.request(4, "ui/request-display-mode", """{"mode":"fullscreen"}""")
            assertEquals(
                "fullscreen",
                h
                    .response(4)["result"]!!
                    .jsonObject["mode"]!!
                    .jsonPrimitive.content,
            )
            assertEquals("ui/notifications/host-context-changed", h.methods().last())
            h.request(5, "ui/request-display-mode", """{"mode":"bogus"}""")
            assertEquals(2, h.handler.blocked.count { it.kind == BlockedKind.DisplayMode })
        }

    @Test
    fun `a mode the app did not declare is refused`() =
        runTest {
            val context = HostContext(availableDisplayModes = listOf(DisplayMode.Inline, DisplayMode.Fullscreen))
            val h = harness(context = context)
            h.request(1, "ui/initialize", initWithModes("""["inline"]"""))
            h.notification("ui/notifications/initialized")
            h.handler.displayModeAnswer = DisplayMode.Fullscreen
            h.request(2, "ui/request-display-mode", """{"mode":"fullscreen"}""")
            assertEquals(
                "inline",
                h
                    .response(2)["result"]!!
                    .jsonObject["mode"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `ping is answered at any time`() =
        runTest {
            val h = harness()
            h.request(9, "ping")
            assertEquals(0, h.response(9)["result"]!!.jsonObject.size)
        }

    // --- framing and limits ----------------------------------------------------------------------------------

    @Test
    fun `oversized messages are dropped`() =
        runTest {
            val h = harness(config = HostBridgeConfig("h", "1", policy = HostPolicy(maxMessageLength = 50)))
            h.app("""{"jsonrpc":"2.0","id":1,"method":"ping","params":{"pad":"${"x".repeat(100)}"}}""")
            assertTrue(h.sent.isEmpty())
            assertEquals(
                BlockedKind.Oversize,
                h.handler.blocked
                    .single()
                    .kind,
            )
        }

    @Test
    fun `garbage is reported and invalid json gets a parse error`() =
        runTest {
            val h = harness()
            h.app("not json")
            assertEquals(-32700, errorCode(h.sent.single()))
            h.app("""{"jsonrpc":"2.0","method":5}""")
            assertEquals(1, h.sent.size)
            assertEquals(listOf(BlockedKind.Malformed, BlockedKind.Malformed), h.handler.blocked.map { it.kind })
        }

    @Test
    fun `stray responses are ignored`() =
        runTest {
            val h = harness()
            h.handshake()
            h.app("""{"jsonrpc":"2.0","id":"nope","result":{}}""")
            h.app("""{"jsonrpc":"2.0","id":null,"error":{"code":1,"message":"m"}}""")
            assertEquals(1, h.sent.size)
        }

    // --- teardown --------------------------------------------------------------------------------------------

    @Test
    fun `teardown asks the app to clean up and closes after the answer`() =
        runTest {
            val h = harness()
            h.handshake()
            val job = launchTeardown(h)
            assertEquals("ui/resource-teardown", h.methods().last())
            val id =
                h.sent
                    .last()["id"]!!
                    .jsonPrimitive.content
            assertEquals(
                "host closed the app",
                h.sent
                    .last()
                    .params()["reason"]!!
                    .jsonPrimitive.content,
            )
            h.app("""{"jsonrpc":"2.0","id":"$id","result":{}}""")
            job.join()
            assertEquals(BridgeState.Closed, h.bridge.state)
        }

    @Test
    fun `teardown gives up when the app stays silent`() =
        runTest {
            val h = harness(config = HostBridgeConfig("h", "1", teardownTimeoutMillis = 100))
            h.handshake()
            h.bridge.teardown("bye")
            assertEquals(BridgeState.Closed, h.bridge.state)
            assertEquals(
                "bye",
                h.sent
                    .last()
                    .params()["reason"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `teardown before the handshake sends nothing`() =
        runTest {
            val h = harness()
            h.bridge.teardown()
            assertTrue(h.sent.isEmpty())
            assertEquals(BridgeState.Closed, h.bridge.state)
        }

    @Test
    fun `close after teardown sends the request and then closes`() =
        runTest {
            val h = harness()
            h.handshake()
            h.bridge.closeAfterTeardown("view left")
            assertEquals("ui/resource-teardown", h.methods().last())
            assertEquals(
                "view left",
                h.sent
                    .last()
                    .params()["reason"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(BridgeState.Closed, h.bridge.state)
        }

    @Test
    fun `a closed bridge ignores everything`() =
        runTest {
            val h = harness()
            h.handshake()
            h.bridge.close()
            h.request(2, "ping")
            h.bridge.sendToolInput(JsonObject(emptyMap()))
            h.bridge.teardown()
            assertEquals(1, h.sent.size)
        }

    @Test
    fun `closing cancels tool calls in flight`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val handler =
                object : HostBridgeHandler {
                    override suspend fun callTool(request: ToolCallRequest): JsonObject {
                        gate.await()
                        return JsonObject(emptyMap())
                    }
                }
            val sent = mutableListOf<String>()
            val parent = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            val bridge =
                HostBridge(
                    HostBridgeConfig("h", "1", HostCapabilitiesConfig(serverTools = true)),
                    handler,
                    { sent += it },
                    parent,
                    ToolCatalog.of(mapOf("t" to setOf(com.jantursky.casement.model.ToolVisibility.App))),
                )
            bridge.receive(
                """{"jsonrpc":"2.0","id":1,"method":"ui/initialize","params":{"appInfo":{"name":"a","version":"1"}}}""",
            )
            bridge.receive("""{"jsonrpc":"2.0","method":"ui/notifications/initialized"}""")
            bridge.receive("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"t"}}""")
            bridge.close()
            gate.complete(Unit)
            assertEquals(1, sent.size)
            parent.cancel()
        }

    private fun TestScope.launchTeardown(h: Harness) =
        launch(UnconfinedTestDispatcher(testScheduler)) {
            h.bridge.teardown()
        }
}
