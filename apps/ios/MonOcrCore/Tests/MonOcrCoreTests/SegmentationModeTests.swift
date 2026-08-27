import Foundation
import Testing

@testable import MonOcrCore

/**
 Which mode an image is preselected into.

 WHY. `photoLibrary` used to decide on pixel height alone: anything shorter than
 two model windows was treated as an already-cropped single line, which skips
 segmentation entirely and tiles the whole image. That is right for a genuine line
 crop and wrong for a small block of several lines, and when it is wrong the lines
 are concatenated into one string with nothing to indicate it happened.

 The Rust CLI had already found the counterexample and written it down
 (`apps/cli/src/mode.rs:23-27`): `pdf_screenshot.png` is 876x277, so it passes the
 height test, and at aspect 3.2 it is plainly several lines. The cases below are
 the same real fixture images that CLI test pins, so the two surfaces agree on the
 same evidence rather than on two separately chosen rules.

 WHAT THIS CANNOT CHECK. Whether the *choice* of mode is right for a given image —
 only that the rule classifies these known dimensions the way the CLI does. The
 upstream measurement behind the thresholds is in `mon_OCR/docs/LIMITATIONS.md`.
 */
struct SegmentationModeTests {

    /// The six real fixture images that are genuinely one line. Aspect runs 8.6
    /// to 18.3, so they clear the 4.0 floor with room to spare — the two tests
    /// agree easily on real input.
    static let genuineLines: [(name: String, width: Int, height: Int)] = [
        ("000028.jpg", 1024, 64),
        ("000029.jpg", 1024, 64),
        ("test_0005_h71.png", 1633, 93),
        ("test_0006_h61.png", 712, 83),
        ("test_0011_h30.png", 711, 51),
        ("test_0012_h86.png", 1995, 109),
    ]

    @Test func aGenuineLineCropIsReadAsOneLine() {
        for image in Self.genuineLines {
            let mode = ImageProvenance.photoLibrary.defaultMode(
                pixelWidth: image.width, pixelHeight: image.height)
            #expect(mode == .line, "\(image.name) (\(image.width)x\(image.height)) is one line")
        }
    }

    /// The regression this rule exists for.
    @Test func aShortBlockOfLinesIsNotReadAsOneLine() {
        let mode = ImageProvenance.photoLibrary.defaultMode(pixelWidth: 876, pixelHeight: 277)
        #expect(
            mode == .page,
            "876x277 passes the height test but its aspect is 3.2, so it is a block of lines")
    }

    /// Height alone must not be sufficient, stated as its own case so that
    /// reverting to the old rule fails a test named for the reason.
    @Test func heightAloneDoesNotDecide() {
        // Same height, either side of the aspect floor.
        let squarish = ImageProvenance.photoLibrary.defaultMode(pixelWidth: 300, pixelHeight: 100)
        let wide = ImageProvenance.photoLibrary.defaultMode(pixelWidth: 400, pixelHeight: 100)
        #expect(squarish == .page, "aspect 3.0 is below the 4.0 floor")
        #expect(wide == .line, "aspect 4.0 meets the floor")
    }

    /// The boundary has to fall on one side, and a 320px strip is more plausibly
    /// two lines than one. Matches the CLI's `the_line_ceiling_is_exclusive`.
    @Test func theHeightCeilingIsExclusive() {
        let atCeiling = ImageProvenance.photoLibrary.defaultMode(
            pixelWidth: 4000, pixelHeight: 2 * ModelWindow.height)
        let underCeiling = ImageProvenance.photoLibrary.defaultMode(
            pixelWidth: 4000, pixelHeight: 2 * ModelWindow.height - 1)
        #expect(atCeiling == .page, "exactly 2x the model height is a page")
        #expect(underCeiling == .line, "one pixel under is a line")
    }

    /// A zero dimension has no aspect ratio to judge on. Page segments; `.line`
    /// would hand a degenerate image to the model whole.
    @Test func aDegenerateImageFallsBackToPage() {
        #expect(ImageProvenance.photoLibrary.defaultMode(pixelWidth: 0, pixelHeight: 100) == .page)
        #expect(ImageProvenance.photoLibrary.defaultMode(pixelWidth: 100, pixelHeight: 0) == .page)
        #expect(ImageProvenance.photoLibrary.defaultMode(pixelWidth: 0, pixelHeight: 0) == .page)
    }

    /// Provenance still dominates: a PDF render is known to be a page, and a
    /// camera capture is preselected sparse regardless of shape.
    @Test func provenanceDecidesBeforeShape() {
        // Dimensions that would read as a single line from the photo library.
        #expect(ImageProvenance.pdfRender.defaultMode(pixelWidth: 1024, pixelHeight: 64) == .page)
        #expect(
            ImageProvenance.cameraCapture.defaultMode(pixelWidth: 1024, pixelHeight: 64) == .sparse)
    }

    /// One constant for one question. The CLI uses 4.0 for the same judgement,
    /// taken from the canonical `looks_like_a_line`.
    @Test func theAspectFloorMatchesTheCanonicalOne() {
        #expect(ImageProvenance.lineMinAspect == 4.0)
    }
}
