package com.jantursky.casement.compose.platform

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.jantursky.casement.model.Platform
import com.jantursky.casement.model.UiPermissions
import java.util.Locale
import java.util.TimeZone

internal actual fun currentPlatform(): Platform = Platform.Mobile

internal actual fun currentLocaleTag(): String? = Locale.getDefault().toLanguageTag()

internal actual fun currentTimeZoneId(): String? = TimeZone.getDefault().id

@Composable
internal actual fun prefersReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

// Granting camera, microphone or location would also need the host app's runtime permissions. Android grants none.
internal actual fun grantablePermissions(): UiPermissions = UiPermissions.None
