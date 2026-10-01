package dev.janakhpon.monocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.janakhpon.monocr.engine.OcrRepository
import dev.janakhpon.monocr.engine.SegmentationMode
import dev.janakhpon.monocr.ui.MainViewModel
import dev.janakhpon.monocr.ui.UiState
import dev.janakhpon.monocr.ui.screens.ImportPreparationOps
import dev.janakhpon.monocr.ui.screens.ImportPreparationResources
import dev.janakhpon.monocr.ui.screens.PreparedPdfPreview
import dev.janakhpon.monocr.ui.screens.loadAndProcess
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** App-boundary coverage for cancellation before an import reaches the scan job. */
class ImportRecoveryInstrumentedTest {
    @Test fun cancellingActualImagePreparationRestoresReadyAndRecyclesOwnedBitmap() = runBlocking {
        withViewModel { viewModel, context ->
            val started = CompletableDeferred<Unit>()
            lateinit var ownedBitmap: Bitmap
            val preparation = imagePreparation { _, _, resources ->
                ownedBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                resources.bitmap = ownedBitmap
                started.complete(Unit)
                awaitCancellation()
            }
            val caller = launch(Dispatchers.Main.immediate) {
                loadAndProcess(context, Uri.parse("content://test/image"), viewModel,
                    preparation) { SegmentationMode.LINE }
            }
            started.await()

            caller.cancelAndJoin()

            assertEquals(UiState.Ready, viewModel.uiState.value)
            assertTrue(ownedBitmap.isRecycled)
        }
    }

    @Test fun cancellingActualPdfPreparationRestoresReadyAndDeletesOwnedPreview() = runBlocking {
        withViewModel { viewModel, context ->
            val started = CompletableDeferred<Unit>()
            lateinit var previewFile: File
            val preparation = pdfPreparation { _, _, resources ->
                previewFile = File.createTempFile("cancelled-preview", ".jpg", context.cacheDir)
                resources.previewFile = previewFile
                started.complete(Unit)
                awaitCancellation()
            }
            val caller = launch(Dispatchers.Main.immediate) {
                loadAndProcess(context, Uri.parse("content://test/document.pdf"), viewModel,
                    preparation) { SegmentationMode.PAGE }
            }
            started.await()

            caller.cancelAndJoin()

            assertEquals(UiState.Ready, viewModel.uiState.value)
            assertFalse(previewFile.exists())
        }
    }

    @Test fun lateActualCancellationCannotClearNewerSelection() = runBlocking {
        withViewModel { viewModel, context ->
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val first = launch(Dispatchers.Main.immediate) {
                loadAndProcess(context, Uri.parse("content://test/first"), viewModel,
                    imagePreparation { _, _, _ -> firstStarted.complete(Unit); awaitCancellation() }) {
                    SegmentationMode.LINE
                }
            }
            firstStarted.await()
            val secondUri = Uri.parse("content://test/second")
            val second = launch(Dispatchers.Main.immediate) {
                loadAndProcess(context, secondUri, viewModel,
                    imagePreparation { _, _, _ -> secondStarted.complete(Unit); awaitCancellation() }) {
                    SegmentationMode.LINE
                }
            }
            secondStarted.await()

            first.cancelAndJoin()

            assertEquals(UiState.Processing(secondUri), viewModel.uiState.value)
            second.cancelAndJoin()
            assertEquals(UiState.Ready, viewModel.uiState.value)
        }
    }

    @Test fun resetDuringActualPreparationWinsOverLateCancellation() = runBlocking {
        withViewModel { viewModel, context ->
            withTimeout(60_000) { viewModel.uiState.first { it is UiState.Ready } }
            val started = CompletableDeferred<Unit>()
            val caller = launch(Dispatchers.Main.immediate) {
                loadAndProcess(context, Uri.parse("content://test/reset"), viewModel,
                    imagePreparation { _, _, _ -> started.complete(Unit); awaitCancellation() }) {
                    SegmentationMode.LINE
                }
            }
            started.await()

            viewModel.reset()
            caller.cancelAndJoin()

            assertEquals(UiState.Ready, viewModel.uiState.value)
        }
    }

    @Test fun actualPreparationErrorLeavesAUsableErrorState() = runBlocking {
        withViewModel { viewModel, context ->
            val uri = Uri.parse("content://test/broken")

            loadAndProcess(context, uri, viewModel,
                imagePreparation { _, _, _ -> error("decode failed") }) { SegmentationMode.LINE }

            assertEquals(UiState.OcrError(uri, "decode failed"), viewModel.uiState.value)
        }
    }

    @Test fun successfulActualImageTransferPublishesAndReleasesTheBitmap() = runBlocking {
        withViewModel { viewModel, context ->
            withTimeout(60_000) { viewModel.uiState.first { it is UiState.Ready } }
            val uri = Uri.parse("content://test/ready")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xffffffff.toInt())
            }

            loadAndProcess(context, uri, viewModel,
                imagePreparation { _, _, resources ->
                    resources.bitmap = bitmap
                    bitmap
                }) { SegmentationMode.LINE }

            val terminal = withTimeout(60_000) {
                viewModel.uiState.first { it is UiState.Success || it is UiState.OcrError }
            }
            assertTrue(terminal is UiState.Success)
            withTimeout(60_000) {
                while (!bitmap.isRecycled) yield()
            }
            assertTrue(bitmap.isRecycled)
        }
    }

    private fun imagePreparation(
        decode: suspend (Context, Uri, ImportPreparationResources) -> Bitmap?
    ) = ImportPreparationOps(
        fileSize = { _, _ -> 0L },
        isPdf = { _, _ -> false },
        preparePdfPreview = { _, _, _ -> error("unexpected PDF preparation") },
        decodeImage = decode
    )

    private fun pdfPreparation(
        prepare: suspend (Context, Uri, ImportPreparationResources) -> PreparedPdfPreview
    ) = ImportPreparationOps(
        fileSize = { _, _ -> 0L },
        isPdf = { _, _ -> true },
        preparePdfPreview = prepare,
        decodeImage = { _, _, _ -> error("unexpected image preparation") }
    )

    private suspend fun withViewModel(
        block: suspend CoroutineScope.(MainViewModel, Context) -> Unit
    ) = withContext(Dispatchers.Main.immediate) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ViewModelStore()
        val viewModel = MainViewModel(OcrRepository(context))
        store.put("import-recovery", viewModel)
        try {
            block(viewModel, context)
        } finally {
            store.clear()
        }
    }
}
