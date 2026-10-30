package dev.janakhpon.monocr.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.ui.screens.ContributeContent
import dev.janakhpon.monocr.ui.theme.MonOCRTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A Contribute submission is uploaded by SyncWorker, so it must not be possible
 * without the consent the privacy policy promises.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentGateTest {
    @get:Rule val compose = createComposeRule()

    private val submitted = mutableListOf<String>()

    private fun showContribute() = compose.setContent {
        MonOCRTheme(darkTheme = false) {
            ContributeContent(
                contributionHistory = emptyList(),
                initialText = "typed text",
                onMenuClick = {},
                onSubmit = { _, text, _: Uri? -> submitted += text },
                onDelete = {},
                onClearAll = {}
            )
        }
    }

    private fun consentRow() =
        compose.onNode(isToggleable() and hasText(str(R.string.feedback_consent_title)))

    @Test fun `contribute submit stays disabled until consent is ticked`() {
        showContribute()
        compose.onNodeWithText(str(R.string.contribute_submit)).assertIsNotEnabled()

        consentRow().performScrollTo().assertIsOff().performClick()

        consentRow().assertIsOn()
        compose.onNodeWithText(str(R.string.contribute_submit)).assertIsEnabled()
    }

    @Test fun `contribute submits once consented and asks again for the next one`() {
        showContribute()
        consentRow().performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.contribute_submit)).performScrollTo().performClick()

        assertEquals(listOf("typed text"), submitted)
        consentRow().assertIsOff()
    }
}
