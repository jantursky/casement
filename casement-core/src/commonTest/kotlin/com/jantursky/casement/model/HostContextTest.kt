package com.jantursky.casement.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HostContextTest {
    private val tool = JsonObject(mapOf("name" to JsonPrimitive("t")))

    @Test
    fun `serializes every field the spec defines`() {
        val json =
            HostContext(
                theme = Theme.Dark,
                styleVariables = mapOf("--color-background-primary" to "#000"),
                fontCss = "@font-face{}",
                displayMode = DisplayMode.Inline,
                availableDisplayModes = listOf(DisplayMode.Inline, DisplayMode.Fullscreen),
                containerDimensions = ContainerDimensions(height = 400, width = 300, fixedWidth = true),
                locale = "sk-SK",
                timeZone = "Europe/Bratislava",
                userAgent = "casement/1",
                platform = Platform.Mobile,
                touch = true,
                hover = false,
                safeAreaInsets = SafeAreaInsets(top = 1, right = 2, bottom = 3, left = 4),
                toolInfo = ToolWithUi("t", ToolUi("ui://t"), tool),
                extras = mapOf("fontScale" to JsonPrimitive(1.5)),
            ).toJson()
        assertEquals("dark", json["theme"]?.jsonPrimitive?.content)
        assertEquals(
            "#000",
            json["styles"]!!
                .jsonObject["variables"]!!
                .jsonObject["--color-background-primary"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "@font-face{}",
            json["styles"]!!
                .jsonObject["css"]!!
                .jsonObject["fonts"]!!
                .jsonPrimitive.content,
        )
        assertEquals(2, json["availableDisplayModes"]!!.jsonArray.size)
        val dims = json["containerDimensions"]!!.jsonObject
        assertEquals(400, dims["maxHeight"]!!.jsonPrimitive.content.toInt())
        assertEquals(300, dims["width"]!!.jsonPrimitive.content.toInt())
        assertEquals("mobile", json["platform"]!!.jsonPrimitive.content)
        assertEquals("true", json["deviceCapabilities"]!!.jsonObject["touch"]!!.jsonPrimitive.content)
        assertEquals(
            4,
            json["safeAreaInsets"]!!
                .jsonObject["left"]!!
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(tool, json["toolInfo"]!!.jsonObject["tool"])
        assertEquals("1.5", json["fontScale"]!!.jsonPrimitive.content)
        assertEquals("sk-SK", json["locale"]!!.jsonPrimitive.content)
        assertEquals("Europe/Bratislava", json["timeZone"]!!.jsonPrimitive.content)
        assertEquals("casement/1", json["userAgent"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty context only lists the available display modes`() {
        val json = HostContext(displayMode = null).toJson()
        assertEquals(setOf("availableDisplayModes"), json.keys)
        assertFalse(Json.encodeToString(JsonObject.serializer(), json).contains("styles"))
    }

    @Test
    fun `changes since lists only the fields that differ`() {
        val before = HostContext(theme = Theme.Light, locale = "en")
        val after = before.copy(theme = Theme.Dark)
        assertEquals(setOf("theme"), after.changesSince(before).keys)
        assertEquals(emptySet(), after.changesSince(after).keys)
    }

    @Test
    fun `container dimensions mark fixed and maximum sides`() {
        val max = HostContext(containerDimensions = ContainerDimensions(height = 10, width = 20)).toJson()
        assertEquals(setOf("maxHeight", "maxWidth"), max["containerDimensions"]!!.jsonObject.keys)
        val fixed = HostContext(containerDimensions = ContainerDimensions(height = 10, fixedHeight = true)).toJson()
        assertEquals(setOf("height"), fixed["containerDimensions"]!!.jsonObject.keys)
    }
}
