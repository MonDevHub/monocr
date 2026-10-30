package dev.janakhpon.monocr.ui

import android.net.Uri
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertCountEquals
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.data.HistoryRecord
import dev.janakhpon.monocr.ui.components.HistoryItem
import dev.janakhpon.monocr.ui.components.OcrErrorView
import dev.janakhpon.monocr.ui.components.PickerView
import dev.janakhpon.monocr.ui.screens.FeedbackContent
import dev.janakhpon.monocr.ui.screens.ResultView
import dev.janakhpon.monocr.ui.theme.MonOCRTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { MonOCRTheme(darkTheme = false) { content() } }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    private val politeLiveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test fun `the feedback consent row is one labelled toggle that enables submit`() {
        show {
            FeedbackContent(
                feedbackHistory = emptyList(), originalText = "read text", sourceUriDefault = null,
                onMenuClick = {}, onCancel = {}, onSubmit = { _, _, _, _ -> }, onDelete = {}, onClearAll = {}
            )
        }
        compose.onNodeWithText(str(R.string.feedback_submit)).assertIsNotEnabled()
        compose.onNode(isToggleable() and hasText(str(R.string.feedback_consent_title)))
            .assert(hasRole(Role.Checkbox))
            .performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.feedback_submit)).assertIsEnabled()
    }

    @Test fun `screens expose headings`() {
        show {
            FeedbackContent(
                feedbackHistory = emptyList(), originalText = "", sourceUriDefault = null,
                onMenuClick = {}, onCancel = {}, onSubmit = { _, _, _, _ -> }, onDelete = {}, onClearAll = {}
            )
        }
        val headings = compose.onAllNodes(isHeading()).fetchSemanticsNodes()
        assertTrue("expected the title and section headers, found ${headings.size}", headings.size >= 3)
    }

    @Test fun `the gallery card is a button`() {
        show { PickerView(onGallery = {}, onPdf = {}, onCamera = {}) }
        compose.onNodeWithText(str(R.string.select_image))
            .assertHasClickAction()
            .assert(hasRole(Role.Button))
    }

    @Test fun `a finished scan is announced`() {
        show { ResultView(imageUri = Uri.parse("content://test/i"), result = FakePipeline.result("text")) }
        compose.onNodeWithText(str(R.string.extracted_text)).assert(politeLiveRegion)
    }

    @Test fun `a failed scan is announced`() {
        show { OcrErrorView(Uri.parse("content://test/i"), OcrFailure.UnreadableImage, onReset = {}, onViewDocs = {}) }
        compose.onNodeWithText(str(R.string.error_corrupt)).assert(politeLiveRegion)
    }

    @Test fun `a history row reads its sync state`() {
        val record = HistoryRecord(fileName = "page.jpg", fileType = "image/jpeg", text = "t",
            processingTime = 12, category = "feedback", isSynced = false)
        show { HistoryItem(record = record, onClick = {}, onDelete = {}) }
        compose.onNodeWithText("page.jpg").assert(hasText(str(R.string.status_pending)))
        compose.onAllNodesWithContentDescription("page.jpg", substring = true).assertCountEquals(0)
    }
}
