package dev.janakhpon.monocr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Sharpness measurement.
 *
 * The analytic case is the load-bearing one: it pins the kernel COEFFICIENT, not just
 * the ordering of two images. A test that only asserts "sharp scores higher than soft"
 * passes with the wrong coefficient, which is how the same test on the web port
 * survived a mutation from -4 to -3 until it was tightened.
 *
 * These mirror `apps/ios/MonOcrCore/Tests/MonOcrCoreTests/CaptureQualityTests.swift`
 * case for case and use the same derived values, so the three ports are pinned to one
 * derivation rather than three.
 *
 * Two mutations of [CaptureQuality] survive this suite and are not oversights:
 *
 *  - Relaxing the 3x3 minimum to 2x2 changes nothing observable. The loops already
 *    run `1 until size - 1`, so a 2-pixel axis yields no samples and the function
 *    returns 0 through the `n == 0` path instead of the guard. The guard states the
 *    filter's requirement rather than implementing it.
 *  - `isSoft`'s `<` against `<=` differs only where the variance is exactly the
 *    floor. `20 * d^2 / 9` is the variance of the 5x5 family used above and no
 *    integer `d` makes it 100, so there is no cheap image that lands on the
 *    boundary; the floor is a documented heuristic rather than a measured value, so
 *    contriving one would pin a number that is meant to move.
 */
class CaptureQualityTest {

    /** White page with dark bars in one row band, [pitch] apart. */
    private fun page(
        width: Int, height: Int, band: IntRange?, pitch: Int = 8, ink: Int = 0
    ): GreyImage {
        val px = IntArray(width * height) { 255 }
        if (band != null) {
            for (y in band) {
                var x = 20
                while (x < width - 20) {
                    px[y * width + x] = ink
                    x += pitch
                }
            }
        }
        return GreyImage(width, height, px)
    }

    /**
     * The analytic case, derived by hand and reproduced independently on two ports.
     *
     * 5x5, all 255, centre 100. A grey centre is required: a black centre gives the
     * same answer under -4 and -3 because it is multiplied by zero either way, so it
     * cannot pin the coefficient.
     *
     * Interior is 3x3 = 9 samples. The centre responds 4*255 - 4*100 = 620; its four
     * edge-neighbours each 3*255 + 100 - 4*255 = -155; the four interior corners 0.
     * The sum is 620 - 620 = 0 exactly, so the mean is 0 and the variance is the mean
     * square: (620^2 + 4*155^2)/9 = 480500/9.
     */
    @Test
    fun `matches the analytic response of a known kernel`() {
        val px = IntArray(25) { 255 }
        px[2 * 5 + 2] = 100
        val variance = CaptureQuality.laplacianVariance(GreyImage(5, 5, px))
        assertTrue(
            "expected 480500/9, got $variance",
            abs(variance - 480500.0 / 9.0) < 1e-9
        )
    }

    @Test
    fun `a flat field has no edges`() {
        assertEquals(0.0, CaptureQuality.laplacianVariance(page(60, 60, null)), 0.0)
    }

    @Test
    fun `a hard edged page scores above the soft floor`() {
        val sharp = CaptureQuality.laplacianVariance(page(200, 200, 80 until 120))
        assertTrue(
            "a page of hard bars should not read as soft, got $sharp",
            sharp > CaptureQuality.SOFT_IMAGE_LAPLACIAN_VARIANCE
        )
    }

    /** Same content, half the step at every edge: no edge moves, only its contrast. */
    @Test
    fun `softening the same content lowers the score`() {
        val hard = CaptureQuality.laplacianVariance(page(200, 200, 80 until 120))
        val soft = CaptureQuality.laplacianVariance(page(200, 200, 80 until 120, ink = 128))
        assertTrue("soft=$soft should be under hard=$hard", soft < hard)
    }

    @Test
    fun `an image too small for the kernel scores zero rather than throwing`() {
        assertEquals(0.0, CaptureQuality.laplacianVariance(page(2, 2, null)), 0.0)
        assertEquals(0.0, CaptureQuality.laplacianVariance(page(1, 40, null)), 0.0)
        assertEquals(0.0, CaptureQuality.laplacianVariance(GreyImage(0, 0, IntArray(0))), 0.0)
    }

    @Test
    fun `softness is reported against the floor`() {
        assertTrue(CaptureQuality.isSoft(page(120, 120, null)))
        assertFalse(CaptureQuality.isSoft(page(200, 200, 80 until 120)))
    }

    /**
     * A constant response has zero variance even though its mean is large.
     *
     * This is the case that separates the variance from the mean square, and the
     * only one that does. Every other image here has a near-zero Laplacian mean —
     * the filter telescopes to boundary terms over the interior, which is exactly
     * why the single-pass `E[x^2] - E[x]^2` form is safe in this function. The cost
     * of that is that dropping the `- mean * mean` term changes almost nothing, and
     * a mutation doing so survived the whole suite.
     *
     * A quadratic ramp is the exception. `p(x) = 2x^2` has an exact second
     * difference of 4, so every interior pixel responds 4: the mean is 4 and the
     * spread is nothing. The variance is 0 and the mean square is 16.
     */
    @Test
    fun `a constant response has zero variance and a non-zero mean`() {
        val w = 11
        val h = 5
        // 2x^2 over 0..10 peaks at 200, inside the 8-bit range, and every row is
        // identical so the vertical term contributes nothing.
        val px = IntArray(w * h) { 2 * (it % w) * (it % w) }
        val variance = CaptureQuality.laplacianVariance(GreyImage(w, h, px))
        assertTrue(
            "a constant response must have no spread; got $variance, and 16.0 would " +
                "mean the mean was not subtracted",
            abs(variance) < 1e-9
        )
    }

    /** One value for one question, across all three ports. */
    @Test
    fun `the floor matches the other ports`() {
        assertEquals(100.0, CaptureQuality.SOFT_IMAGE_LAPLACIAN_VARIANCE, 0.0)
    }
}
