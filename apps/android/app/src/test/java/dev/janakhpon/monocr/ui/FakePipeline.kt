package dev.janakhpon.monocr.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.janakhpon.monocr.data.HistoryRecord
import dev.janakhpon.monocr.engine.OcrPipeline
import dev.janakhpon.monocr.engine.OcrResult
import dev.janakhpon.monocr.engine.SegmentationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** An [OcrPipeline] whose answers each test sets, so no model or database is loaded. */
internal class FakePipeline : OcrPipeline {
    var image: suspend (Bitmap, SegmentationMode) -> OcrResult = { _, _ -> result("text") }
    var document: suspend (Context, Uri, (Int, Int) -> Unit) -> OcrResult = { _, _, _ -> result("Page 1\ntext") }
    val saved = mutableListOf<String>()

    override val isEngineReady = true
    override suspend fun initialize() {}
    override fun getScanHistory(): Flow<List<HistoryRecord>> = MutableStateFlow(emptyList())
    override suspend fun performOcr(bitmap: Bitmap, mode: SegmentationMode) = image(bitmap, mode)
    override suspend fun performMultiPageOcr(context: Context, uri: Uri, onPage: (Int, Int) -> Unit) =
        document(context, uri, onPage)
    override suspend fun saveToHistory(
        fileName: String, fileType: String, text: String, durationMs: Long,
        category: String, fileUri: String?, rawText: String?, warningSummary: String?
    ) { saved += fileName }
    override suspend fun deleteHistoryRecord(id: Long) {}
    override suspend fun clearHistory(category: String) {}
    override fun dispose() {}

    companion object {
        fun result(text: String) = OcrResult(text = text, lineCount = 1, durationMs = 1)
    }
}
