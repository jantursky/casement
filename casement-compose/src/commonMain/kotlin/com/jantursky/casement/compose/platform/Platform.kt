package com.jantursky.casement.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jantursky.casement.compose.WebViewSpec
import com.jantursky.casement.model.Platform
import com.jantursky.casement.model.UiPermissions

/** Shows [spec] in the platform's sandboxed web view. */
@Composable
internal expect fun PlatformAppWebView(
    spec: WebViewSpec,
    modifier: Modifier,
)

internal expect fun currentPlatform(): Platform

internal expect fun currentLocaleTag(): String?

internal expect fun currentTimeZoneId(): String?

@Composable
internal expect fun prefersReducedMotion(): Boolean

/** The permissions this platform's renderer can grant at all. A permission outside this set is always denied. */
internal expect fun grantablePermissions(): UiPermissions
