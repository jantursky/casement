package com.jantursky.casement.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.jantursky.casement.compose.platform.currentLocaleTag
import com.jantursky.casement.compose.platform.currentPlatform
import com.jantursky.casement.compose.platform.currentTimeZoneId
import com.jantursky.casement.compose.platform.prefersReducedMotion
import com.jantursky.casement.model.HostContext
import com.jantursky.casement.model.Theme
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * A [HostContext] built from the current Material 3 theme and the user's settings: light or dark theme,
 * the spec's style variables mapped from the color scheme, locale, time zone and platform. Reduced motion,
 * font scale and text direction go in as the extra keys `prefersReducedMotion`, `fontScale` and `textDirection`.
 * Those three are Casement conventions, not part of the spec.
 *
 * Build a [HostContext] by hand to use another design system.
 */
@Composable
public fun rememberHostContext(): HostContext {
    val colors = MaterialTheme.colorScheme
    val reducedMotion = prefersReducedMotion()
    val fontScale = LocalDensity.current.fontScale
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return HostContext(
        theme = if (colors.background.luminance() < DARK_THRESHOLD) Theme.Dark else Theme.Light,
        styleVariables = materialStyleVariables(colors),
        locale = currentLocaleTag(),
        timeZone = currentTimeZoneId(),
        platform = currentPlatform(),
        extras =
            mapOf(
                "prefersReducedMotion" to JsonPrimitive(reducedMotion),
                "fontScale" to JsonPrimitive(fontScale),
                "textDirection" to JsonPrimitive(if (rtl) "rtl" else "ltr"),
            ),
    )
}

private const val DARK_THRESHOLD = 0.5f

/** Maps a Material 3 color scheme to the spec's `--color-*` variables and adds the type, radius and shadow tokens. */
internal fun materialStyleVariables(c: ColorScheme): Map<String, String> =
    buildMap {
        fun put(
            name: String,
            color: Color,
        ) = put(name, color.toCss())

        put("--color-background-primary", c.surface)
        put("--color-background-secondary", c.surfaceContainer)
        put("--color-background-tertiary", c.surfaceContainerHigh)
        put("--color-background-inverse", c.inverseSurface)
        put("--color-background-ghost", Color.Transparent)
        put("--color-background-info", c.primaryContainer)
        put("--color-background-danger", c.errorContainer)
        put("--color-background-success", c.tertiaryContainer)
        put("--color-background-warning", c.secondaryContainer)
        put("--color-background-disabled", c.onSurface.copy(alpha = DISABLED_FILL))

        put("--color-text-primary", c.onSurface)
        put("--color-text-secondary", c.onSurfaceVariant)
        put("--color-text-tertiary", c.outline)
        put("--color-text-inverse", c.inverseOnSurface)
        put("--color-text-ghost", c.onSurface.copy(alpha = GHOST_TEXT))
        put("--color-text-info", c.primary)
        put("--color-text-danger", c.error)
        put("--color-text-success", c.tertiary)
        put("--color-text-warning", c.secondary)
        put("--color-text-disabled", c.onSurface.copy(alpha = DISABLED_TEXT))

        put("--color-border-primary", c.outline)
        put("--color-border-secondary", c.outlineVariant)
        put("--color-border-tertiary", c.surfaceVariant)
        put("--color-border-inverse", c.inverseOnSurface)
        put("--color-border-ghost", Color.Transparent)
        put("--color-border-info", c.primary)
        put("--color-border-danger", c.error)
        put("--color-border-success", c.tertiary)
        put("--color-border-warning", c.secondary)
        put("--color-border-disabled", c.onSurface.copy(alpha = DISABLED_FILL))

        put("--color-ring-primary", c.primary)
        put("--color-ring-secondary", c.secondary)
        put("--color-ring-inverse", c.inverseOnSurface)
        put("--color-ring-info", c.primary)
        put("--color-ring-danger", c.error)
        put("--color-ring-success", c.tertiary)
        put("--color-ring-warning", c.secondary)

        putAll(typographyAndShape)
    }

private const val DISABLED_FILL = 0.12f
private const val DISABLED_TEXT = 0.38f
private const val GHOST_TEXT = 0.6f

private val typographyAndShape: Map<String, String> =
    mapOf(
        "--font-sans" to "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
        "--font-mono" to "ui-monospace, SFMono-Regular, Menlo, monospace",
        "--font-weight-normal" to "400",
        "--font-weight-medium" to "500",
        "--font-weight-semibold" to "600",
        "--font-weight-bold" to "700",
        "--font-text-xs-size" to "12px",
        "--font-text-xs-line-height" to "16px",
        "--font-text-sm-size" to "14px",
        "--font-text-sm-line-height" to "20px",
        "--font-text-md-size" to "16px",
        "--font-text-md-line-height" to "24px",
        "--font-text-lg-size" to "18px",
        "--font-text-lg-line-height" to "28px",
        "--font-heading-xs-size" to "16px",
        "--font-heading-xs-line-height" to "24px",
        "--font-heading-sm-size" to "18px",
        "--font-heading-sm-line-height" to "26px",
        "--font-heading-md-size" to "20px",
        "--font-heading-md-line-height" to "28px",
        "--font-heading-lg-size" to "24px",
        "--font-heading-lg-line-height" to "32px",
        "--font-heading-xl-size" to "28px",
        "--font-heading-xl-line-height" to "36px",
        "--font-heading-2xl-size" to "32px",
        "--font-heading-2xl-line-height" to "40px",
        "--font-heading-3xl-size" to "36px",
        "--font-heading-3xl-line-height" to "44px",
        "--border-radius-xs" to "4px",
        "--border-radius-sm" to "8px",
        "--border-radius-md" to "12px",
        "--border-radius-lg" to "16px",
        "--border-radius-xl" to "24px",
        "--border-radius-full" to "9999px",
        "--border-width-regular" to "1px",
        "--shadow-hairline" to "0 0 0 1px rgba(0, 0, 0, 0.08)",
        "--shadow-sm" to "0 1px 2px rgba(0, 0, 0, 0.12)",
        "--shadow-md" to "0 4px 8px rgba(0, 0, 0, 0.14)",
        "--shadow-lg" to "0 8px 24px rgba(0, 0, 0, 0.18)",
    )

internal fun Color.toCss(): String {
    fun channel(value: Float) = (value * MAX_CHANNEL).roundToInt().coerceIn(0, MAX_CHANNEL)
    val (r, g, b) = Triple(channel(red), channel(green), channel(blue))
    return if (alpha >= 1f) {
        "#" + listOf(r, g, b).joinToString("") { it.toString(HEX_RADIX).padStart(2, '0') }
    } else {
        "rgba($r, $g, $b, ${(alpha * ALPHA_SCALE).roundToInt() / ALPHA_SCALE})"
    }
}

private const val MAX_CHANNEL = 255
private const val HEX_RADIX = 16
private const val ALPHA_SCALE = 100f
