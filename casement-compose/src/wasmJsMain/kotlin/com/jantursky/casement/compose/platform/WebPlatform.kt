@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jantursky.casement.compose.platform

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.jantursky.casement.compose.RendererFailure
import com.jantursky.casement.compose.WebViewSpec
import com.jantursky.casement.model.Platform
import com.jantursky.casement.model.UiPermissions
import kotlin.js.ExperimentalWasmJsInterop

@Composable
internal actual fun PlatformAppWebView(
    spec: WebViewSpec,
    modifier: Modifier,
) {
    LaunchedEffect(spec) { spec.onFailed(RendererFailure.Unsupported) }
    Text("MCP Apps are not supported in the browser yet.", modifier)
}

internal actual fun currentPlatform(): Platform = Platform.Web

private fun browserLanguage(): String? = js("navigator.language")

private fun browserTimeZone(): String? = js("Intl.DateTimeFormat().resolvedOptions().timeZone")

private fun browserPrefersReducedMotion(): Boolean = js("window.matchMedia('(prefers-reduced-motion: reduce)').matches")

internal actual fun currentLocaleTag(): String? = browserLanguage()

internal actual fun currentTimeZoneId(): String? = browserTimeZone()

@Composable
internal actual fun prefersReducedMotion(): Boolean = browserPrefersReducedMotion()

internal actual fun grantablePermissions(): UiPermissions = UiPermissions.None
