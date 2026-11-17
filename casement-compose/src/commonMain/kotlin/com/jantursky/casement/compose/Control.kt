package com.jantursky.casement.compose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the browser said about a CSP violation, from the shim's `{"casement":"csp",...}` message. */
internal fun cspDetail(text: String): String {
    val message = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    val directive = message?.get("directive")?.jsonPrimitive?.content
    val blocked = message?.get("blocked")?.jsonPrimitive?.content
    return when {
        directive != null && !blocked.isNullOrEmpty() -> "The browser blocked $blocked ($directive)"
        directive != null -> "The browser blocked something ($directive)"
        else -> "The browser blocked a CSP violation"
    }
}
