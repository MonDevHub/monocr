package dev.janakhpon.monocr.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which mode a gallery image defaults to.
 *
 * WHY. `galleryModeFor` decided on pixel height alone: shorter than two model
 * windows meant "already a cropped line", which skips segmentation and tiles the
 * whole image. Right for a genuine line crop, wrong for a small block of several
 * lines — and when it is wrong the lines are concatenated into one string.
 *
 * The rule was a private function inside a Compose screen, so nothing in
 * `src/test` could reach it. That is why the bug survived here while
 * `apps/cli/src/mode.rs` carried six tests for the same decision and one of them
 * named the exact counterexample. Moving the rule onto `SegmentationMode` was as
 * much of the fix as changing it.
 *
 * The cases are the same real fixture images `mode.rs` pins, so Android, iOS and
 * the CLI answer to one set of evidence rather than three separately chosen rules.
 */
class SegmentationModeTest {

    /**
     * The six real fixture images that are genuinely one line. Aspect runs 8.6 to
     * 18.3, so they clear the 4.0 floor with room to spare — the two tests agree
     * easily on real input and disagree only where the old rule was wrong.
     */
    private val genuineLines = listOf(
        Triple("000028.jpg", 1024, 64),
        Triple("000029.jpg", 1024, 64),
        Triple("test_0005_h71.png", 1633, 93),
        Triple("test_0006_h61.png", 712, 83),
        Triple("test_0011_h30.png", 711, 51),
        Triple("test_0012_h86.png", 1995, 109),
    )

    @Test
    fun `a genuine line crop is read as one line`() {
        for ((name, w, h) in genuineLines) {
            assertEquals(
                "$name (${w}x$h) is a single line",
                SegmentationMode.LINE,
                SegmentationMode.forGalleryImage(w, h),
            )
        }
    }

    /** The regression this rule exists for. */
    @Test
    fun `a short block of lines is not read as one line`() {
        assertEquals(
            "876x277 clears the height ceiling but its aspect is 3.2, so it is a block",
            SegmentationMode.PAGE,
            SegmentationMode.forGalleryImage(876, 277),
        )
    }

    /**
     * Height alone must not decide, stated as its own case so that reverting to the
     * old rule fails a test named for the reason.
     */
    @Test
    fun `height alone does not decide`() {
        assertEquals(
            "aspect 3.0 is below the 4.0 floor",
            SegmentationMode.PAGE,
            SegmentationMode.forGalleryImage(300, 100),
        )
        assertEquals(
            "aspect 4.0 meets the floor",
            SegmentationMode.LINE,
            SegmentationMode.forGalleryImage(400, 100),
        )
    }

    /**
     * The boundary falls on one side, and a 320px strip is more plausibly two lines
     * than one. Matches `mode.rs::the_line_ceiling_is_exclusive`.
     */
    @Test
    fun `the height ceiling is exclusive`() {
        val ceiling = 2 * ImagePreprocessor.TARGET_HEIGHT
        assertEquals(
            "exactly 2x the model height is a page",
            SegmentationMode.PAGE,
            SegmentationMode.forGalleryImage(4000, ceiling),
        )
        assertEquals(
            "one pixel under is a line",
            SegmentationMode.LINE,
            SegmentationMode.forGalleryImage(4000, ceiling - 1),
        )
    }

    /**
     * No area means no aspect ratio to judge on. PAGE segments; LINE would hand a
     * degenerate image to the model whole.
     */
    @Test
    fun `a degenerate image falls back to page`() {
        assertEquals(SegmentationMode.PAGE, SegmentationMode.forGalleryImage(0, 100))
        assertEquals(SegmentationMode.PAGE, SegmentationMode.forGalleryImage(100, 0))
        assertEquals(SegmentationMode.PAGE, SegmentationMode.forGalleryImage(0, 0))
    }

    /**
     * One constant for one question. The aspect floor is read from `LineSegmenter`
     * rather than redeclared, because two constants for one judgement drift apart
     * and this ecosystem has a documented history of exactly that.
     */
    @Test
    fun `the aspect floor is the canonical one`() {
        assertEquals(4.0f, LineSegmenter.LINE_SHAPE_ASPECT)
    }
}
