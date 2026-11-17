package com.jantursky.casement.demo

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jantursky.casement.compose.platform.initializeDesktopRenderer
import java.io.File

/**
 * The desktop renderer is experimental. It needs the Chromium engine, which is downloaded on first run.
 * `CASEMENT_AUTORUN=form,hostile` shows those scenarios at start and prints what the sandbox blocked.
 */
fun main() =
    application {
        var ready by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Starting the browser engine...") }
        LaunchedEffect(Unit) {
            initializeDesktopRenderer(
                installDir = File(System.getProperty("user.home"), ".casement/cef"),
                onProgress = { percent ->
                    if (percent ==
                        null
                    ) {
                        ready = true
                    } else {
                        status = "Downloading the engine: ${percent.toInt()}%"
                    }
                },
                onError = { status = "The engine could not start: ${it?.message}" },
            )
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Casement demo",
            state = rememberWindowState(size = DpSize(480.dp, 860.dp)),
        ) {
            if (ready) {
                val autoRun = System.getenv("CASEMENT_AUTORUN")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
                DemoApp(autoRun = autoRun, onBlocked = { println("BLOCKED ${it.kind}: ${it.detail}") })
            } else {
                Text(status)
            }
        }
    }
