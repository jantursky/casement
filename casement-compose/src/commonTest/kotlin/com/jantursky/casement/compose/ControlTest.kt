package com.jantursky.casement.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class ControlTest {
    @Test
    fun `a violation names what was blocked and the directive`() {
        assertEquals(
            "The browser blocked https://x.example/a.png (img-src)",
            cspDetail("""{"casement":"csp","directive":"img-src","blocked":"https://x.example/a.png"}"""),
        )
    }

    @Test
    fun `an empty blocked uri still names the directive`() {
        assertEquals(
            "The browser blocked something (script-src)",
            cspDetail("""{"casement":"csp","directive":"script-src","blocked":""}"""),
        )
    }

    @Test
    fun `a message without details falls back to the generic text`() {
        assertEquals("The browser blocked a CSP violation", cspDetail("""{"casement":"csp"}"""))
        assertEquals("The browser blocked a CSP violation", cspDetail("not json"))
    }
}
