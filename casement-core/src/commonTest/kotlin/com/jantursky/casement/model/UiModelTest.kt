package com.jantursky.casement.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

class UiModelTest {
    @Test
    fun `tool ui defaults to model and app visibility`() {
        val ui = ToolUi.fromToolMeta(obj("""{"ui":{"resourceUri":"ui://x/y"}}"""))
        assertEquals("ui://x/y", ui?.resourceUri)
        assertEquals(setOf(ToolVisibility.Model, ToolVisibility.App), ui?.visibility)
        assertTrue(ui!!.appCallable)
    }

    @Test
    fun `tool ui reads visibility and ignores unknown values`() {
        val ui = ToolUi.fromToolMeta(obj("""{"ui":{"resourceUri":"ui://a","visibility":["app","other"]}}"""))
        assertEquals(setOf(ToolVisibility.App), ui?.visibility)
        val modelOnly = ToolUi.fromToolMeta(obj("""{"ui":{"resourceUri":"ui://a","visibility":["model"]}}"""))
        assertFalse(modelOnly!!.appCallable)
    }

    @Test
    fun `tool ui falls back to the deprecated flat key`() {
        assertEquals("ui://old", ToolUi.fromToolMeta(obj("""{"ui/resourceUri":"ui://old"}"""))?.resourceUri)
    }

    @Test
    fun `tool ui rejects other schemes and missing metadata`() {
        assertNull(ToolUi.fromToolMeta(null))
        assertNull(ToolUi.fromToolMeta(obj("""{"ui":{"resourceUri":"https://evil"}}""")))
        assertNull(ToolUi.fromToolMeta(obj("""{"ui":{"resourceUri":5}}""")))
        assertNull(ToolUi.fromToolMeta(obj("""{"other":1}""")))
    }

    @Test
    fun `resource meta parses csp permissions domain and border`() {
        val meta =
            UiResourceMeta.fromResourceMeta(
                obj(
                    """{"ui":{"csp":{"connectDomains":["https://a.com"],"resourceDomains":["https://cdn.com",3],
                    "frameDomains":["https://f.com"],"baseUriDomains":["https://b.com"]},
                    "permissions":{"camera":{},"geolocation":{}},"domain":"d.example","prefersBorder":true}}""",
                ),
            )
        assertEquals(listOf("https://a.com"), meta.csp?.connectDomains)
        assertEquals(listOf("https://cdn.com"), meta.csp?.resourceDomains)
        assertEquals(listOf("https://f.com"), meta.csp?.frameDomains)
        assertEquals(listOf("https://b.com"), meta.csp?.baseUriDomains)
        assertEquals(UiPermissions(camera = true, geolocation = true), meta.permissions)
        assertEquals("d.example", meta.domain)
        assertEquals(true, meta.prefersBorder)
    }

    @Test
    fun `resource meta without ui keeps the defaults`() {
        assertEquals(UiResourceMeta(), UiResourceMeta.fromResourceMeta(null))
        assertEquals(UiResourceMeta(), UiResourceMeta.fromResourceMeta(obj("""{"x":1}""")))
        val partial = UiResourceMeta.fromResourceMeta(obj("""{"ui":{"csp":{}}}"""))
        assertEquals(UiCsp(), partial.csp)
        assertTrue(partial.permissions.isEmpty)
    }

    @Test
    fun `permissions intersect with what the host grants`() {
        val asked = UiPermissions(camera = true, microphone = true, clipboardWrite = true)
        val granted = UiPermissions(microphone = true, geolocation = true)
        assertEquals(UiPermissions(microphone = true), asked intersect granted)
        assertTrue((asked intersect UiPermissions.None).isEmpty)
    }

    @Test
    fun `display mode parses wire values`() {
        assertEquals(DisplayMode.Pip, DisplayMode.fromWire("pip"))
        assertNull(DisplayMode.fromWire("sideways"))
        assertNull(DisplayMode.fromWire(null))
    }
}
