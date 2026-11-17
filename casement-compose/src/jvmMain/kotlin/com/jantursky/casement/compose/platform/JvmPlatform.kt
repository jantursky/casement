package com.jantursky.casement.compose.platform

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jantursky.casement.compose.RendererFailure
import com.jantursky.casement.compose.WebViewSpec
import com.jantursky.casement.model.Platform
import com.jantursky.casement.model.UiPermissions
import java.util.Locale
import java.util.TimeZone

@Composable
internal actual fun PlatformAppWebView(
    spec: WebViewSpec,
    modifier: Modifier,
) {
    androidx.compose.runtime.LaunchedEffect(spec) { spec.onFailed(RendererFailure.Unsupported) }
    Text("MCP Apps are not supported on desktop yet.", modifier)
}

internal actual fun currentPlatform(): Platform = Platform.Desktop

internal actual fun currentLocaleTag(): String? = Locale.getDefault().toLanguageTag()

internal actual fun currentTimeZoneId(): String? = TimeZone.getDefault().id

@Composable
internal actual fun prefersReducedMotion(): Boolean = false

internal actual fun grantablePermissions(): UiPermissions = UiPermissions.None
