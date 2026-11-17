package com.jantursky.casement.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The MCP Apps specification version Casement implements. */
public const val SPEC_VERSION: String = "2026-01-26"

/** Extension id a client advertises under `capabilities.extensions`. */
public const val EXTENSION_ID: String = "io.modelcontextprotocol/ui"

/** MIME type of a `ui://` resource that holds an MCP App. */
public const val MCP_APP_MIME_TYPE: String = "text/html;profile=mcp-app"

/** Who may call a tool. A tool without `_meta.ui.visibility` is visible to both. */
public enum class ToolVisibility(
    public val wire: String,
) {
    /** The model can see and call the tool. */
    Model("model"),

    /** The app that belongs to the same server can call the tool. */
    App("app"),
}

/** The UI a tool declares in `_meta.ui`. */
public data class ToolUi(
    val resourceUri: String,
    val visibility: Set<ToolVisibility> = setOf(ToolVisibility.Model, ToolVisibility.App),
) {
    /** True when the app may call this tool. */
    public val appCallable: Boolean get() = ToolVisibility.App in visibility

    public companion object {
        /**
         * Reads the UI declaration from a tool's `_meta`. Returns null when the tool has none, or when the
         * resource URI is not a `ui://` URI. The deprecated flat `ui/resourceUri` key is used as a fallback.
         */
        public fun fromToolMeta(meta: JsonObject?): ToolUi? {
            val ui = meta?.get("ui") as? JsonObject
            val uri = ui?.string("resourceUri") ?: meta?.string("ui/resourceUri")
            if (uri == null || !uri.startsWith("ui://")) return null
            val visibility =
                (ui?.get("visibility") as? JsonArray)
                    ?.mapNotNull { element -> ToolVisibility.entries.firstOrNull { it.wire == element.stringOrNull() } }
                    ?.toSet()
                    ?: setOf(ToolVisibility.Model, ToolVisibility.App)
            return ToolUi(uri, visibility)
        }
    }
}

/** A tool with a UI, as the host knows it. [definition] is the tool's JSON, sent to the app as `toolInfo.tool`. */
public data class ToolWithUi(
    val name: String,
    val ui: ToolUi,
    val definition: JsonObject,
)

/** Domains an app asks to reach, from `_meta.ui.csp`. */
public data class UiCsp(
    val connectDomains: List<String> = emptyList(),
    val resourceDomains: List<String> = emptyList(),
    val frameDomains: List<String> = emptyList(),
    val baseUriDomains: List<String> = emptyList(),
) {
    public companion object {
        internal fun fromJson(obj: JsonObject): UiCsp =
            UiCsp(
                connectDomains = obj.strings("connectDomains"),
                resourceDomains = obj.strings("resourceDomains"),
                frameDomains = obj.strings("frameDomains"),
                baseUriDomains = obj.strings("baseUriDomains"),
            )
    }
}

/** Sensitive browser features. Used both for what an app asks for and for what a host grants. */
public data class UiPermissions(
    val camera: Boolean = false,
    val microphone: Boolean = false,
    val geolocation: Boolean = false,
    val clipboardWrite: Boolean = false,
) {
    /** True when nothing is requested or granted. */
    public val isEmpty: Boolean get() = !camera && !microphone && !geolocation && !clipboardWrite

    /** What both sides agree on: a permission survives only when [this] asks for it and [granted] allows it. */
    public infix fun intersect(granted: UiPermissions): UiPermissions =
        UiPermissions(
            camera = camera && granted.camera,
            microphone = microphone && granted.microphone,
            geolocation = geolocation && granted.geolocation,
            clipboardWrite = clipboardWrite && granted.clipboardWrite,
        )

    public companion object {
        /** No permissions. This is the default policy. */
        public val None: UiPermissions = UiPermissions()

        internal fun fromJson(obj: JsonObject): UiPermissions =
            UiPermissions(
                camera = obj["camera"] is JsonObject,
                microphone = obj["microphone"] is JsonObject,
                geolocation = obj["geolocation"] is JsonObject,
                clipboardWrite = obj["clipboardWrite"] is JsonObject,
            )
    }
}

/** What the server declares about a UI resource in `_meta.ui` of the resource content. */
public data class UiResourceMeta(
    val csp: UiCsp? = null,
    val permissions: UiPermissions = UiPermissions.None,
    val domain: String? = null,
    val prefersBorder: Boolean? = null,
) {
    public companion object {
        /** Reads `_meta.ui` from the `_meta` of a resource content item. Missing fields keep their defaults. */
        public fun fromResourceMeta(meta: JsonObject?): UiResourceMeta {
            val ui = meta?.get("ui") as? JsonObject ?: return UiResourceMeta()
            return UiResourceMeta(
                csp = (ui["csp"] as? JsonObject)?.let(UiCsp::fromJson),
                permissions = (ui["permissions"] as? JsonObject)?.let(UiPermissions::fromJson) ?: UiPermissions.None,
                domain = ui.string("domain"),
                prefersBorder = (ui["prefersBorder"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }
}

/** The HTML document of an MCP App together with what the server declared about it. */
public data class UiResource(
    val uri: String,
    val html: String,
    val meta: UiResourceMeta = UiResourceMeta(),
)

internal fun JsonObject.string(key: String): String? = get(key).stringOrNull()

internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.strings(key: String): List<String> =
    (get(key) as? JsonArray)?.mapNotNull { it.stringOrNull() } ?: emptyList()
