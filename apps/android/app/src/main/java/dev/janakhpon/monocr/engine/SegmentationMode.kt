package dev.janakhpon.monocr.engine

/**
 * How to split an image into lines before reading it.
 *
 * The projection profile uses one global threshold as a fraction of the mean row
 * density, and no single fraction works on both regimes. A threshold that separates
 * lines on a clean book scan sits below the noise floor of a photograph; a threshold
 * that works on a poster fuses dense book lines. Measured 2026-08-15 upstream
 * (mon_OCR docs/LIMITATIONS.md): a slide holding a six-line Mon poem segmented into
 * 3 lines at the low ratio and into all 6, read correctly, at 0.50. The response is
 * not monotone either — on one photograph 0.5 gave 5 lines, 0.7 gave 4 and 1.3 gave 1.
 *
 * So this is a choice, made from where the image came from and then handed to the
 * user, not something to infer from the model's own output. Confidence cannot
 * arbitrate it: the same docs record 0.83 confidence on a fabricated reading of a
 * fused block and 0.00 on a genuinely blank crop, which is the wrong way round.
 */
enum class SegmentationMode(
    /**
     * Valley threshold as a fraction of mean row density, or null to skip
     * segmentation entirely.
     */
    val densityThresholdRatio: Float?
) {
    /** Dense text: scanned or born-digital book pages, and PDF renders. */
    PAGE(0.03f),

    /** Wide-spaced layouts: camera photos of slides, posters and signs. */
    SPARSE(0.50f),

    /** The image is already one line. Skip segmentation and tile the whole thing. */
    LINE(null)
}
