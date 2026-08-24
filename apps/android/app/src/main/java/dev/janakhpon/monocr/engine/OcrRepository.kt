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
    val durationMs: Long,
    /** Which segmentation mode produced this. Shown so the user can try another. */
    val mode: SegmentationMode = SegmentationMode.PAGE,
    /**
     * Bands that came back shaped like a block rather than a line
     * ([LineSegmenter.looksLikeALine]). The model answers fluently on these and is
     * wrong, so the count is surfaced rather than the reading being dropped.
     */
    val blockShapedLineCount: Int = 0,
    /** Lines the model runtime failed on. Not the same as lines with no text. */
    val failedLineCount: Int = 0
)

/**
 * Orchestrates the full OCR pipeline:
 *   Bitmap → normalise page → segment lines → tile wide lines → preprocess → infer
 *   → CTC decode → join
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
     * Perform OCR on [bitmap] using [mode].
     *
     * NOTE: The caller retains ownership of [bitmap]; this function does NOT recycle it,
     * but will not hold a reference after returning. The bitmap is safe to recycle
     * immediately after [performOcr] completes.
     *
     * Returns an [OcrResult] with extracted text, line count, and wall-clock duration.
     */
    suspend fun performOcr(
        bitmap: Bitmap,
        mode: SegmentationMode = SegmentationMode.PAGE
    ): OcrResult = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()

        MonLogger.i("starting ocr: size=${bitmap.width}x${bitmap.height} mode=$mode")

        // 1. Normalise polarity and background for the whole page, once, before
        //    anything measures ink. Doing this per line after segmentation meant the
        //    projection profile read the background of an inverted page as text.
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val page = PageNormalizer.normalize(
            GreyImage.fromArgbInPlace(argb, bitmap.width, bitmap.height)
        )

        // 2. Segment lines
        val ratio = mode.densityThresholdRatio
        // The shape verdict applies to the whole-image case too, and it matters most
        // there: a full page read as one line is the case that comes back as fluent
        // Mon that appears nowhere on the page.
        val fullPage = LineSegment(0, 0, page.width, page.height).let {
            it.copy(looksLikeALine = LineSegmenter.looksLikeALine(it, page.height))
        }
        val segments = if (ratio == null) {
            // LINE mode: the image is already one line, so there is nothing to find
            // and a projection profile would only chop it up.
            listOf(fullPage)
        } else {
            LineSegmenter.segment(page, ratio).ifEmpty {
                MonLogger.d("no lines detected, using full image as fallback")
                listOf(fullPage)
            }
        }
        val blockShaped = segments.count { !it.looksLikeALine }
        MonLogger.d("segmented: lines=${segments.size} block_shaped=$blockShaped mode=$mode")

        // 3. Tile every line wide enough to overflow the model window. This has to
        //    happen while the grey buffer is still grey, because the next step
        //    consumes it.
        val tiledLines = segments.map { segment ->
            LineTiler.tileSegment(
                page,
                segment,
                ImagePreprocessor.TARGET_HEIGHT,
                ImagePreprocessor.TARGET_WIDTH
            )
        }
        val tileCount = tiledLines.sumOf { it.size }
        if (tileCount != segments.size) {
            MonLogger.d("tiled wide lines: lines=${segments.size} tiles=$tileCount")
        }

        // 4. Preprocess + infer each tile. Tiles of one line join with no separator;
        //    they are pieces of a single reading, and a separator here is what turns
        //    one line into "Mon E-boo" and "k library".
        val normalizedBitmap = ImagePreprocessor.toBitmapConsuming(page)
        var failedLines = 0
        val lineTexts = mutableListOf<String>()
        try {
            for (tiles in tiledLines) {
                try {
                    val line = StringBuilder()
                    for (tile in tiles) {
                        line.append(engine.runInference(ImagePreprocessor.processLine(normalizedBitmap, tile)))
                    }
                    if (line.isNotBlank()) lineTexts.add(line.toString())
                } catch (e: LineInferenceException) {
                    // Counted, logged and reported, not swallowed. Aborting the page on
                    // the first bad line would lose a 300-page PDF to one driver hiccup;
                    // returning "" silently was the bug that made a broken device look
                    // like a blank document.
                    failedLines++
                    MonLogger.e("line inference failed: line=${tiles.firstOrNull()}", e)
                }
            }
        } finally {
            normalizedBitmap.recycle()
        }

        // Every line failing is not a blank page, it is a broken engine. Say so.
        if (failedLines > 0 && lineTexts.isEmpty()) {
            throw LineInferenceException(
                "all $failedLines line(s) failed in the ONNX runtime; no text could be read"
            )
        }

        val duration = System.currentTimeMillis() - startMs
        OcrResult(
            text = lineTexts.joinToString("\n"),
            lineCount = lineTexts.size,
            durationMs = duration,
            mode = mode,
            blockShapedLineCount = blockShaped,
            failedLineCount = failedLines
        )
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
     *
     * A PDF render is a page of dense text by construction, so it gets
     * [SegmentationMode.PAGE] and the mode is not offered per page.
     */
    suspend fun performMultiPageOcr(context: Context, uri: android.net.Uri): OcrResult = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()
        val pageCount = PdfUtil.getPageCount(context, uri)
        val allTexts = mutableListOf<String>()
        var totalLines = 0
        var totalBlockShaped = 0
        var totalFailed = 0

        MonLogger.i("starting multi-page ocr: uri=$uri pages=$pageCount")

        for (i in 0 until pageCount) {
            MonLogger.d("processing pdf page: index=${i + 1} of=$pageCount")
            val bitmap = PdfUtil.renderPdfPageToBitmap(context, uri, i)
            if (bitmap != null) {
                val pageResult = performOcr(bitmap, SegmentationMode.PAGE)
                if (pageResult.text.isNotBlank()) {
                    allTexts.add("Page ${i + 1}\n${pageResult.text}")
                    totalLines += pageResult.lineCount
                }
                totalBlockShaped += pageResult.blockShapedLineCount
                totalFailed += pageResult.failedLineCount
                bitmap.recycle()
            }
        }

        val combinedText = allTexts.joinToString("\n\n")
        val totalDuration = System.currentTimeMillis() - startMs

        MonLogger.i(
            "multi-page ocr done: duration_ms=$totalDuration lines=$totalLines " +
                "block_shaped=$totalBlockShaped failed_lines=$totalFailed"
        )

        OcrResult(
            text = combinedText,
            lineCount = totalLines,
            pageCount = pageCount,
            durationMs = totalDuration,
            mode = SegmentationMode.PAGE,
            blockShapedLineCount = totalBlockShaped,
            failedLineCount = totalFailed
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
