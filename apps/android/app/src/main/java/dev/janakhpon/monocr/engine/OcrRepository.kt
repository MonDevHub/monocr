package dev.janakhpon.monocr.engine

import dev.janakhpon.monocr.util.MonLogger

import dev.janakhpon.monocr.data.HistoryDatabase
import dev.janakhpon.monocr.data.HistoryDao
import dev.janakhpon.monocr.data.HistoryRecord
import android.content.Context
import android.graphics.Bitmap
import dev.janakhpon.monocr.util.PdfUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OcrResult(
    val text: String,
    val lineCount: Int,
    val pageCount: Int = 1,
    val durationMs: Long
)

/**
 * Orchestrates the full OCR pipeline:
 *   Bitmap → segment lines → preprocess each line → infer → CTC decode → join
 *
 * Equivalent to recognize() in monocr-onnx.ts.
 */


class OcrRepository(
    context: Context
) {
    private val historyDao: HistoryDao by lazy {
        HistoryDatabase.getDatabase(context).historyDao()
    }

    private val engine = MonOcrEngine(context)

    val isEngineReady: Boolean get() = engine.isInitialized

    suspend fun initialize() {
        engine.initialize()
    }

    /**
     * Perform OCR on [bitmap].
     *
     * NOTE: The caller retains ownership of [bitmap]; this function does NOT recycle it,
     * but will not hold a reference after returning. The bitmap is safe to recycle
     * immediately after [performOcr] completes.
     *
     * Returns an [OcrResult] with extracted text, line count, and wall-clock duration.
     */
    suspend fun performOcr(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()

        MonLogger.i("Starting OCR for bitmap: ${bitmap.width}x${bitmap.height}")
        // 1. Segment lines
        val segments = LineSegmenter.segment(bitmap).ifEmpty {
            MonLogger.d("No lines detected, using full image as fallback")
            listOf(LineSegment(0, 0, bitmap.width, bitmap.height))
        }
        MonLogger.d("Segmented into ${segments.size} lines")

        // 2. Preprocess + infer each line
        val lineTexts = segments.mapNotNull { segment ->
            val lineData = ImagePreprocessor.processLine(bitmap, segment)
            val text = engine.runInference(lineData)
            text.takeIf { it.isNotBlank() }
        }

        val duration = System.currentTimeMillis() - startMs
        val result = OcrResult(
            text      = lineTexts.joinToString("\n"),
            lineCount = lineTexts.size,
            durationMs = duration
        )
        return@withContext result
    }

    /**
     * Save a result to the local history.
     */
    suspend fun saveToHistory(
        fileName: String,
        fileType: String,
        text: String,
        durationMs: Long,
        category: String = "ocr-scan",
        fileUri: String? = null
    ) = withContext(Dispatchers.IO) {
        historyDao.insert(
            HistoryRecord(
                fileName = fileName,
                fileType = fileType,
                text = text,
                processingTime = durationMs.toInt(),
                category = category,
                fileUri = fileUri
            )
        )
    }

    /**
     * Perform OCR on all pages of a PDF.
     */
    suspend fun performMultiPageOcr(context: Context, uri: android.net.Uri): OcrResult = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()
        val pageCount = PdfUtil.getPageCount(context, uri)
        val allTexts = mutableListOf<String>()
        var totalLines = 0

        MonLogger.i("Starting multi-page OCR for PDF: $uri ($pageCount pages)")

        for (i in 0 until pageCount) {
            MonLogger.d("Processing PDF page ${i + 1}/$pageCount")
            val bitmap = PdfUtil.renderPdfPageToBitmap(context, uri, i)
            if (bitmap != null) {
                val pageResult = performOcr(bitmap)
                if (pageResult.text.isNotBlank()) {
                    allTexts.add("Page ${i + 1}\n${pageResult.text}")
                    totalLines += pageResult.lineCount
                }
                bitmap.recycle()
            }
        }

        val combinedText = allTexts.joinToString("\n\n")
        val totalDuration = System.currentTimeMillis() - startMs

        MonLogger.i("Multi-page OCR completed in ${totalDuration}ms. Found $totalLines lines total.")

        OcrResult(
            text = combinedText,
            lineCount = totalLines,
            pageCount = pageCount,
            durationMs = totalDuration
        )
    }

    fun getScanHistory() = historyDao.getRecordsByCategory("ocr-scan")

    fun getContributionHistory() = historyDao.getRecordsByCategory("contribution")

    fun getFeedbackHistory() = historyDao.getRecordsByCategory("feedback")

    suspend fun deleteHistoryRecord(id: Long) = withContext(Dispatchers.IO) {
        historyDao.deleteById(id)
    }

    suspend fun clearHistory(category: String) = withContext(Dispatchers.IO) {
        historyDao.clearCategory(category)
    }

    fun dispose() {
        engine.dispose()
    }
}
