import Foundation

/**
 How to cut a page into lines.

 One global threshold cannot serve every layout. The projection profile's
 threshold is a fraction of the mean row density, so a value that separates lines
 on a dense book page sits below the noise floor of a photograph, and a value
 that works on a poster fuses book lines. Measured upstream 2026-08-15
 (`mon_OCR/docs/LIMITATIONS.md`): a slide holding a six-line Mon poem segmented
 into 3 lines at the low ratio and into all 6, read correctly, at 0.50 — while on
 book pages the low ratio recalls 89.0% of the text against 87.1% at 0.50.

 So the ratio is a parameter and the user can pick the regime. It is NOT switched
 automatically on confidence: the same docs record confidence 0.83 on a complete
 fabrication and 0.00 on a genuinely blank crop, which is the wrong way round.
 */
nonisolated enum SegmentationMode: String, CaseIterable, Identifiable, Sendable {
    /// Book pages, scans and PDF renders: dense text, small gaps.
    case page
    /// Camera photos, posters and slides: wide gaps, noisy background.
    case sparse
    /// The image is already one line. Skip segmentation and tile it.
    case line

    var id: String { rawValue }

    /// Row-density threshold as a fraction of the mean, for the projection
    /// profile, or nil for a mode that never runs one.
    ///
    /// `.line` returns nil rather than a number nobody reads. It returned 0.03,
    /// which was inert because `MonOcrEngine` short-circuits before consulting it,
    /// but it meant the type could not say "this mode never segments" and a caller
    /// that forgot the short-circuit would have got a plausible threshold instead
    /// of a compile error. Android types this `Float?` and the Rust CLI returns
    /// `None`; this port was the only one of the three that did not.
    var densityThresholdRatio: Float? {
        switch self {
        case .page: return 0.03
        case .sparse: return 0.50
        case .line: return nil
        }
    }

    var label: String {
        switch self {
        case .page: return NSLocalizedString("Page", comment: "Segmentation mode")
        case .sparse: return NSLocalizedString("Sparse", comment: "Segmentation mode")
        case .line: return NSLocalizedString("Line", comment: "Segmentation mode")
        }
    }

    var detail: String {
        switch self {
        case .page:
            return NSLocalizedString("Dense text: scans, book pages, PDFs.", comment: "")
        case .sparse:
            return NSLocalizedString("Wide gaps: photos, posters, slides.", comment: "")
        case .line:
            return NSLocalizedString("The whole image is one line of text.", comment: "")
        }
    }
}

/// Where an image came from, which is the only reliable signal about its layout
/// available before reading it.
nonisolated enum ImageProvenance: Sendable {
    case pdfRender
    case cameraCapture
    case photoLibrary

    /// Minimum width-to-height ratio for a crop to be one line.
    ///
    /// The same 4.0 the canonical `looks_like_a_line` uses for the same judgement
    /// (`mon_OCR/src/monocr/segmenter.py:181-215`), and the same
    /// `LINE_MIN_ASPECT` the Rust CLI uses. Reused rather than re-picked: two
    /// constants for one question drift apart, and this ecosystem has a
    /// documented history of exactly that.
    static let lineMinAspect: Double = 4.0

    /**
     The mode to preselect. A default, not a decision — the user can override it,
     and on `.sparse` in particular the response is not monotone (upstream
     measured one photograph giving 5 lines at 0.5, 4 at 0.7 and 1 at 1.3).

     A photo-library image shorter than two model windows *may* be a cropped
     single line, and running a projection profile over one line splits it at its
     own internal gaps.

     **Height alone is not enough, and this used to test height alone.** The CLI's
     fixture set demonstrates the failure: `pdf_screenshot.png` is 876x277, so it
     passes the height test, and at aspect 3.2 it is plainly several lines. Under
     the old rule that image was read as one line and its lines were concatenated
     into a single string. Every genuine single line in that set runs aspect 8.6
     to 18.3, so the two tests agree easily on real input and disagree exactly on
     the case that was wrong.

     Corrected 2026-08-26 to require both, matching `apps/cli/src/mode.rs`.
     */
    func defaultMode(pixelWidth: Int, pixelHeight: Int) -> SegmentationMode {
        switch self {
        case .pdfRender:
            return .page
        case .cameraCapture:
            return .sparse
        case .photoLibrary:
            // No area means no aspect ratio, so there is nothing to judge on.
            // Page is the safe default: it segments, where `.line` would hand a
            // degenerate image to the model whole.
            guard pixelWidth > 0, pixelHeight > 0 else { return .page }

            let shortEnough = pixelHeight < 2 * ModelWindow.height
            let lineShaped = Double(pixelWidth) / Double(pixelHeight) >= Self.lineMinAspect

            // Both must agree. Short and wide is a line; short and squarish is a
            // small block of several lines, and reading it as one concatenates
            // them.
            return shortEnough && lineShaped ? .line : .page
        }
    }
}
