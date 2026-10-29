package dev.janakhpon.monocr.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.engine.SegmentationMode
import dev.janakhpon.monocr.ui.screens.ImportPreparationOps
import dev.janakhpon.monocr.ui.screens.PreparedPdfPreview
import dev.janakhpon.monocr.ui.screens.ResultView
import dev.janakhpon.monocr.ui.screens.loadAndProcess
import dev.janakhpon.monocr.ui.screens.writeTextTo
import dev.janakhpon.monocr.ui.theme.MonOCRTheme
import dev.janakhpon.monocr.util.FileUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ResultActionsTest {
    @get:Rule val compose = createComposeRule()
    private val context = RuntimeEnvironment.getApplication()

    @Test fun `each result action is announced once`() {
        compose.setContent {
            MonOCRTheme(darkTheme = false) {
                ResultView(imageUri = Uri.parse("content://test/image"), result = FakePipeline.result("text"))
            }
        }
        for (label in listOf(R.string.save_text, R.string.share_text, R.string.copy_text)) {
            compose.onAllNodesWithText(str(label)).assertCountEquals(1)
            compose.onAllNodesWithContentDescription(str(label)).assertCountEquals(0)
        }
    }

    @Test fun `save writes UTF-8 text to the chosen document and reports failure`() {
        val target = File.createTempFile("result", ".txt").apply { deleteOnExit() }
        val mon = "က္လောဒ်"
        assertTrue(writeTextTo(context, Uri.fromFile(target), mon))
        assertEquals(mon, target.readText(Charsets.UTF_8))

        assertFalse(writeTextTo(context, Uri.parse("content://no.such.provider/x"), mon))
    }

    @Test fun `history shows the provider's display name, not a URI segment`() {
        Robolectric.setupContentProvider(NamedFiles::class.java, NamedFiles.AUTHORITY)
        val uri = Uri.parse("content://${NamedFiles.AUTHORITY}/document/image%3A1000012345")
        assertEquals("IMG_123.jpg", FileUtil.getFileName(context, uri))

        val pipeline = FakePipeline()
        val viewModel = MainViewModel(pipeline)
        val ops = ImportPreparationOps(
            fileSize = { _, _ -> 1L },
            isPdf = { _, _ -> false },
            preparePdfPreview = { _, _, _ -> PreparedPdfPreview(null) },
            decodeImage = { _, _, _ -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) }
        )
        runBlocking { loadAndProcess(context, uri, viewModel, ops) { SegmentationMode.PAGE } }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("IMG_123.jpg"), pipeline.saved)
    }

    /** A documents provider in miniature: every URI is a file called IMG_123.jpg. */
    class NamedFiles : ContentProvider() {
        companion object { const val AUTHORITY = "test.named.files" }
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("IMG_123.jpg")) }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
}
