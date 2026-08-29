package dev.janakhpon.monocr.engine

/**
 * How sharp a captured page is, before anything tries to read it.
 *
 * **Why.** The app advertises a quality bar and, on this platform, has never checked
 * it. A soft capture is only diagnosable from bad Mon text, which is the least useful
 * signal available: a blurred photograph does not fail, it returns confident nonsense.
 *
 * It matters more here than a general nicety, for a reason specific to this pipeline.
 * [LineSegmenter]'s tuning constants are absolute pixel counts — blur kernel, adaptive
 * threshold window, smear kernels, smoothing width, minimum line height — and none of
 * them scales with the resolution of the input. The pipeline is tuned for one text
 * size and nothing said when a capture was nowhere near it.
 *
 * Ported from `apps/ios/monocr-ios/CaptureQuality.swift`, itself ported from
 * `apps/web/src/lib/capture-quality.ts`, so the three surfaces report the same
 * quantity. Android was the only one of the three with no such check at all; web
 * computes it and logs it inside its worker, and iOS surfaces it to the user.
 *
 * **What this cannot do.** Report DPI: a bitmap carries no physical scale. And the
 * floor is a heuristic, not a measurement — see [SOFT_IMAGE_LAPLACIAN_VARIANCE].
 */
object CaptureQuality {

    /**
     * Laplacian-variance floor below which an image reads as soft.
     *
     * **A heuristic, not a measurement.** ~100 is the common rule of thumb for 8-bit
     * grey and it has been calibrated against nothing in this project. It is set low
     * deliberately so a warning is rare and therefore worth reading.
     *
     * Calibrating it needs the real-photograph set `mon_OCR/docs/DATA_STRATEGY.md`
     * calls rung D1, which does not exist yet. Until then this should warn, never
     * gate. Same value as the other two ports so all three agree on "soft".
     */
    const val SOFT_IMAGE_LAPLACIAN_VARIANCE = 100.0

    /**
     * Variance of the 4-neighbour Laplacian over the image interior. Higher is sharper.
     *
     * A Laplacian is a second-derivative filter, so it responds to edges; a soft image
     * has few strong edges and its response has little spread. Interior only, because
     * the kernel needs all four neighbours and clamping at the border would manufacture
     * edges that are not in the image.
     *
     * Returns 0 for anything too small to hold the kernel rather than throwing: a 3x3
     * minimum is a property of the filter, not a caller error.
     */
    fun laplacianVariance(page: GreyImage): Double {
        if (page.width < 3 || page.height < 3) return 0.0

        // The naive single-pass form, E[x^2] - E[x]^2. That is the numerically
        // unstable one in general, and it is safe here for a specific reason: a
        // discrete Laplacian over the interior telescopes to boundary terms, so the
        // mean is ~0 for any real image and sumSq/n dominates mean^2 by orders of
        // magnitude. No cancellation to lose.
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        val px = page.pixels
        val w = page.width
        for (y in 1 until page.height - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val response = (px[i - w] + px[i + w] + px[i - 1] + px[i + 1] - 4 * px[i]).toDouble()
                sum += response
                sumSq += response * response
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /**
     * True when the page is soft enough to be worth telling the user about.
     *
     * Mon diacritics are thin and stack above and below the line, so they are what
     * blur removes first, which is why softness is worth surfacing on this script
     * specifically rather than treated as a generic photography tip.
     */
    fun isSoft(page: GreyImage): Boolean =
        laplacianVariance(page) < SOFT_IMAGE_LAPLACIAN_VARIANCE
}
