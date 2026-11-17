package com.jantursky.casement.compose.platform

import dev.datlag.kcef.KCEF
import java.io.File

/**
 * Sets up the browser engine the desktop renderer runs on. Call it once at start-up, before the first
 * `McpAppView`; until it finishes, apps are not rendered. The first run downloads the Chromium Embedded Framework
 * bundle (about 200 MB) into [installDir], later runs reuse it.
 *
 * The desktop renderer is experimental: it has been run only on macOS (arm64).
 *
 * @param installDir Where the engine is kept.
 * @param onProgress Download progress in percent, or null once the engine is ready.
 * @param onError Called when the engine could not be set up. Apps then show the "not supported" state.
 */
public suspend fun initializeDesktopRenderer(
    installDir: File = File("casement-cef"),
    onProgress: (Float?) -> Unit = {},
    onError: (Throwable?) -> Unit = {},
) {
    KCEF.init(
        builder = {
            installDir(installDir)
            progress {
                onDownloading { onProgress(it) }
                onInitialized { onProgress(null) }
            }
            settings { cachePath = File(installDir, "cache").absolutePath }
        },
        onError = { onError(it) },
        onRestartRequired = { onError(IllegalStateException("The browser engine needs a restart")) },
    )
}

/** Releases the browser engine. Call it when the application exits. */
public suspend fun disposeDesktopRenderer() {
    KCEF.dispose()
}
