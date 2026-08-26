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
    LINE(null);

    companion object {
        /**
         * Which mode a gallery image should default to, from its dimensions alone.
         *
         * **Height alone is not enough, and this used to test height alone.** Anything
         * shorter than two model windows was treated as an already-cropped line, which
         * skips segmentation and tiles the whole image. Right for a genuine line crop,
         * wrong for a small block of several lines — and when it is wrong the lines are
         * concatenated into one string.
         *
         * The Rust CLI found the counterexample first and wrote it down
         * (`apps/cli/src/mode.rs:23-27`): `pdf_screenshot.png` is 876x277, so it clears
         * the height ceiling, and at aspect 3.2 it is plainly several lines. Every
         * genuine single line in that fixture set runs aspect 8.6 to 18.3, so the two
         * tests agree easily on real input and disagree exactly on the case that was
         * wrong.
         *
         * Lives here rather than in `HomeScreen` so it can be tested at all: as a
         * private function in a Compose screen it was unreachable from `src/test`,
         * which is why the bug survived while `mode.rs` had six tests for the same
         * decision. Corrected 2026-08-26.
         */
        fun forGalleryImage(pixelWidth: Int, pixelHeight: Int): SegmentationMode {
            // No area means no aspect ratio to judge on. PAGE is the safe default: it
            // segments, where LINE would hand a degenerate image to the model whole.
            if (pixelWidth <= 0 || pixelHeight <= 0) return PAGE

            val shortEnough = pixelHeight < 2 * ImagePreprocessor.TARGET_HEIGHT
            val lineShaped =
                pixelWidth.toFloat() / pixelHeight >= LineSegmenter.LINE_SHAPE_ASPECT

            // Both must agree. Short and wide is a line; short and squarish is a small
            // block of several lines, and reading it as one concatenates them.
            return if (shortEnough && lineShaped) LINE else PAGE
        }
    }
}
