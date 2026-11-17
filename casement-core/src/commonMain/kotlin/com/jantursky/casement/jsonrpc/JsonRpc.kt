package com.jantursky.casement.jsonrpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** A JSON-RPC 2.0 message exchanged between the host and an app. */
public sealed interface JsonRpcMessage

/** A call that expects a response with the same [id]. */
public data class JsonRpcRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonObject? = null,
) : JsonRpcMessage

/** A one-way message without an id. */
public data class JsonRpcNotification(
    val method: String,
    val params: JsonObject? = null,
) : JsonRpcMessage

/** The successful answer to a request. */
public data class JsonRpcResponse(
    val id: JsonElement,
    val result: JsonObject,
) : JsonRpcMessage

/** The failed answer to a request. [id] is null when the request id could not be read. */
public data class JsonRpcErrorResponse(
    val id: JsonElement?,
    val error: JsonRpcError,
) : JsonRpcMessage

/** The error object of a failed response. */
public data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/** Standard JSON-RPC error codes. */
public object JsonRpcErrorCode {
    public const val PARSE_ERROR: Int = -32700
    public const val INVALID_REQUEST: Int = -32600
    public const val METHOD_NOT_FOUND: Int = -32601
    public const val INVALID_PARAMS: Int = -32602
    public const val INTERNAL_ERROR: Int = -32603

    /** The host refused the call, for example a tool the app may not use. */
    public const val REJECTED: Int = -32000
}

/** Thrown by [JsonRpcCodec.decode] when text is not a valid JSON-RPC 2.0 message. */
public class JsonRpcFormatException(
    public val code: Int,
    message: String,
) : IllegalArgumentException(message)

/** Reads and writes JSON-RPC 2.0 messages. */
public object JsonRpcCodec {
    private const val VERSION: String = "2.0"
    private val json = Json

    /** Parses [text]. Throws [JsonRpcFormatException] for anything that is not a well-formed message. */
    public fun decode(text: String): JsonRpcMessage {
        val root =
            try {
                json.parseToJsonElement(text)
            } catch (e: IllegalArgumentException) {
                throw JsonRpcFormatException(JsonRpcErrorCode.PARSE_ERROR, "Invalid JSON: ${e.message}")
            }
        val obj = root as? JsonObject ?: invalid("A message must be a JSON object")
        if (obj["jsonrpc"]?.stringOrNull() != VERSION) invalid("jsonrpc must be \"2.0\"")

        val method = obj["method"]
        val id = obj["id"]
        return when {
            method != null -> decodeCall(obj, method, id)
            obj.containsKey("result") -> decodeResult(obj, id)
            obj.containsKey("error") -> decodeError(obj, id)
            else -> invalid("A message needs method, result or error")
        }
    }

    /** Serializes [message] to a single line of JSON. */
    public fun encode(message: JsonRpcMessage): String = json.encodeToString(JsonObject.serializer(), toJson(message))

    private fun decodeCall(
        obj: JsonObject,
        method: JsonElement,
        id: JsonElement?,
    ): JsonRpcMessage {
        val name = method.stringOrNull() ?: invalid("method must be a string")
        val params =
            when (val raw = obj["params"]) {
                null, JsonNull -> null
                is JsonObject -> raw
                else -> invalid("params must be an object")
            }
        return if (id == null) {
            JsonRpcNotification(name, params)
        } else {
            JsonRpcRequest(validId(id), name, params)
        }
    }

    private fun decodeResult(
        obj: JsonObject,
        id: JsonElement?,
    ): JsonRpcMessage {
        val result = obj["result"]
        val body = if (result == JsonNull) JsonObject(emptyMap()) else result as? JsonObject
        return JsonRpcResponse(
            validId(id ?: invalid("A response needs an id")),
            body ?: invalid("result must be an object"),
        )
    }

    private fun decodeError(
        obj: JsonObject,
        id: JsonElement?,
    ): JsonRpcMessage {
        val error = obj["error"] as? JsonObject ?: invalid("error must be an object")
        val code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: invalid("error.code must be an integer")
        val message = error["message"]?.stringOrNull() ?: invalid("error.message must be a string")
        val responseId = if (id == null || id == JsonNull) null else validId(id)
        return JsonRpcErrorResponse(responseId, JsonRpcError(code, message, error["data"]))
    }

    private fun toJson(message: JsonRpcMessage): JsonObject =
        buildJsonObject {
            put("jsonrpc", VERSION)
            when (message) {
                is JsonRpcRequest -> {
                    put("id", message.id)
                    put("method", message.method)
                    message.params?.let { put("params", it) }
                }

                is JsonRpcNotification -> {
                    put("method", message.method)
                    message.params?.let { put("params", it) }
                }

                is JsonRpcResponse -> {
                    put("id", message.id)
                    put("result", message.result)
                }

                is JsonRpcErrorResponse -> {
                    put("id", message.id ?: JsonNull)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", message.error.code)
                            put("message", message.error.message)
                            message.error.data?.let { put("data", it) }
                        },
                    )
                }
            }
        }

    private fun validId(id: JsonElement): JsonElement {
        val primitive = id as? JsonPrimitive
        if (primitive == null || (!primitive.isString && primitive.doubleOrNull == null)) {
            invalid("id must be a string or a number")
        }
        return primitive
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun invalid(message: String): Nothing =
        throw JsonRpcFormatException(JsonRpcErrorCode.INVALID_REQUEST, message)
}
