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
    /// profile. Unused by `.line`, which never runs the profile.
    var densityThresholdRatio: Float {
        switch self {
        case .page: return 0.03
        case .sparse: return 0.50
        case .line: return 0.03
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

    /**
     The mode to preselect. A default, not a decision — the user can override it,
     and on `.sparse` in particular the response is not monotone (upstream
     measured one photograph giving 5 lines at 0.5, 4 at 0.7 and 1 at 1.3).

     A photo-library image shorter than two model windows is almost always a
     cropped single line, and running a projection profile over one line splits
     it at its own internal gaps.
     */
    func defaultMode(pixelHeight: Int) -> SegmentationMode {
        switch self {
        case .pdfRender:
            return .page
        case .cameraCapture:
            return .sparse
        case .photoLibrary:
            return pixelHeight < 2 * ModelWindow.height ? .line : .page
        }
    }
}
