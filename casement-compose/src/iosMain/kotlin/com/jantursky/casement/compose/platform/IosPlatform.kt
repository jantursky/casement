package com.jantursky.casement.compose.platform

import androidx.compose.runtime.Composable
import com.jantursky.casement.model.Platform
import com.jantursky.casement.model.UiPermissions
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.preferredLanguages
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

internal actual fun currentPlatform(): Platform = Platform.Mobile

internal actual fun currentLocaleTag(): String? = NSLocale.preferredLanguages.firstOrNull() as? String

internal actual fun currentTimeZoneId(): String? = NSTimeZone.localTimeZone.name

@Composable
internal actual fun prefersReducedMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()

// Camera and microphone work when the host app declares the usage strings and the user agrees. Location and
// clipboard writes cannot be controlled per web view on iOS, so they are never granted.
internal actual fun grantablePermissions(): UiPermissions = UiPermissions(camera = true, microphone = true)
