package dev.janakhpon.monocr.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.janakhpon.monocr.data.HistoryRecord
import kotlinx.coroutines.flow.Flow

/**
 * What [dev.janakhpon.monocr.ui.MainViewModel] needs from [OcrRepository].
 *
 * An interface so the ViewModel's state machine can be tested on the JVM with a fake,
 * without loading the ONNX model or a Room database.
 */
interface OcrPipeline {
    val isEngineReady: Boolean
    suspend fun initialize()
    fun getScanHistory(): Flow<List<HistoryRecord>>
    suspend fun performOcr(bitmap: Bitmap, mode: SegmentationMode = SegmentationMode.PAGE): OcrResult
    suspend fun performMultiPageOcr(context: Context, uri: Uri): OcrResult
    suspend fun saveToHistory(
        fileName: String,
        fileType: String,
        text: String,
        durationMs: Long,
        category: String = "ocr-scan",
        fileUri: String? = null,
        rawText: String? = null,
        warningSummary: String? = null
    )
    suspend fun deleteHistoryRecord(id: Long)
    suspend fun clearHistory(category: String)
    fun dispose()
}
