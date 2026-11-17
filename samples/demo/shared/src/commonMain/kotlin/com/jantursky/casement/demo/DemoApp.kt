package com.jantursky.casement.demo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.compose.McpAppView
import com.jantursky.casement.demo.resources.Res
import com.jantursky.casement.mcp.connectMcpAppsHost
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal enum class ThemeChoice(
    val label: String,
) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1F1B16),
        onPrimary = Color(0xFFFFFFFF),
        background = Color(0xFFFAF7F2),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEFE9DF),
        onSurface = Color(0xFF1F1B16),
        outline = Color(0xFFCFC6B8),
    )
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFF2EDE4),
        onPrimary = Color(0xFF1F1B16),
        background = Color(0xFF14110E),
        surface = Color(0xFF1D1914),
        surfaceVariant = Color(0xFF2A251E),
        onSurface = Color(0xFFF2EDE4),
        outline = Color(0xFF4A4338),
    )

private sealed interface Entry {
    data class Text(
        val fromUser: Boolean,
        val text: String,
    ) : Entry

    class App(
        val scenario: Scenario,
        val result: JsonObject?,
    ) : Entry
}

private enum class Source(
    val label: String,
) {
    BuiltIn("Built-in"),
    Servers("Servers"),
}

/** The example servers `scripts/run-sample-servers.sh` starts. */
private val SERVER_PORTS = listOf(39101, 39102, 39103)

/** The Casement demo: a chat where the assistant answers with MCP Apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun DemoApp(
    autoRun: List<String> = emptyList(),
    onBlocked: (BlockedAction) -> Unit = {},
) {
    var choice by remember { mutableStateOf(ThemeChoice.System) }
    val dark =
        when (choice) {
            ThemeChoice.System -> isSystemInDarkTheme()
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
        }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        val client = remember { DemoClient { Res.readBytes("files/$it").decodeToString() } }
        val builtIn = remember { builtInScenarios(client) }
        val servers = remember { mutableStateListOf<Scenario>() }
        var source by remember { mutableStateOf(Source.BuiltIn) }
        var serverStatus by remember { mutableStateOf<String?>(null) }
        val entries = remember { mutableStateListOf<Entry>(Entry.Text(false, "Pick something and I will show it.")) }
        val blocked = remember { mutableStateListOf<BlockedAction>() }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        fun show(scenario: Scenario) {
            entries += Entry.Text(true, scenario.label)
            entries += Entry.Text(false, scenario.reply)
            scope.launch {
                val result =
                    try {
                        scenario.result()
                    } catch (e: Exception) {
                        entries += Entry.Text(false, "The tool call failed: ${e.message}")
                        return@launch
                    }
                entries += Entry.App(scenario, result)
            }
        }

        LaunchedEffect(entries.size) { listState.animateScrollToItem(entries.lastIndex) }
        LaunchedEffect(source) {
            if (source == Source.Servers && servers.isEmpty()) {
                serverStatus = "Connecting..."
                servers += connectServers { serverStatus = it }
                serverStatus = if (servers.isEmpty()) serverStatus ?: "No server answered." else null
            }
        }
        var started by remember { mutableStateOf(emptySet<String>()) }
        LaunchedEffect(autoRun, servers.size) {
            if (autoRun.any { id -> builtIn.none { it.id == id } }) source = Source.Servers
            autoRun.filter { it !in started }.forEach { id ->
                (builtIn + servers).firstOrNull { it.id == id }?.let {
                    started += id
                    show(it)
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Casement") },
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            ThemeChoice.entries.forEach { option ->
                                FilterChip(
                                    selected = choice == option,
                                    onClick = { choice = option },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (blocked.isNotEmpty()) BlockedPanel(blocked)
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Source.entries.forEach { option ->
                            FilterChip(
                                selected = source == option,
                                onClick = { source = option },
                                label = { Text(option.label) },
                            )
                        }
                        serverStatus?.takeIf { source == Source.Servers }?.let {
                            Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(if (source == Source.BuiltIn) builtIn else servers.toList()) { scenario ->
                            AssistChip(onClick = { show(scenario) }, label = { Text(scenario.label) })
                        }
                    }
                }
            },
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries.size) { index ->
                        when (val entry = entries[index]) {
                            is Entry.Text -> {
                                Bubble(entry)
                            }

                            is Entry.App -> {
                                Card(
                                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    McpAppView(
                                        tool = entry.scenario.tool,
                                        toolInput = null,
                                        toolResult = entry.result,
                                        client = entry.scenario.client,
                                        modifier = Modifier.fillMaxWidth(),
                                        onMessage = { entries += Entry.Text(true, "(from app) ${it.content}") },
                                        approveToolCall = {
                                            entries += Entry.Text(false, "The app called ${it.name}")
                                            true
                                        },
                                        onOpenLink = { entries += Entry.Text(false, "The app wants to open $it") },
                                        onBlocked = {
                                            blocked += it
                                            onBlocked(it)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Connects to every example server that answers and returns one scenario per tool that has a UI. */
private suspend fun connectServers(status: (String) -> Unit): List<Scenario> {
    val http = demoHttpClient()
    val found = mutableListOf<Scenario>()
    SERVER_PORTS.forEach { port ->
        val url = "http://${serverHost()}:$port/mcp"
        try {
            val host = connectMcpAppsHost(http, url)
            host.refreshTools().forEach { tool ->
                found +=
                    Scenario(
                        id = tool.name,
                        label = tool.name,
                        reply = "Here is ${tool.name} from the server on port $port.",
                        tool = tool,
                        client = host,
                        result = { host.callTool(tool.name, null) },
                    )
            }
        } catch (e: Exception) {
            status("No server on port $port. Run scripts/run-sample-servers.sh")
        }
    }
    return found
}

@Composable
private fun Bubble(entry: Entry.Text) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (entry.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (entry.fromUser) scheme.primary else scheme.surfaceVariant,
            contentColor = if (entry.fromUser) scheme.onPrimary else scheme.onSurface,
        ) {
            Text(entry.text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun BlockedPanel(blocked: List<BlockedAction>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Blocked: ${blocked.size}", style = MaterialTheme.typography.labelLarge)
            blocked.takeLast(MAX_SHOWN).forEach {
                Text("${it.kind}: ${it.detail}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

private const val MAX_SHOWN = 12
