package dev.janakhpon.monocr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM: Compose Color and ColorScheme are ordinary Kotlin. */
class ThemeTest {

    /** Every role, read through its public getter, so a new role cannot be missed. */
    private fun roles(scheme: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
            .associate { it.name.removePrefix("get").substringBefore('-') to Color((it.invoke(scheme) as Long).toULong()) }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertNoBaselineRole(name: String, ours: ColorScheme, baseline: ColorScheme) {
        val mine = roles(ours)
        val defaults = roles(baseline)
        assertTrue("found no colour roles to check", mine.size >= 30)
        // A role equal to the baseline is either left unset or a deliberate white or
        // black, which happens to match (onPrimary is white in both). The baseline's own
        // roles are tinted lavender, so a tinted match is a role that was never set.
        val leftAtDefault = mine.filter { (role, value) ->
            defaults[role] == value && !(value.red == value.green && value.green == value.blue)
        }.keys
        assertTrue("$name leaves these roles at the Material baseline: $leftAtDefault", leftAtDefault.isEmpty())
    }

    @Test fun `light scheme sets every Material 3 role`() =
        assertNoBaselineRole("MonLightScheme", MonLightScheme, lightColorScheme())

    @Test fun `dark scheme sets every Material 3 role`() =
        assertNoBaselineRole("MonDarkScheme", MonDarkScheme, darkColorScheme())

    @Test fun `secondary text and borders meet WCAG contrast in both schemes`() {
        for ((name, s) in listOf("light" to MonLightScheme, "dark" to MonDarkScheme)) {
            for (bg in listOf(s.surface, s.background)) {
                val text = contrast(s.onSurfaceVariant, bg)
                assertTrue("$name onSurfaceVariant is $text:1, needs 4.5", text >= 4.5)
                val border = contrast(s.outline, bg)
                assertTrue("$name outline is $border:1, needs 3", border >= 3.0)
            }
            val chip = contrast(s.onSecondaryContainer, s.secondaryContainer)
            assertTrue("$name selected chip text is $chip:1", chip >= 4.5)
        }
    }
}
