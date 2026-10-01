package dev.janakhpon.monocr.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class ReliabilityTest {
    private fun result(raw: String = "text", failed: Int = 0) = OcrResult(
        raw, 1, durationMs = 0, rawText = raw, failedLineCount = failed
    )

    @Test fun `missing PDF page keeps its index and later text`() = runBlocking {
        val released = mutableListOf<Int>()
        val pages = readDocumentPages(3, { if (it == 1) null else it },
            { result("page$it") }, { released.add(it) })
        assertEquals(listOf(1, 2, 3), pages.map { it.pageNumber })
        assertEquals(PageStatus.RENDER_FAILED, pages[1].status)
        assertEquals(listOf(0, 2), released)
        val document = combinePageResults(pages, 3)
        assertEquals(3, document.pageCount)
        assertEquals("Page 1\npage0\n\nPage 3\npage2", document.text)
        assertTrue(document.warningSummary()!!.contains("Page 2: rendering failed"))
    }

    @Test fun `blank failure and partial recognition are different outcomes`() = runBlocking {
        val released = mutableListOf<Int>()
        val pages = readDocumentPages(3, { it }, {
            when (it) { 0 -> result(""); 1 -> throw LineInferenceException("driver"); else -> result(failed = 1) }
        }, { released.add(it) })
        assertEquals(listOf(PageStatus.EMPTY_UNVERIFIED, PageStatus.INFERENCE_FAILED, PageStatus.PARTIAL), pages.map { it.status })
        assertEquals(listOf(0, 1, 2), released)
    }

    @Test fun `cancelled recognition releases current page and stops document`() {
        val rendered = mutableListOf<Int>(); val released = mutableListOf<Int>()
        assertThrows(CancellationException::class.java) {
            runBlocking { readDocumentPages(3, { rendered.add(it); it },
                { throw CancellationException("cancel") }, { released.add(it) }) }
        }
        assertEquals(listOf(0), rendered)
        assertEquals(listOf(0), released)
    }

    @Test fun `zero pages fails rather than becoming blank success`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { readDocumentPages(0, { it }, { result() }, {}) }
        }
    }

    @Test fun `combined text is the decoded text byte for byte`() {
        // U+1025 U+102E has a canonical composition (U+1026). Nothing on the output
        // path may apply it: a change to the text produced needs its own A/B.
        val pages = listOf(OcrPageOutcome(1, PageStatus.COMPLETE, result("\u1025\u102e")))
        val document = combinePageResults(pages, 0)
        assertEquals("Page 1\n\u1025\u102e", document.text)
        assertEquals(document.text, document.rawText)
    }

    @Test fun `nonfinite and malformed output is failure never empty text`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(LineInferenceException::class.java) { CtcDecoder.decode(floatArrayOf(0f, value), 1, 2, "a") }
        }
        assertThrows(LineInferenceException::class.java) { CtcDecoder.decode(floatArrayOf(1f), 1, 2, "a") }
        assertEquals("", CtcDecoder.decode(floatArrayOf(4f, 0f), 1, 2, "a"))
    }

    @Test fun `verified cache repairs truncation and leaves valid published bytes on failed update`() {
        val directory = Files.createTempDirectory("model-cache-test").toFile()
        try {
            val file = directory.resolve("model.onnx")
            val bytes = "complete model".toByteArray()
            val hash = VerifiedArtifactCache.sha256(ByteArrayInputStream(bytes))
            file.writeText("truncated")
            var copies = 0
            VerifiedArtifactCache.ensure(file, hash) { copies++; ByteArrayInputStream(bytes) }
            assertArrayEquals(bytes, file.readBytes())
            VerifiedArtifactCache.ensure(file, hash) { error("A valid cache must not be recopied") }
            assertEquals(1, copies)
            assertThrows(IllegalStateException::class.java) {
                VerifiedArtifactCache.ensure(file, "incorrect hash") { ByteArrayInputStream("bad".toByteArray()) }
            }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(listOf("model.onnx"), directory.listFiles()!!.map { it.name })
        } finally { directory.deleteRecursively() }
    }
}
