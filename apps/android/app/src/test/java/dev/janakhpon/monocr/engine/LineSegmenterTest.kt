package dev.janakhpon.monocr.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LineSegmenter].
 *
 * These used to build `android.graphics.Bitmap` directly, which throws "not mocked"
 * in a plain JVM unit test, so they could not have been passing. [LineSegmenter]
 * takes a [GreyImage] now and they run for real.
 */
class LineSegmenterTest {

    /**
     * A white page with a band of vertical black strokes at each given row range.
     *
     * Strokes, not a solid bar. The segmenter binarizes against a 25px local mean, so
     * the inside of a solid 36px bar is not darker than its own neighbourhood and only
     * the bar's two edges register as ink — one stripe comes back as two thin bands.
     * That is correct behaviour on an input that is not text; it just means a solid bar
     * tests nothing about line finding. These tests asserted against a solid bar and
     * could not run, so nobody found out.
     */
    private fun textLikePage(width: Int, height: Int, stripes: List<IntRange>): GreyImage {
        val pixels = IntArray(width * height) { 255 }
        for (stripe in stripes) {
            for (y in stripe) {
                for (x in 0 until width) {
                    if (x % 4 < 2) pixels[y * width + x] = 0
                }
            }
        }
        return GreyImage(width, height, pixels)
    }

    private val pageRatio = SegmentationMode.PAGE.densityThresholdRatio!!

    @Test
    fun `two horizontal stripes produce two segments`() = runBlocking {
        val page = textLikePage(
            width = 300,
            height = 200,
            stripes = listOf(
                20..55,   // line 1: rows 20-55
                120..155  // line 2: rows 120-155
            )
        )

        val segments = LineSegmenter.segment(page, pageRatio)

        assertEquals("Expected 2 segments", 2, segments.size)
        // Each segment should cover the stripe region (with padding)
        assertTrue("Segment 0 y should be near 20", segments[0].y <= 20)
        assertTrue("Segment 0 height should cover stripe", segments[0].height >= 35)
        assertTrue("Segment 1 y should be near 120", segments[1].y <= 120)
        assertTrue("Segment 1 height should cover stripe", segments[1].height >= 35)
    }

    @Test
    fun `single stripe produces one segment`() = runBlocking {
        val page = textLikePage(width = 200, height = 100, stripes = listOf(30..60))

        val segments = LineSegmenter.segment(page, pageRatio)

        assertEquals("Expected 1 segment", 1, segments.size)
    }

    @Test
    fun `blank image produces no segments`() = runBlocking {
        val page = GreyImage(200, 100, IntArray(200 * 100) { 255 })

        val segments = LineSegmenter.segment(page, pageRatio)

        assertEquals("Expected 0 segments for blank image", 0, segments.size)
    }

    @Test
    fun `a band filling the page and not line shaped is called a block`() {
        // 500px tall on a 760px page, only twice as wide as it is tall: this is the
        // shape a fused multi-line block has, and the recogniser answers fluently and
        // wrongly on it.
        val block = LineSegment(x = 0, y = 100, width = 1000, height = 500)
        assertFalse(LineSegmenter.looksLikeALine(block, pageHeight = 760))
    }

    @Test
    fun `a wide band is a line however tall it is`() {
        // A single-line crop is 100% of its own image, so page fraction alone would
        // reject it. Aspect ratio is what saves it.
        val crop = LineSegment(x = 0, y = 0, width = 1200, height = 160)
        assertTrue(LineSegmenter.looksLikeALine(crop, pageHeight = 160))
    }

    @Test
    fun `a short word on a page is a line even though it is taller than wide`() {
        val word = LineSegment(x = 0, y = 300, width = 60, height = 80)
        assertTrue(LineSegmenter.looksLikeALine(word, pageHeight = 1000))
    }

    @Test
    fun `a degenerate band is not a line`() {
        assertFalse(LineSegmenter.looksLikeALine(LineSegment(0, 0, 100, 0), pageHeight = 500))
        assertFalse(LineSegmenter.looksLikeALine(LineSegment(0, 0, 100, 50), pageHeight = 0))
    }

    /**
     * Tightly-set lines must not fuse into one band.
     *
     * The boundary test reads the RAW row profile. It read the smoothed one until
     * 2026-08-28, and the cost was measured on this port rather than argued: pages
     * of 14px lines separated by 5, 6 and 8 pixels came back as ONE band each,
     * against 29, 28 and 25 lines actually drawn. Reading the raw profile returns
     * exactly the drawn count, and at 12px and wider the two agree exactly.
     *
     * The threshold is still calibrated from the smoothed mean, which is what the
     * reference does and why the two profiles both exist
     * (`mon_OCR/src/monocr/segmenter.py`, "Valley detection (dual-histogram)").
     *
     * 8px is the gap this pins. Below 5px both profiles fuse, and that is the
     * vertical smear doing its job: a kernel of 5 is meant to bridge a 4px gap so
     * a floating diacritic stays attached to its base line.
     */
    @Test
    fun `lines eight pixels apart stay separate`() = runBlocking {
        val w = 400
        val h = 600
        val lineH = 14
        val gap = 8
        val px = IntArray(w * h) { 255 }
        var y = 20
        var drawn = 0
        while (y + lineH < h - 20) {
            for (yy in y until y + lineH) {
                for (x in 20 until w - 20) {
                    // Words with gaps: a solid ribbon this wide reads as a printed
                    // rule and trips the ink-share ceiling, which would make the
                    // fixture prove nothing.
                    val insideAWord = (x - 20) % 45 < 30
                    if (insideAWord && x % 4 < 2) px[yy * w + x] = 0
                }
            }
            drawn++
            y += lineH + gap
        }

        val bands = LineSegmenter.segment(GreyImage(w, h, px), pageRatio)

        assertEquals(
            "$drawn lines were drawn 8px apart; reading the smoothed profile returns 1",
            drawn, bands.size
        )
    }
}
