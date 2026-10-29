package dev.janakhpon.monocr.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.engine.EmptyPdfException
import dev.janakhpon.monocr.engine.LineInferenceException
import dev.janakhpon.monocr.ui.components.OcrErrorView
import dev.janakhpon.monocr.ui.screens.ImportPreparationOps
import dev.janakhpon.monocr.ui.screens.PreparedPdfPreview
import dev.janakhpon.monocr.ui.screens.loadAndProcess
import dev.janakhpon.monocr.ui.theme.MonOCRTheme
import dev.janakhpon.monocr.engine.SegmentationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
class OcrFailureTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://test/file")
    private val pipeline = FakePipeline()
    private val viewModel = MainViewModel(pipeline)

    private fun ops(
        size: Long = 1024,
        pdf: Boolean = false,
        decode: suspend () -> Bitmap? = { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) }
    ) = ImportPreparationOps(
        fileSize = { _, _ -> size },
        isPdf = { _, _ -> pdf },
        preparePdfPreview = { _, _, _ -> PreparedPdfPreview(null) },
        decodeImage = { _, _, _ -> decode() }
    )

    private fun import(ops: ImportPreparationOps): UiState {
        runBlocking { loadAndProcess(context, uri, viewModel, ops) { SegmentationMode.PAGE } }
        shadowOf(Looper.getMainLooper()).idle()
        return viewModel.uiState.value
    }

    @Test fun `a file over the cap is reported as too large, not as a bad image`() {
        assertEquals(UiState.OcrError(uri, OcrFailure.FileTooLarge(50)), import(ops(size = 60L * 1024 * 1024)))
    }

    @Test fun `an image that will not decode is reported as unreadable`() {
        assertEquals(UiState.OcrError(uri, OcrFailure.UnreadableImage), import(ops(decode = { null })))
        assertEquals(UiState.OcrError(uri, OcrFailure.UnreadableImage),
            import(ops(decode = { error("bad header") })))
    }

    @Test fun `a file that cannot be opened is not blamed on the image`() {
        assertEquals(UiState.OcrError(uri, OcrFailure.UnreadableFile),
            import(ops(decode = { throw FileNotFoundException("gone") })))
    }

    @Test fun `engine failures map to their own types`() {
        pipeline.document = { _, _ -> throw EmptyPdfException() }
        assertEquals(UiState.OcrError(uri, OcrFailure.PdfNoPages), import(ops(pdf = true)))

        pipeline.image = { _, _ -> throw LineInferenceException("all lines failed") }
        assertEquals(UiState.OcrError(uri, OcrFailure.ModelRuntime), import(ops()))

        pipeline.image = { _, _ -> throw OutOfMemoryError() }
        assertEquals(UiState.OcrError(uri, OcrFailure.OutOfMemory), import(ops()))
    }

    @Test fun `too large shows its own message and the CLI guide`() {
        var docsOpened = false
        compose.setContent {
            MonOCRTheme(darkTheme = false) {
                OcrErrorView(uri, OcrFailure.FileTooLarge(50), onReset = {}, onViewDocs = { docsOpened = true })
            }
        }
        compose.onNodeWithText(str(R.string.error_file_large, 50)).assertExists()
        compose.onNodeWithText(str(R.string.view_cli_guide)).assertHasClickAction()
        compose.onNodeWithText(str(R.string.view_cli_guide)).performClick()
        assertEquals(true, docsOpened)
    }

    @Test fun `an unknown failure gets the generic advice and no CLI guide`() {
        compose.setContent {
            MonOCRTheme(darkTheme = false) {
                OcrErrorView(uri, OcrFailure.Unknown("x"), onReset = {}, onViewDocs = {})
            }
        }
        compose.onNodeWithText(str(R.string.error_generic_ocr)).assertExists()
        compose.onAllNodesWithText(str(R.string.view_cli_guide)).assertCountEquals(0)
    }
}
