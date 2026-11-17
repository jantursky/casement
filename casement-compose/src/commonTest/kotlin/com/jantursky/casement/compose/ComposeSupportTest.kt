package com.jantursky.casement.compose

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeSupportTest {
    @Test
    fun `opaque colors become hex and translucent ones rgba`() {
        assertEquals("#ff0000", Color(1f, 0f, 0f).toCss())
        assertEquals("#000000", Color.Black.toCss())
        assertEquals("#ffffff", Color.White.toCss())
        assertEquals("rgba(0, 0, 0, 0.5)", Color.Black.copy(alpha = 0.5f).toCss())
        assertEquals("rgba(0, 0, 0, 0)", Color.Transparent.toCss())
    }

    @Test
    fun `a material color scheme maps to the spec variables`() {
        val light = materialStyleVariables(lightColorScheme())
        val dark = materialStyleVariables(darkColorScheme())
        listOf(
            "--color-background-primary",
            "--color-text-primary",
            "--color-border-primary",
            "--color-ring-primary",
            "--font-sans",
            "--border-radius-md",
            "--shadow-sm",
        ).forEach { assertTrue(it in light, it) }
        assertTrue(light["--color-background-primary"] != dark["--color-background-primary"])
        assertContains(light["--color-text-disabled"]!!, "rgba")
    }

    @Test
    fun `the shim wires the platform snippets in`() {
        val shim = bridgeShim(send = "function (s) { native.post(s); }", receive = "native.onmessage = deliver;")
        assertContains(shim, "var send = function (s) { native.post(s); };")
        assertContains(shim, "native.onmessage = deliver;")
        assertContains(shim, "message.jsonrpc === '2.0'")
        assertContains(shim, "securitypolicyviolation")
        assertContains(shim, "casement: 'ready'")
    }

    @Test
    fun `the channel passes messages both ways and drops them when detached`() {
        val channel = AppChannel()
        val fromApp = mutableListOf<String>()
        val toApp = mutableListOf<String>()
        channel.onFromApp = { fromApp += it }
        channel.toApp("lost")
        channel.attach { toApp += it }
        channel.toApp("one")
        channel.fromApp("two")
        channel.detach()
        channel.toApp("three")
        assertEquals(listOf("one"), toApp)
        assertEquals(listOf("two"), fromApp)
    }
}
