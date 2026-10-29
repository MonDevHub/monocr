package dev.janakhpon.monocr.ui

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.ui.components.ProcessingView
import dev.janakhpon.monocr.ui.theme.MonOCRTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ProgressTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val pdf = Uri.parse("content://test/book.pdf")
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A three-page document whose pages are released one at a time by the test. */
    private class SteppedDocument {
        val pages = List(3) { CompletableDeferred<Unit>() }
        val done = CompletableDeferred<Unit>()
        suspend fun read(onPage: (Int, Int) -> Unit) = run {
            for (i in 0 until 3) { onPage(i + 1, 3); pages[i].await() }
            done.await()
            FakePipeline.result("Page 1\ntext")
        }
    }

    @Test fun `a document reports each page before it succeeds`() {
        val doc = SteppedDocument()
        val pipeline = FakePipeline().apply { document = { _, _, onPage -> doc.read(onPage) } }
        val viewModel = MainViewModel(pipeline)
        idle()

        viewModel.onPdfSelected(context, pdf, previewUri = null)
        idle()
        assertEquals(UiState.Processing(pdf, ScanProgress(1, 3)), viewModel.uiState.value)

        doc.pages[0].complete(Unit); idle()
        assertEquals(UiState.Processing(pdf, ScanProgress(2, 3)), viewModel.uiState.value)

        doc.pages[1].complete(Unit); doc.pages[2].complete(Unit); doc.done.complete(Unit); idle()
        assertTrue(viewModel.uiState.value is UiState.Success)
    }

    @Test fun `cancelling a document returns to Ready and drops its late result`() {
        val doc = SteppedDocument()
        val pipeline = FakePipeline().apply { document = { _, _, onPage -> doc.read(onPage) } }
        val viewModel = MainViewModel(pipeline)
        idle()

        viewModel.onPdfSelected(context, pdf, previewUri = null)
        idle()
        viewModel.reset()
        doc.pages.forEach { it.complete(Unit) }; doc.done.complete(Unit); idle()

        assertEquals(UiState.Ready, viewModel.uiState.value)
        assertEquals(emptyList<String>(), pipeline.saved)
    }

    @Test fun `the processing view shows the page and a cancel button`() {
        var cancelled = 0
        compose.setContent {
            MonOCRTheme(darkTheme = false) {
                ProcessingView(imageUri = pdf, progress = ScanProgress(2, 3), onCancel = { cancelled++ })
            }
        }
        compose.onNodeWithText(str(R.string.scan_progress, 2, 3)).assertExists()
        compose.onNodeWithText(str(R.string.btn_cancel)).performClick()
        assertEquals(1, cancelled)
    }
}
