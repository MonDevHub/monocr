package dev.janakhpon.monocr.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LineSegmenter.suppressPageRules].
 *
 * The same seven cases exist in `apps/ios/MonOcrCore/Tests/MonOcrCoreTests/RuleSuppressionTests.swift`
 * and cover the same three constants. Written as a matching pair on purpose: the
 * defect this whole pass exists to stop is two ports of one algorithm drifting
 * apart unnoticed, and a divergence is far easier to see in two test files that
 * are meant to read identically than in two implementations that are not.
 *
 * These run against the binary mask directly rather than through [LineSegmenter.segment],
 * so a failure names the run-length scan instead of the adaptive threshold that
 * feeds it. The one end-to-end case at the bottom is the product property.
 */
class RuleSuppressionTest {

    /** Row-major mask, `true` where there is ink. */
    private fun mask(width: Int, height: Int, paint: (Int, Int) -> Boolean): BooleanArray {
        val m = BooleanArray(width * height)
        for (y in 0 until height) for (x in 0 until width) m[y * width + x] = paint(x, y)
        return m
    }

    private fun inkCount(m: BooleanArray): Int = m.count { it }

    // MARK: - Nothing to do

    /**
     * The property that makes this pass safe to run on every page unconditionally:
     * a page carrying no rules comes back identical to the pixel, so the step
     * cannot cost anything on the pages it was not written for.
     */
    @Test
    fun `a page with no rules is untouched to the pixel`() {
        // Glyph-scale strokes: runs of 2, nowhere near the 50px span this page requires.
        val m = mask(100, 100) { x, y -> y in 30..32 && x < 40 && x % 4 < 2 }
        val before = m.copyOf()

        assertFalse("nothing spans half the page, so nothing should be removed",
            LineSegmenter.suppressPageRules(m, 100, 100))
        assertArrayEquals(before, m)
    }

    /**
     * An empty mask has no ink to measure a share against, so it returns early.
     *
     * The second assertion used to be `assertEquals(0, inkCount(m))` on a mask
     * constructed all-false, passed to a function whose only write sets a pixel
     * FALSE. It could not fail under any mutation. Asserting the mask is unchanged
     * is the same intent and can: a mutation writing `true` anywhere is caught.
     */
    @Test
    fun `a blank page is refused before the ink share is computed`() {
        val m = BooleanArray(100 * 100)
        val before = m.copyOf()
        assertFalse(LineSegmenter.suppressPageRules(m, 100, 100))
        assertArrayEquals(before, m)
    }

    // MARK: - Rules found

    @Test
    fun `a full width horizontal rule is removed and the text is kept`() {
        // minH = max(15, 100 * 0.5) = 50. The border row is a run of 100; the text
        // rows are runs of 2.
        val m = mask(100, 100) { x, y ->
            y == 10 || (y in 30..32 && x < 40 && x % 4 < 2)
        }
        val textInkBefore = inkCount(m) - 100

        assertTrue(LineSegmenter.suppressPageRules(m, 100, 100))

        for (x in 0 until 100) {
            assertFalse("the rule at row 10 should be gone at x=$x", m[10 * 100 + x])
        }
        assertEquals("no text pixel may be removed with it", textInkBefore, inkCount(m))
    }

    @Test
    fun `a full height vertical rule is removed`() {
        // The margin rule of a ruled notebook, or the left edge of a table.
        val m = mask(100, 100) { x, y ->
            x == 10 || (y in 30..32 && x in 40..79 && x % 4 < 2)
        }
        val textInkBefore = inkCount(m) - 100

        assertTrue(LineSegmenter.suppressPageRules(m, 100, 100))

        for (y in 0 until 100) {
            assertFalse("the rule at column 10 should be gone at y=$y", m[y * 100 + 10])
        }
        assertEquals(textInkBefore, inkCount(m))
    }

    // MARK: - The two guards

    /**
     * [LineSegmenter.RULE_MAX_INK_SHARE]. When the scan classifies most of the page's
     * ink as rules it has found the text, and emptying the page is strictly worse
     * than leaving it alone.
     */
    @Test
    fun `suppression that would eat the page is abandoned`() {
        // 80 solid rows: every row is a 100px run and every column an 80px run, so
        // the scan claims 100% of the ink.
        val m = mask(100, 100) { _, y -> y < 80 }
        val before = m.copyOf()

        assertFalse("100 percent is over the 80 percent ceiling",
            LineSegmenter.suppressPageRules(m, 100, 100))
        assertArrayEquals("an abandoned pass must not have partially applied", before, m)
    }

    /**
     * The 15px floor under the span. Without it a small crop would use a span of
     * `width / 2`, which on a 20px-wide crop is 10px — inside glyph range.
     */
    @Test
    fun `the span has a floor of fifteen pixels on small crops`() {
        // 20 wide, so width * 0.5 = 10 and the floor of 15 is what governs.
        val fifteen = mask(20, 20) { x, y ->
            (y == 5 && x < 15) || (y in 10..12 && x < 8 && x % 4 < 2)
        }
        assertTrue("a run of exactly 15 reaches the floor",
            LineSegmenter.suppressPageRules(fifteen, 20, 20))
        for (x in 0 until 15) assertFalse(fifteen[5 * 20 + x])

        val fourteen = mask(20, 20) { x, y ->
            (y == 5 && x < 14) || (y in 10..12 && x < 8 && x % 4 < 2)
        }
        val before = fourteen.copyOf()
        assertFalse("a run of 14 is one short and must survive",
            LineSegmenter.suppressPageRules(fourteen, 20, 20))
        assertArrayEquals(before, fourteen)
    }

    /**
     * The same boundary on the vertical axis.
     *
     * Added after a mutation run: flipping `>=` to `>` in the vertical scan survived
     * the whole suite, because the vertical case above uses a full-height column and
     * a run of 100 clears a span of 50 either way. Only an exact-length run pins it.
     */
    @Test
    fun `the vertical span boundary is exact`() {
        // Scattered single pixels keep the ink share under the ceiling without
        // forming a run of their own on either axis.
        fun withColumnRun(rows: Int): BooleanArray = mask(20, 20) { x, y ->
            (x == 5 && y < rows) || (x in 10..13 && (x + y) % 2 == 0)
        }

        val fifteen = withColumnRun(15)
        assertTrue("a column run of exactly 15 reaches the floor",
            LineSegmenter.suppressPageRules(fifteen, 20, 20))
        for (y in 0 until 15) assertFalse(fifteen[y * 20 + 5])

        val fourteen = withColumnRun(14)
        val before = fourteen.copyOf()
        assertFalse("a column run of 14 is one short and must survive",
            LineSegmenter.suppressPageRules(fourteen, 20, 20))
        assertArrayEquals(before, fourteen)
    }

    // MARK: - The product property

    /**
     * What the whole pass is for: a printed frame around a page must stop changing
     * how many lines come out of it.
     *
     * A page border adds a constant ink floor to every row it spans. Once that floor
     * clears the gap threshold no row inside the frame reads as a gap, the page
     * returns as one band, and that band is squeezed into the model window and read
     * as a sentence that is not on the page. Upstream, nine of twelve real MNEC
     * papers collapsed this way.
     *
     * Asserted as an equality against the same page unframed rather than as "more
     * than one band", because that is the actual claim — the frame is not supposed
     * to be visible in the result at all.
     */
    @Test
    fun `a printed frame does not change the reading`() = runBlocking {
        val ratio = SegmentationMode.PAGE.densityThresholdRatio!!
        val w = 300
        val h = 200
        val stripes = listOf(40..52, 90..102, 140..152)

        // Words with gaps, not a solid ribbon of ink. The first version of this
        // fixture drew each stripe as one unbroken 260px band, which on a 300px page
        // is longer than the 150px span - so the scan classified the TEXT as rules,
        // RULE_MAX_INK_SHARE fired at 90.7%, and the pass abandoned. That is the
        // guard behaving exactly as designed, and it made the fixture prove nothing.
        // Real text is broken by word gaps and cannot form runs that long.
        fun page(framed: Boolean): GreyImage {
            val px = IntArray(w * h) { 255 }
            for (stripe in stripes) {
                for (y in stripe) for (x in 20 until w - 20) {
                    val insideAWord = (x - 20) % 45 < 30
                    if (insideAWord && x % 4 < 2) px[y * w + x] = 0
                }
            }
            if (framed) {
                for (x in 0 until w) { px[x] = 0; px[(h - 1) * w + x] = 0 }
                for (y in 0 until h) { px[y * w] = 0; px[y * w + w - 1] = 0 }
            }
            return GreyImage(w, h, px)
        }

        fun geometry(bands: List<LineSegment>) = bands.map { "${it.x},${it.y},${it.width},${it.height}" }

        val plain = LineSegmenter.segment(page(framed = false), ratio)
        val framed = LineSegmenter.segment(page(framed = true), ratio)

        assertEquals("the unframed page is the control and must find its stripes",
            stripes.size, plain.size)

        // Band-for-band, not just the count. A frame that shifted every box by a
        // pixel would pass a count check and still be visible in every crop.
        assertEquals(
            "the frame must not be visible in the result at all",
            geometry(plain), geometry(framed)
        )
    }
}
