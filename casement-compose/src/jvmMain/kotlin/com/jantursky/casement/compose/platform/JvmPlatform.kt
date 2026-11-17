package com.jantursky.casement.compose.platform

import androidx.compose.runtime.Composable
import com.jantursky.casement.model.Platform
import com.jantursky.casement.model.UiPermissions
import java.util.Locale
import java.util.TimeZone

internal actual fun currentPlatform(): Platform = Platform.Desktop

internal actual fun currentLocaleTag(): String? = Locale.getDefault().toLanguageTag()

internal actual fun currentTimeZoneId(): String? = TimeZone.getDefault().id

@Composable
internal actual fun prefersReducedMotion(): Boolean = false

internal actual fun grantablePermissions(): UiPermissions = UiPermissions.None
