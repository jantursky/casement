package com.jantursky.casement.jsonrpc

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class JsonRpcCodecTest {
    private fun invalid(text: String): JsonRpcFormatException = assertFailsWith { JsonRpcCodec.decode(text) }

    @Test
    fun `decodes a request with a string or number id`() {
        val a = JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":"a","method":"ping"}""")
        val b = JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":7,"method":"x","params":{"k":1}}""")
        assertEquals(JsonRpcRequest(JsonPrimitive("a"), "ping"), a)
        assertEquals(JsonPrimitive(7), assertIs<JsonRpcRequest>(b).id)
        assertEquals(1, assertIs<JsonRpcRequest>(b).params?.size)
    }

    @Test
    fun `a message without an id is a notification`() {
        val n = JsonRpcCodec.decode("""{"jsonrpc":"2.0","method":"n","params":null}""")
        assertEquals(JsonRpcNotification("n", null), n)
    }

    @Test
    fun `decodes results and errors`() {
        val ok = JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":1,"result":{"a":true}}""")
        assertEquals(1, assertIs<JsonRpcResponse>(ok).result.size)
        val empty = JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":1,"result":null}""")
        assertEquals(JsonObject(emptyMap()), assertIs<JsonRpcResponse>(empty).result)
        val err = JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"no","data":1}}""")
        val parsed = assertIs<JsonRpcErrorResponse>(err)
        assertNull(parsed.id)
        assertEquals(-32000, parsed.error.code)
        assertEquals(JsonPrimitive(1), parsed.error.data)
    }

    @Test
    fun `rejects text that is not JSON`() {
        assertEquals(JsonRpcErrorCode.PARSE_ERROR, invalid("{nope").code)
    }

    @Test
    fun `rejects malformed messages`() {
        listOf(
            "[]",
            """{"id":1,"method":"x"}""",
            """{"jsonrpc":"1.0","method":"x"}""",
            """{"jsonrpc":"2.0"}""",
            """{"jsonrpc":"2.0","method":5}""",
            """{"jsonrpc":"2.0","method":"x","params":[1]}""",
            """{"jsonrpc":"2.0","id":true,"method":"x"}""",
            """{"jsonrpc":"2.0","id":null,"method":"x"}""",
            """{"jsonrpc":"2.0","result":{}}""",
            """{"jsonrpc":"2.0","id":1,"result":3}""",
            """{"jsonrpc":"2.0","id":1,"error":3}""",
            """{"jsonrpc":"2.0","id":1,"error":{"code":"x","message":"m"}}""",
            """{"jsonrpc":"2.0","id":1,"error":{"code":1}}""",
        ).forEach { assertEquals(JsonRpcErrorCode.INVALID_REQUEST, invalid(it).code, it) }
    }

    @Test
    fun `encode and decode round trip every kind of message`() {
        val params = buildJsonObject { put("a", 1) }
        val messages =
            listOf(
                JsonRpcRequest(JsonPrimitive("1"), "m", params),
                JsonRpcRequest(JsonPrimitive(2), "m"),
                JsonRpcNotification("n", params),
                JsonRpcNotification("n"),
                JsonRpcResponse(JsonPrimitive(3), params),
                JsonRpcErrorResponse(JsonPrimitive(4), JsonRpcError(-1, "bad", JsonPrimitive("d"))),
                JsonRpcErrorResponse(null, JsonRpcError(-2, "worse")),
            )
        messages.forEach { assertEquals(it, JsonRpcCodec.decode(JsonRpcCodec.encode(it))) }
        assertIs<JsonNull>(
            JsonRpcCodec.encode(JsonRpcErrorResponse(null, JsonRpcError(-2, "x"))).let {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(it)
                    .let { e -> (e as JsonObject)["id"] }
            },
        )
    }
}
