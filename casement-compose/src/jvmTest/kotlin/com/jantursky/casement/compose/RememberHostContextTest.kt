package com.jantursky.casement.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import com.jantursky.casement.model.HostContext
import com.jantursky.casement.model.Platform
import com.jantursky.casement.model.Theme
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RememberHostContextTest {
    private fun capture(
        dark: Boolean,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ): HostContext {
        var captured: HostContext? = null
        runComposeUiTest {
            setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        captured = rememberHostContext()
                    }
                }
            }
        }
        return checkNotNull(captured)
    }

    @Test
    fun `the theme follows the material color scheme`() {
        assertEquals(Theme.Light, capture(dark = false).theme)
        assertEquals(Theme.Dark, capture(dark = true).theme)
    }

    @Test
    fun `style variables locale platform and extras are filled in`() {
        val context = capture(dark = false)
        assertNotNull(context.styleVariables["--color-background-primary"])
        assertNotNull(context.locale)
        assertNotNull(context.timeZone)
        assertEquals(Platform.Desktop, context.platform)
        assertEquals("false", context.extras["prefersReducedMotion"]?.jsonPrimitive?.content)
        assertNotNull(context.extras["fontScale"])
    }

    @Test
    fun `text direction is passed on`() {
        assertEquals("ltr", capture(dark = false).extras["textDirection"]?.jsonPrimitive?.content)
        assertEquals(
            "rtl",
            capture(dark = false, direction = LayoutDirection.Rtl).extras["textDirection"]?.jsonPrimitive?.content,
        )
    }
}
