package com.jantursky.casement.compose.platform

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.jantursky.casement.compose.RendererFailure
import com.jantursky.casement.compose.WebViewSpec
import com.jantursky.casement.model.Platform
import com.jantursky.casement.model.UiPermissions
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.preferredLanguages
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
internal actual fun PlatformAppWebView(
    spec: WebViewSpec,
    modifier: Modifier,
) {
    LaunchedEffect(spec) { spec.onFailed(RendererFailure.Unsupported) }
    Text("MCP Apps are not supported on iOS yet.", modifier)
}

internal actual fun currentPlatform(): Platform = Platform.Mobile

internal actual fun currentLocaleTag(): String? = NSLocale.preferredLanguages.firstOrNull() as? String

internal actual fun currentTimeZoneId(): String? = NSTimeZone.localTimeZone.name

@Composable
internal actual fun prefersReducedMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()

internal actual fun grantablePermissions(): UiPermissions = UiPermissions.None
