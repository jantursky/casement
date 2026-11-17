package com.jantursky.casement.compose

import com.jantursky.casement.bridge.BlockedAction
import com.jantursky.casement.model.UiPermissions

/** Everything a platform renderer needs to show one app. */
internal class WebViewSpec(
    val html: String,
    /** The `Content-Security-Policy` value the document must be served with. */
    val csp: String,
    /** Origins the CSP allows beyond the document itself. Renderers also block anything else on the network. */
    val allowedOrigins: List<String>,
    val permissions: UiPermissions,
    val channel: AppChannel,
    val onBlocked: (BlockedAction) -> Unit,
    /** The page cannot be shown: the platform lacks a needed feature, or its renderer process died. */
    val onFailed: (RendererFailure) -> Unit,
)

internal enum class RendererFailure {
    /** The web view lacks a feature Casement needs for a safe sandbox. */
    Unsupported,

    /** The web content process died and the view has to be rebuilt. */
    ProcessGone,
}
