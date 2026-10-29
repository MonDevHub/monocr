package dev.janakhpon.monocr.ui

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.ui.components.LanguagePickerDialog
import dev.janakhpon.monocr.ui.theme.MonOCRTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalisationTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = "my")
    fun `the language picker marks the current language and has no flags`() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            MonOCRTheme(darkTheme = false) { LanguagePickerDialog(onDismiss = {}, onSelect = { chosen += it }) }
        }
        compose.onNodeWithText(str(R.string.lang_my)).assertIsSelected()
        compose.onNodeWithText(str(R.string.lang_en)).assertIsNotSelected()
        for (flag in listOf("🇺🇸", "🇲🇲")) {
            compose.onAllNodesWithText(flag, substring = true).assertCountEquals(0)
        }

        compose.onNodeWithText(str(R.string.lang_mnw)).performClick()
        assertEquals(listOf("mnw"), chosen)
    }

    @Test fun `privacy copy does not claim scans never leave the device`() {
        val s = File("src/main/res/values/strings.xml").readText()
        fun value(k: String) = Regex("""name="$k">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(s)!!.groupValues[1]
        assertTrue(value("privacy_s1_desc").contains("unless you submit them through Feedback or Contribute"))
        assertTrue(!value("docs_privacy_stat").contains("stay on your device"))
        assertTrue(value("docs_privacy_stat").contains("unless you submit"))
    }

    /**
     * English typed into a composable never reaches a Mon or Burmese user in their
     * language. This is the grep the audit ran, kept as a test so it stays at zero.
     */
    @Test fun `no user-facing English literal in the Compose sources`() {
        val ui = File("src/main/java/dev/janakhpon/monocr/ui")
        assertTrue("run from the app module: ${ui.absolutePath}", ui.isDirectory)
        val literal = Regex("""(Text\(\s*"|contentDescription = "|onClickLabel = "|title = "|description = ")([A-Za-z©][^"]*)"""")
        // The product name and the licence notice are not translated.
        val allowed = setOf("MonOCR", "MIT License · © 2026 Janakhpon", "© 2026 Janakhpon · MIT License")
        val found = ui.walk().filter { it.extension == "kt" }.flatMap { file ->
            literal.findAll(file.readText())
                .map { it.groupValues[2] }
                .filter { it !in allowed }
                .map { "${file.name}: \"$it\"" }
        }.toList()
        assertTrue("hard-coded English in UI sources:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
