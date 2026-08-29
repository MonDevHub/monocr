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
     * A page of Mon-shaped lines: a sparse strip of upper marks, [gap] source rows,
     * then a solid consonant body, with [stems] word positions whose stroke crosses
     * the gap.
     *
     * Sparse marks and strokes, not solid bars, for the reason [textLikePage]
     * already gives: the segmenter binarizes against a 25px local mean. Words
     * rather than a full-width ribbon, because a ribbon this wide reads as a printed
     * rule and `suppressPageRules` removes it.
     */
    private fun monPage(gap: Int, stems: Int, lines: Int = 10, stripH: Int = 20): GreyImage {
        val w = 900
        val h = 1200
        val px = IntArray(w * h) { 255 }
        val bodyH = 45
        for (l in 0 until lines) {
            val y0 = 100 + l * 100
            val bodyStart = y0 + stripH + gap
            var g = 0
            var x = 60
            while (x < w - 60) {
                if ((x - 60) % 60 < 42) {
                    for (y in bodyStart until bodyStart + bodyH) {
                        px[y * w + x] = 0; px[y * w + x + 1] = 0
                    }
                    if (g % 3 == 0) {
                        for (y in y0 until y0 + stripH) {
                            px[y * w + x] = 0; px[y * w + x + 1] = 0
                        }
                    }
                    if ((x - 60) / 60 < stems && (x - 60) % 60 == 0) {
                        for (y in y0 until bodyStart) {
                            px[y * w + x] = 0; px[y * w + x + 1] = 0
                        }
                    }
                }
                x += 6; g++
            }
        }
        return GreyImage(w, h, px)
    }

    /** A profile with the given half-open ranges filled to `value`. */
    private fun profile(length: Int, fills: List<Triple<Int, Int, Float>>): FloatArray {
        val h = FloatArray(length)
        for ((a, b, v) in fills) for (y in a until b) h[y] = v
        return h
    }

    // MARK: - mergeRuns
    //
    // The same cases exist in `apps/web/src/lib/segmentation.test.ts` and in iOS
    // `LineMergeTests.swift`. Written as a matching set on purpose: the defect this
    // pass exists to stop is one algorithm's four ports drifting apart unnoticed.
    //
    // Three of the Rust fixtures in `monocr-onnx` `rust/src/segmenter.rs` are
    // degenerate against the mutation they are named for, because with only two runs
    // on the page the median run height IS one of the two: its ink-alone case has
    // runs of 40, 40, 82, 82, so `2 * 40 <= 82` makes the fragment clause fire as
    // well; and its wide-gap and two-lines-apart cases are refused by the ceiling
    // rather than by the clause under test. Every fixture here whose verdict depends
    // on the median therefore carries ordinary full-height lines too, which puts the
    // median where a real page would put it.
    //
    // The first two ARE the Rust geometry verbatim, on purpose: they are the two
    // cases actually measured on a real page, so they are kept exactly as measured.
    // Both are degenerate in the sense above and neither kills a mutation - they are
    // regression anchors, and the isolating cases follow them.

    @Test
    fun `a sub-threshold dip does not end a line`() {
        // The measured case, in the reference's numbers: one line, rows 260-324,
        // split by row 280 carrying 6 ink pixels against a threshold of 7.0. On this
        // port the same shape is a dip row of 16 ink columns against 16.85.
        val hist = profile(400, listOf(Triple(260, 325, 200f)))
        hist[280] = 6f // above zero, below the gap threshold
        assertEquals(
            listOf(260 to 325),
            LineSegmenter.mergeRuns(listOf(260 to 280, 281 to 325), hist, 10, 10)
        )
    }

    @Test
    fun `a zero-ink gap still merges a fragment into its line`() {
        // The other measured case: rows 341-360 are the upper marks and 362-404 the
        // body of one line, separated by TWO rows of genuinely zero ink. The ink
        // clause cannot cross that; the height ratio is what does.
        val hist = profile(500, listOf(Triple(341, 360, 40f), Triple(362, 404, 300f)))
        assertEquals(
            listOf(341 to 404),
            LineSegmenter.mergeRuns(listOf(341 to 360, 362 to 404), hist, 10, 10)
        )
    }

    @Test
    fun `two real lines two rows apart stay separate`() {
        // The case the fragment clause must NOT swallow, and the reason it is a
        // ratio: same gap, same emptiness, but both runs are full height against the
        // page median. A vertical smear was tried instead and fused exactly this
        // pair, which is why the fix is not a smear.
        val hist = profile(
            400,
            listOf(
                Triple(20, 60, 300f), Triple(62, 102, 300f),
                Triple(150, 210, 300f), Triple(260, 320, 300f)
            )
        )
        val runs = listOf(20 to 60, 62 to 102, 150 to 210, 260 to 320)
        assertEquals(runs, LineSegmenter.mergeRuns(runs, hist, 10, 10))
    }

    @Test
    fun `a wide gap is a line boundary however much ink it holds`() {
        // The size bound on its own. Overlapping diacritics can hold the raw profile
        // above zero right across real inter-line spacing; upstream that collapsed 3
        // PDF lines into 1. The two 60-row lines put the ceiling at 120, above the
        // 95-row span a merge here would produce, so the size bound is the only
        // thing refusing it.
        val hist = profile(
            400,
            listOf(
                Triple(20, 60, 300f), Triple(60, 75, 5f), Triple(75, 115, 300f),
                Triple(200, 260, 300f), Triple(300, 360, 300f)
            )
        )
        val runs = listOf(20 to 60, 75 to 115, 200 to 260, 300 to 360)
        assertEquals(runs, LineSegmenter.mergeRuns(runs, hist, 10, 10))
    }

    @Test
    fun `a dip between equal halves merges on the ink clause alone`() {
        // The ink clause isolated. In the dip case above the fragment clause ALSO
        // fires, so dropping the ink test survives it. Here the two halves are 40
        // rows against a page median of 60, so `2 * 40 <= 60` is false and only the
        // ink test can merge them.
        val hist = profile(
            400,
            listOf(
                Triple(20, 60, 300f),
                Triple(60, 62, 5f), // two rows of ink: below any threshold, above zero
                Triple(62, 102, 300f), Triple(150, 210, 300f), Triple(260, 320, 300f)
            )
        )
        assertEquals(
            listOf(20 to 102, 150 to 210, 260 to 320),
            LineSegmenter.mergeRuns(
                listOf(20 to 60, 62 to 102, 150 to 210, 260 to 320), hist, 10, 10
            )
        )
    }

    @Test
    fun `a run of fragments cannot chain past twice a typical line`() {
        // The ceiling and the median, together, on the failure they exist for. Five
        // 20-row fragments two empty rows apart would chain into one 108-row band,
        // and this is the shape that cost 92% of a real page's characters when the
        // fragment test compared against the NEIGHBOUR instead of the median: every
        // merge makes the accumulated run taller, and a taller run makes the next
        // line look more like a fragment.
        //
        // Against a page median of 45 the ceiling is 90, so the chain stops after
        // four. Judging against the neighbour merges nothing here at all, so this
        // case pins both halves of the correction.
        val fills = mutableListOf(
            Triple(0, 20, 200f), Triple(22, 42, 200f), Triple(44, 64, 200f),
            Triple(66, 86, 200f), Triple(88, 108, 200f)
        )
        val lines = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until 5) {
            val y = 200 + i * 100
            fills.add(Triple(y, y + 45, 300f))
            lines.add(y to y + 45)
        }
        val hist = profile(700, fills)
        val runs = listOf(0 to 20, 22 to 42, 44 to 64, 66 to 86, 88 to 108) + lines
        assertEquals(listOf(0 to 86, 88 to 108) + lines, LineSegmenter.mergeRuns(runs, hist, 10, 10))
    }

    @Test
    fun `an empty page is left alone`() {
        assertEquals(emptyList<Pair<Int, Int>>(), LineSegmenter.mergeRuns(emptyList(), FloatArray(10), 10, 10))
    }

    /**
     * The merge must be reached THROUGH [LineSegmenter.segment], not only
     * unit-tested.
     *
     * In the Rust port a mutation that deleted the `merge_runs` call from the
     * pipeline SURVIVED all four helper tests, because they call the helper directly
     * and the call site was unguarded. That is the gap `se-brain`
     * `rules/standards/testing.md` names: a tested helper does not make its call
     * site safe.
     *
     * Geometry is this port's measured one. Rust's is not reusable: this port blurs
     * the greyscale 5x5 and dilates the mask with reach 2 before the profile, so a
     * source gap under 8 rows never reaches the profile at all.
     */
    @Test
    fun `a bridged diacritic strip comes back joined to its line`() = runBlocking {
        // One stroke bridging an 8-row gap gives a dip row of 16 raw ink columns
        // against a threshold of 16.85. Before the merge this port returned 20 bands
        // for the 10 lines drawn: a 41px strip of glyph tops and a 77px decapitated
        // body each.
        val bands = LineSegmenter.segment(monPage(gap = 8, stems = 1), pageRatio)

        assertEquals("10 lines were drawn; a sub-threshold dip returns 20", 10, bands.size)
        // And each band spans the marks AND the body. A body-only band is 77px.
        for (b in bands) {
            assertTrue("band is ${b.height}px, so it is the body without its marks", b.height > 100)
        }
    }

    /**
     * The merge must run BEFORE the height filter, and this is the only case where
     * the two orders differ - so without it, moving the filter ahead of the merge is
     * a one-line change no test notices.
     *
     * A 1-row source strip becomes a 9-row run, one short of [MIN_LINE_HEIGHT] 10.
     * Measured both ways on this port: filtering first returns 10 bands 77px tall,
     * this order returns 10 bands 93px tall, and the 16px difference IS the strip of
     * upper marks. The COUNT is 10 either way, which is why the assertion is on
     * height.
     */
    @Test
    fun `a strip shorter than the height floor survives, so the order is pinned`() = runBlocking {
        val bands = LineSegmenter.segment(monPage(gap = 8, stems = 0, stripH = 1), pageRatio)

        assertEquals(10, bands.size)
        for (b in bands) {
            assertTrue(
                "band is ${b.height}px; filtering before the merge drops the strip and returns 77",
                b.height > 85
            )
        }
    }

    @Test
    fun `a six row gap is closed by the blur and never reaches the merge`() = runBlocking {
        // The control. Below 8 source rows the 5x5 grey blur and the vertical smear
        // bridge the gap before the profile sees it, so this case never reached
        // `mergeRuns` and must still return the drawn count.
        assertEquals(10, LineSegmenter.segment(monPage(gap = 6, stems = 1), pageRatio).size)
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
