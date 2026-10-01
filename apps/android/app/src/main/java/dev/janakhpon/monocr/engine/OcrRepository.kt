package dev.janakhpon.monocr.engine

import dev.janakhpon.monocr.util.MonLogger

import dev.janakhpon.monocr.data.HistoryDatabase
import dev.janakhpon.monocr.data.HistoryDao
import dev.janakhpon.monocr.data.HistoryRecord
import android.content.Context
import android.graphics.Bitmap
import dev.janakhpon.monocr.util.PdfUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val pageMutex = Mutex()

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
        pageMutex.withLock {
            currentCoroutineContext().ensureActive()
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
            var noRegionsDetected = false
            val segments = if (ratio == null) {
                // LINE mode: the image is already one line, so there is nothing to find
                // and a projection profile would only chop it up.
                listOf(fullPage)
            } else {
                LineSegmenter.segment(page, ratio).ifEmpty {
                    noRegionsDetected = true
                    MonLogger.d("no lines detected, using full image as fallback")
                    listOf(fullPage)
                }
            }
            // Measured on the normalised page, which is what the segmenter and the model
            // actually see, and BEFORE the empty-segments fallback below replaces an empty
            // result with the whole page. Assessing after it would hide the very case
            // worth reporting, which is the ordering the web port documents at its own
            // call site.
            val captureLooksSoft = CaptureQuality.isSoft(page)
            if (captureLooksSoft) {
                MonLogger.w("capture looks soft; the reading may be unreliable")
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
                    currentCoroutineContext().ensureActive()
                    try {
                        val line = StringBuilder()
                        for (tile in tiles) {
                            currentCoroutineContext().ensureActive()
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
                noRegionsDetected = noRegionsDetected,
                lineCount = lineTexts.size,
                durationMs = duration,
                mode = mode,
                blockShapedLineCount = blockShaped,
                failedLineCount = failedLines,
                captureLooksSoft = captureLooksSoft
            )
        }
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
        fileUri: String? = null,
        rawText: String? = null,
        warningSummary: String? = null
    ) = withContext(Dispatchers.IO) {
        historyDao.insert(
            HistoryRecord(
                fileName = fileName,
                fileType = fileType,
                text = text,
                processingTime = durationMs.toInt(),
                category = category,
                fileUri = fileUri,
                rawText = rawText,
                warningSummary = warningSummary
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
        val pages = readDocumentPages(
            pageCount,
            render = { index -> PdfUtil.renderPdfPageToBitmap(context, uri, index) },
            recognize = { bitmap -> performOcr(bitmap, SegmentationMode.PAGE) },
            release = { bitmap -> bitmap.recycle() }
        )
        combinePageResults(pages, System.currentTimeMillis() - startMs)
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
