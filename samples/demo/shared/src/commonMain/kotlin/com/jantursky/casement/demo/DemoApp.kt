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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.compose.McpAppView
import com.jantursky.casement.demo.resources.Res

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
    ) : Entry
}

/** The Casement demo: a chat where the assistant answers with MCP Apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun DemoApp() {
    var choice by remember { mutableStateOf(ThemeChoice.System) }
    val dark =
        when (choice) {
            ThemeChoice.System -> isSystemInDarkTheme()
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
        }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        val client = remember { DemoClient { Res.readBytes("files/$it").decodeToString() } }
        val entries = remember { mutableStateListOf<Entry>(Entry.Text(false, "Pick something and I will show it.")) }
        val blocked = remember { mutableStateListOf<BlockedAction>() }
        val listState = rememberLazyListState()
        LaunchedEffect(entries.size) { listState.animateScrollToItem(entries.lastIndex) }

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
                Column(Modifier.padding(vertical = 8.dp)) {
                    if (blocked.isNotEmpty()) BlockedPanel(blocked)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(SCENARIOS) { scenario ->
                            AssistChip(
                                onClick = {
                                    entries += Entry.Text(true, scenario.label)
                                    entries += Entry.Text(false, scenario.reply)
                                    entries += Entry.App(scenario)
                                },
                                label = { Text(scenario.label) },
                            )
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
                                        toolResult = entry.scenario.result(client),
                                        client = client,
                                        modifier = Modifier.fillMaxWidth(),
                                        onMessage = { entries += Entry.Text(true, "(from app) ${it.content}") },
                                        approveToolCall = {
                                            entries += Entry.Text(false, "The app called ${it.name}")
                                            true
                                        },
                                        onOpenLink = { entries += Entry.Text(false, "The app wants to open $it") },
                                        onBlocked = { blocked += it },
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
