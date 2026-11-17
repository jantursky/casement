@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jantursky.casement.compose.platform

import androidx.compose.runtime.Composable
import com.jantursky.casement.model.Platform
import com.jantursky.casement.model.UiPermissions
import kotlin.js.ExperimentalWasmJsInterop

internal actual fun currentPlatform(): Platform = Platform.Web

private fun browserLanguage(): String? = js("navigator.language")

private fun browserTimeZone(): String? = js("Intl.DateTimeFormat().resolvedOptions().timeZone")

private fun browserPrefersReducedMotion(): Boolean = js("window.matchMedia('(prefers-reduced-motion: reduce)').matches")

internal actual fun currentLocaleTag(): String? = browserLanguage()

internal actual fun currentTimeZoneId(): String? = browserTimeZone()

@Composable
internal actual fun prefersReducedMotion(): Boolean = browserPrefersReducedMotion()

// The nested iframe's `allow` attribute decides, so all four can be granted.
internal actual fun grantablePermissions(): UiPermissions =
    UiPermissions(camera = true, microphone = true, geolocation = true, clipboardWrite = true)
