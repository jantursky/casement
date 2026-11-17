package com.jantursky.casement.compose.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class InjectScriptTest {
    @Test
    fun `the script goes right after the doctype`() {
        assertEquals("<!doctype html><script>x</script><p>a</p>", injectScript("<!doctype html><p>a</p>", "x"))
    }

    @Test
    fun `leading comments before the doctype are kept in front of it`() {
        assertEquals(
            "<!-- c --><!DOCTYPE html><script>x</script><b></b>",
            injectScript("<!-- c --><!DOCTYPE html><b></b>", "x"),
        )
    }

    @Test
    fun `without a doctype the script goes first`() {
        assertEquals("<script>x</script><p>a</p>", injectScript("<p>a</p>", "x"))
    }
}
