package dev.janakhpon.monocr.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class OcrResult(
    val text: String,
    val lineCount: Int,
    val pageCount: Int = 1,
    val durationMs: Long,
    val mode: SegmentationMode = SegmentationMode.PAGE,
    val blockShapedLineCount: Int = 0,
    val failedLineCount: Int = 0,
    val captureLooksSoft: Boolean = false,
    val rawText: String = text,
    val pages: List<OcrPageOutcome> = emptyList(),
    val noRegionsDetected: Boolean = false
) {
    /** Retained in history too: warnings must survive closing the result screen. */
    fun warningSummary(includeCaptureWarnings: Boolean = true): String? = buildList {
        if (includeCaptureWarnings && captureLooksSoft) add("The image may be too blurred to read reliably.")
        if (includeCaptureWarnings && blockShapedLineCount > 0) add("$blockShapedLineCount region(s) may contain merged lines. Review the source.")
        if (includeCaptureWarnings && failedLineCount > 0) add("$failedLineCount line(s) could not be read. Text is incomplete.")
        if (noRegionsDetected) add("No text regions were detected. The full-image reading needs review.")
        if (text.isBlank()) add("No text was recognized. This does not confirm a blank page.")
        for (page in pages) when (page.status) {
            PageStatus.RENDER_FAILED -> add("Page ${page.pageNumber}: rendering failed; text is missing.")
            PageStatus.INFERENCE_FAILED -> add("Page ${page.pageNumber}: recognition failed; text is missing.")
            PageStatus.PARTIAL -> add("Page ${page.pageNumber}: some lines failed; review this page.")
            PageStatus.EMPTY_UNVERIFIED -> add("Page ${page.pageNumber}: no text recognized; check the source.")
            PageStatus.COMPLETE -> Unit
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

enum class PageStatus { COMPLETE, EMPTY_UNVERIFIED, PARTIAL, RENDER_FAILED, INFERENCE_FAILED }

data class OcrPageOutcome(
    val pageNumber: Int,
    val status: PageStatus,
    val result: OcrResult? = null,
    val error: String? = null
)

/** Every requested page gets an outcome. A cancellation propagates, never becomes failure/blank. */
internal suspend fun <T : Any> readDocumentPages(
    pageCount: Int,
    render: suspend (Int) -> T?,
    recognize: suspend (T) -> OcrResult,
    release: (T) -> Unit
): List<OcrPageOutcome> {
    require(pageCount > 0) { "The PDF has no readable pages." }
    return (0 until pageCount).map { index ->
        currentCoroutineContext().ensureActive()
        val page = try {
            render(index)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            return@map OcrPageOutcome(index + 1, PageStatus.RENDER_FAILED, error = e.message)
        } ?: return@map OcrPageOutcome(index + 1, PageStatus.RENDER_FAILED, error = "Page could not be rendered")
        try {
            currentCoroutineContext().ensureActive()
            val result = recognize(page)
            currentCoroutineContext().ensureActive()
            val status = when {
                result.failedLineCount > 0 -> PageStatus.PARTIAL
                result.text.isBlank() -> PageStatus.EMPTY_UNVERIFIED
                else -> PageStatus.COMPLETE
            }
            OcrPageOutcome(index + 1, status, result)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            OcrPageOutcome(index + 1, PageStatus.INFERENCE_FAILED, error = e.message)
        } finally { release(page) }
    }
}

internal fun combinePageResults(pages: List<OcrPageOutcome>, durationMs: Long): OcrResult {
    require(pages.isNotEmpty()) { "The PDF has no pages." }
    val results = pages.mapNotNull { it.result }
    // The combined text is the one this app has always produced: each page that read
    // text is labelled with its number, and a page with no text adds nothing. What
    // happened to the other pages is reported through [OcrResult.warningSummary].
    fun join(textOf: (OcrResult) -> String) = pages.mapNotNull { page ->
        page.result?.let(textOf)?.takeIf { it.isNotBlank() }?.let { "Page ${page.pageNumber}\n$it" }
    }.joinToString("\n\n")
    return OcrResult(
        text = join { it.text }, rawText = join { it.rawText }, pages = pages, pageCount = pages.size,
        lineCount = results.sumOf { it.lineCount }, durationMs = durationMs,
        failedLineCount = results.sumOf { it.failedLineCount },
        blockShapedLineCount = results.sumOf { it.blockShapedLineCount },
        captureLooksSoft = results.any { it.captureLooksSoft },
        noRegionsDetected = results.any { it.noRegionsDetected }
    )
}
