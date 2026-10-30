package dev.janakhpon.monocr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.ui.UiState
import dev.janakhpon.monocr.ui.components.EngineStatusChip
import dev.janakhpon.monocr.ui.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Native graphics, so text is measured with real fonts: legacy mode returns sizes that
// have nothing to do with the glyphs, and every overflow check would mean nothing.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class TypeScaleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Myanmar-script typography uses PyidaungSu with tall lines in every style`() {
        val t = MonTypography.forMyanmarScript()
        for (style in listOf(t.bodyMedium, t.bodySmall, t.labelSmall, t.labelLarge, t.titleLarge, t.headlineMedium)) {
            assertEquals(PyidaungSuFamily, style.fontFamily)
            assertTrue("${style.lineHeight} for ${style.fontSize}", style.lineHeight.value >= 1.6f * style.fontSize.value)
        }
        assertTrue(usesMyanmarScript("my") && usesMyanmarScript("mnw"))
        assertFalse(usesMyanmarScript("en"))
    }

    @Test @Config(qualifiers = "my")
    fun `the theme switches to Myanmar-script typography in Burmese`() {
        var family: FontFamily? = null
        compose.setContent { MonOCRTheme(darkTheme = false) { family = MaterialTheme.typography.bodyMedium.fontFamily } }
        compose.runOnIdle { assertEquals(PyidaungSuFamily, family) }
    }

    @Test @Config(qualifiers = "my")
    fun `the engine status chip does not clip Burmese text at twice the font size`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MonOCRTheme(darkTheme = false) { EngineStatusChip(UiState.Ready) }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(str(R.string.engine_ready)).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val l = layouts.single()
        // Height is what a fixed-height box clips; a one-line cap shows up as an
        // ellipsis. Width is not checked: a single line's paragraph width is fractional
        // and can exceed its rounded box by under a pixel without clipping anything.
        assertFalse("the chip clips its text vertically: ${l.size}", l.didOverflowHeight)
        assertFalse("the chip cuts its text short", (0 until l.lineCount).any { l.isLineEllipsized(it) })
    }
}
