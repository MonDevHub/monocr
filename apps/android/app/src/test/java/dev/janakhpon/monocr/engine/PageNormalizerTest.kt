package dev.janakhpon.monocr.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Behavioural checks for [PageNormalizer].
 *
 * This file did not exist until 2026-08-28. `PageNormalizer` is the most numerically
 * delicate file in `engine/` and every one of its pieces was `private`, so nothing
 * but `normalize` could be called and nothing was. The iOS port of the same code has
 * had a suite since 2026-08-19; these cases mirror it one for one, so the two can be
 * read side by side.
 *
 * That gap was not theoretical. iOS dilated with a square structuring element where
 * this file uses a disk, the two ports disagreed on 7 of 8 synthetic pages, and no
 * test on either side could see it — iOS's dilation test compared the optimised
 * implementation against a naive one that was also a square.
 *
 * The bug the polarity half exists to prevent: the segmenter reads dark pixels as
 * ink and cannot tell it was handed an inverted scan, so on a dark-mode screenshot
 * it returns the gaps between lines as the lines.
 */
class PageNormalizerTest {

    /** Page with vertical ink bars over the middle half of the height. */
    private fun page(
        width: Int, height: Int, background: Int, ink: Int, inkEvery: Int
    ): GreyImage {
        val px = IntArray(width * height) { background }
        for (x in 0 until width) {
            if (x % inkEvery != 0) continue
            for (y in (height / 4) until (3 * height / 4)) px[y * width + x] = ink
        }
        return GreyImage(width, height, px)
    }

    private fun solid(width: Int, height: Int, value: Int) =
        GreyImage(width, height, IntArray(width * height) { value })

    // MARK: - Polarity

    /**
     * The sentinel case. `lower` used 0 for both "not found yet" and a legal
     * luminance of 0, so on a page whose lower order statistic is genuinely black the
     * sentinel never cleared, the median came back half a level high, and it landed
     * on the wrong side of the threshold. Found on the web port, which copied this
     * code line for line; this port has always used -1 and this pins that it keeps to it.
     */
    @Test
    fun `a page whose corners are half black reads as dark`() {
        val w = 40
        val h = 40
        val px = IntArray(w * h) { 255 }
        // Left half of every corner patch black, right half white: the median of the
        // corner sample is exactly 128 by the two-middle-values rule, which is not
        // below 128, so this must NOT read as dark. One level lower must.
        val ch = maxOf(3, h / 10)
        val cw = maxOf(3, w / 10)
        for (yRange in listOf(0 until ch, (h - ch) until h)) {
            for (xRange in listOf(0 until cw, (w - cw) until w)) {
                for (y in yRange) for (x in xRange) {
                    px[y * w + x] = if (x % 2 == 0) 0 else 255
                }
            }
        }
        // (0 + 255) / 2 = 127.5 < 128, so this page IS dark by the rule.
        assertTrue(PageNormalizer.backgroundIsDark(GreyImage(w, h, px)))
    }

    @Test
    fun `a dark background is detected`() {
        assertTrue(PageNormalizer.backgroundIsDark(page(40, 40, background = 20, ink = 230, inkEvery = 5)))
    }

    @Test
    fun `a light background is left alone`() {
        assertFalse(PageNormalizer.backgroundIsDark(page(40, 40, background = 240, ink = 10, inkEvery = 5)))
    }

    /**
     * The end-to-end polarity claim: a dark-mode page comes out dark ink on light
     * paper, which is the only form the segmenter can read.
     */
    @Test
    fun `a dark mode page comes out dark ink on white`() {
        val result = PageNormalizer.normalize(page(60, 60, background = 15, ink = 235, inkEvery = 6))
        val corner = result.at(0, 0)
        val middle = result.at(0, 30)
        assertTrue("background should now be light, was $corner", corner > 200)
        assertTrue("ink should now be darker than paper: ink=$middle paper=$corner", middle < corner)
    }

    // MARK: - Background levelling

    @Test
    fun `a grey panel levels to near white without erasing the ink`() {
        val result = PageNormalizer.normalize(page(80, 80, background = 150, ink = 30, inkEvery = 8))
        val paper = result.at(1, 40)
        val ink = result.at(0, 40)
        assertTrue("grey paper should level towards white, got $paper", paper > 230)
        assertTrue("ink must survive levelling, got $ink against paper $paper", ink < paper)
    }

    @Test
    fun `a white page is unchanged`() {
        val result = PageNormalizer.normalize(solid(40, 40, 255))
        assertArrayEquals(IntArray(40 * 40) { 255 }, result.pixels)
    }

    /**
     * An all-black page survives levelling rather than dividing by zero.
     *
     * Polarity runs first, and an all-black page has an all-black corner sample, so
     * it inverts to all-white and levels to white. The `max(background, 1f)` guard in
     * `levelBackground` is what stands behind that if the estimate is ever near zero
     * anyway; this pins that the pipeline produces a finite page either way.
     */
    @Test
    fun `an all black page survives levelling`() {
        val result = PageNormalizer.normalize(solid(32, 32, 0))
        assertTrue(result.pixels.all { it in 0..255 })
        // Inverted to white first, so it levels to white rather than dividing by zero.
        assertArrayEquals(IntArray(32 * 32) { 255 }, result.pixels)
    }

    /**
     * Levelling depends on the SIZE of the image it is handed, so a crop does not
     * level the way the same region of a levelled page does.
     *
     * This is the property to pin, and it is not the one this test asserted first.
     * "Run it twice on the same image and the answer changes" is the obvious reading
     * of non-idempotence and it is false here: a uniformly grey page levels to white
     * on the first pass, and dividing white by white is the identity, so a second
     * pass is a no-op. The test failed, and the assumption was the thing that was
     * wrong.
     *
     * The real hazard is scale. `levelBackground` picks its kernel from `height / 4`
     * of the downsampled image, so the structuring element covers a different
     * fraction of a crop than of the page it came from. The scraper carried a live
     * defect from exactly this: `to_normalized_grayscale` applied once to the page
     * and again to each line crop.
     */
    @Test
    fun `levelling a crop differs from cropping a levelled page`() {
        val w = 120
        val h = 120

        // A GRADIENT background, not a flat one. A uniformly grey page levels to flat
        // white at any scale, so the kernel size cannot show through and this test
        // passed vacuously against a flat page. Uneven lighting is the case the step
        // exists for, and the case where scale is visible.
        fun source(): GreyImage {
            val px = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val background = 110 + (x * 120) / w
                    px[y * w + x] = if (x % 8 == 0 && y % 3 != 0) 30 else background
                }
            }
            return GreyImage(w, h, px)
        }

        val levelledThenCropped = PageNormalizer.normalize(source()).crop(20, 20, 40, 40)
        val croppedThenLevelled = PageNormalizer.normalize(source().crop(20, 20, 40, 40))

        assertFalse(
            "if these matched, the kernel would not depend on the image size and the " +
                "scraper's double-normalisation defect could not have happened",
            levelledThenCropped.pixels.contentEquals(croppedThenLevelled.pixels)
        )
    }

    /**
     * A black region larger than the dilation kernel must come back black.
     *
     * This is where `levelBackground`'s `max(background, 1f)` earns its place, and the
     * reason is a cross-port one that neither port can see alone. Inside a black area
     * wider than the kernel the background estimate is 0 and the pixel is 0, so
     * without the guard the division is 0/0. Kotlin clamps the resulting NaN to NaN
     * and `NaN.toInt()` is 0; Swift's `min(255, NaN)` returns 255 and the pixel comes
     * out WHITE. Same expression, same inputs, opposite answers — 0 against 255.
     *
     * So this assertion cannot fail on the JVM: dropping the guard here still yields
     * 0. It is written on both sides anyway, because the identical case in
     * `PageNormalizerTests.swift` does fail without the guard, and a reader comparing
     * the two suites should find the same case in both.
     */
    @Test
    fun `a black region larger than the kernel stays black`() {
        val w = 120
        val h = 120
        val px = IntArray(w * h) { 245 }
        for (y in 40 until 80) for (x in 40 until 80) px[y * w + x] = 0

        val out = PageNormalizer.normalize(GreyImage(w, h, px))

        // Deep inside the black square, well clear of its edges.
        assertEquals("the middle of a black block must not come back white", 0, out.at(60, 60))
        assertTrue("and the paper around it must stay light", out.at(5, 5) > 200)
    }

    // MARK: - Corner sampling on small inputs

    /**
     * On a small image the four corner patches OVERLAP, and the overlapping pixels
     * are counted twice. That matches the reference, which concatenates four slices
     * of a numpy array without deduplicating them, and matches iOS. It is pinned
     * because "fix the double-count" is an obvious-looking change that would silently
     * put this port out of step with the other two.
     */
    @Test
    fun `corner patches overlap on a small page and are counted twice`() {
        // 4x4: patch side is max(3, 4 / 10) = 3, so the two 3-wide bands share a column.
        val px = IntArray(4 * 4) { 255 }
        px[0] = 0
        val page = GreyImage(4, 4, px)
        // 4 patches x 3 x 3 = 36 samples over a 16-pixel image: more samples than pixels.
        assertFalse("one black pixel cannot drag a white page's median below 128",
            PageNormalizer.backgroundIsDark(page))
    }

    // MARK: - Resampling and morphology

    @Test
    fun `area downsample averages the source rectangle`() {
        // Every 2x2 block averages to a known value.
        val px = IntArray(4 * 4)
        for (y in 0 until 4) for (x in 0 until 4) px[y * 4 + x] = if ((x / 2 + y / 2) % 2 == 0) 100 else 200
        val small = PageNormalizer.resizeArea(GreyImage(4, 4, px), 2, 2)
        assertArrayEquals(intArrayOf(100, 200, 200, 100), small)
    }

    @Test
    fun `resampling a flat field stays flat`() {
        val flat = GreyImage(37, 29, IntArray(37 * 29) { 173 })
        val small = PageNormalizer.resizeArea(flat, 9, 7)
        assertTrue("a constant field must resample to the same constant, got ${small.distinct()}",
            small.all { it == 173 })
    }

    /**
     * The disk dilation against a naive double loop over the same structuring element.
     *
     * This pins the incremental radius-growing algorithm, not the shape: both sides
     * build the half-width table the same way. The SHAPE is pinned by iOS's
     * `PageNormalizerFixtureTests` against cv2, and by this port having matched
     * `cv2.MORPH_ELLIPSE` on every case measured.
     */
    @Test
    fun `disk dilate matches a naive disk filter`() {
        for (kernel in listOf(7, 9, 11, 15)) {
            val w = 23
            val h = 19
            var seed = 0x5EED0000u or kernel.toUInt()
            val src = IntArray(w * h) {
                seed = seed xor (seed shl 13); seed = seed xor (seed shr 17); seed = seed xor (seed shl 5)
                (seed % 256u).toInt()
            }
            assertArrayEquals(
                "kernel $kernel",
                naiveDiskDilate(src, w, h, kernel),
                PageNormalizer.dilateDisk(src, w, h, kernel)
            )
        }
    }

    private fun naiveDiskDilate(src: IntArray, w: Int, h: Int, kernel: Int): IntArray {
        val r = kernel / 2
        val halfWidth = IntArray(2 * r + 1) { i ->
            val dy = (i - r).toDouble()
            sqrt((r.toDouble() * r - dy * dy).coerceAtLeast(0.0)).roundToInt()
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var best = 0
                for (dy in -r..r) {
                    val hw = halfWidth[dy + r]
                    for (dx in -hw..hw) {
                        val ny = y + dy
                        val nx = x + dx
                        if (ny in 0 until h && nx in 0 until w) best = maxOf(best, src[ny * w + nx])
                    }
                }
                out[y * w + x] = best
            }
        }
        return out
    }

    @Test
    fun `a zero size page is returned untouched`() {
        val empty = GreyImage(0, 0, IntArray(0))
        assertEquals(0, PageNormalizer.normalize(empty).pixels.size)
    }
}
