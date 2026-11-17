package com.jantursky.casement.demo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * The desktop renderer is experimental. `CASEMENT_AUTORUN=form,hostile` shows those scenarios at start and prints
 * what the sandbox blocked.
 */
fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Casement demo",
            state = rememberWindowState(size = DpSize(480.dp, 860.dp)),
        ) {
            val autoRun = System.getenv("CASEMENT_AUTORUN")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            DemoApp(autoRun = autoRun, onBlocked = { println("BLOCKED ${it.kind}: ${it.detail}") })
        }
    }
