package com.jantursky.casement.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Light or dark appearance. */
public enum class Theme(
    public val wire: String,
) {
    Light("light"),
    Dark("dark"),
}

/** How the app is shown. */
public enum class DisplayMode(
    public val wire: String,
) {
    Inline("inline"),
    Fullscreen("fullscreen"),
    Pip("pip"),
    ;

    public companion object {
        /** The mode for a wire value, or null for a value the spec does not define. */
        public fun fromWire(value: String?): DisplayMode? = entries.firstOrNull { it.wire == value }
    }
}

/** The kind of device the host runs on. */
public enum class Platform(
    public val wire: String,
) {
    Web("web"),
    Desktop("desktop"),
    Mobile("mobile"),
}

/** Space the app may use. A null side is unbounded. [fixed] sides are filled by the app, others are maximums. */
public data class ContainerDimensions(
    val height: Int? = null,
    val width: Int? = null,
    val fixedHeight: Boolean = false,
    val fixedWidth: Boolean = false,
)

/** Areas of the screen the app should keep clear, in CSS pixels. */
public data class SafeAreaInsets(
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
)

/**
 * What the host tells the app about its surroundings. Everything is optional, as in the spec.
 *
 * [styleVariables] maps spec CSS variable names (such as `--color-background-primary`) to CSS values.
 * [extras] holds extra keys the spec allows. The Compose module puts `prefersReducedMotion`, `fontScale` and
 * `textDirection` there. Those three are Casement conventions, not part of the spec.
 */
public data class HostContext(
    val theme: Theme? = null,
    val styleVariables: Map<String, String> = emptyMap(),
    val fontCss: String? = null,
    val displayMode: DisplayMode? = DisplayMode.Inline,
    val availableDisplayModes: List<DisplayMode> = listOf(DisplayMode.Inline),
    val containerDimensions: ContainerDimensions? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val userAgent: String? = null,
    val platform: Platform? = null,
    val touch: Boolean? = null,
    val hover: Boolean? = null,
    val safeAreaInsets: SafeAreaInsets? = null,
    val toolInfo: ToolWithUi? = null,
    val extras: Map<String, JsonElement> = emptyMap(),
) {
    /** The `hostContext` object as the spec defines it. */
    public fun toJson(): JsonObject =
        buildJsonObject {
            toolInfo?.let { info ->
                putJsonObject("toolInfo") { put("tool", info.definitionForApp()) }
            }
            theme?.let { put("theme", it.wire) }
            if (styleVariables.isNotEmpty() || fontCss != null) {
                putJsonObject("styles") {
                    if (styleVariables.isNotEmpty()) {
                        putJsonObject("variables") { styleVariables.forEach { (k, v) -> put(k, v) } }
                    }
                    fontCss?.let { putJsonObject("css") { put("fonts", it) } }
                }
            }
            displayMode?.let { put("displayMode", it.wire) }
            putJsonArray("availableDisplayModes") { availableDisplayModes.forEach { add(JsonPrimitive(it.wire)) } }
            containerDimensions?.let { put("containerDimensions", it.toJson()) }
            locale?.let { put("locale", it) }
            timeZone?.let { put("timeZone", it) }
            userAgent?.let { put("userAgent", it) }
            platform?.let { put("platform", it.wire) }
            if (touch != null || hover != null) {
                putJsonObject("deviceCapabilities") {
                    touch?.let { put("touch", it) }
                    hover?.let { put("hover", it) }
                }
            }
            safeAreaInsets?.let { insets ->
                putJsonObject("safeAreaInsets") {
                    put("top", insets.top)
                    put("right", insets.right)
                    put("bottom", insets.bottom)
                    put("left", insets.left)
                }
            }
            extras.forEach { (key, value) -> put(key, value) }
        }

    /** The fields of this context that differ from [previous], for a `host-context-changed` notification. */
    public fun changesSince(previous: HostContext): JsonObject {
        val before = previous.toJson()
        return JsonObject(toJson().filter { (key, value) -> before[key] != value })
    }
}

private fun ContainerDimensions.toJson(): JsonObject =
    buildJsonObject {
        height?.let { put(if (fixedHeight) "height" else "maxHeight", it) }
        width?.let { put(if (fixedWidth) "width" else "maxWidth", it) }
    }

/** The app SDK rejects a tool without `name` and `inputSchema`, so fill in what the definition left out. */
private fun ToolWithUi.definitionForApp(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("inputSchema", buildJsonObject { put("type", "object") })
        definition.forEach { (key, value) -> put(key, value) }
    }
