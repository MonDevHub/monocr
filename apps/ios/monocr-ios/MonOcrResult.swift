import Foundation
import UIKit

/**
 One line of a page, as read.

 `looksLikeALine` is carried per line rather than folded into the text because a
 band that is really a fused block still returns fluent Mon — measured upstream
 at confidence 0.83 for text that appears nowhere on the page. The reading is
 kept and flagged; nothing is dropped on the caller's behalf.
 */
struct RecognizedLine {
    let text: String

    /// Page pixel coordinates of the band this text came from.
    let bbox: LineSegment

    /// How many model windows this line was split into. More than one means the
    /// line was tiled instead of squeezed.
    let tileCount: Int

    /// False when the band is shaped like a block of text, not a single line.
    let looksLikeALine: Bool
}

/**
 Data model for MonOCR results.
 */
struct MonOcrResult {
    /// The extracted text
    let text: String

    /// Estimated word count
    let wordCount: Int

    /// Total character count
    let charCount: Int

    /// Total duration in milliseconds
    let durationMs: Int

    /// Preprocessed image used for debugging (engine input)
    let debugImage: UIImage?

    /// Per-line detail, in reading order.
    let lines: [RecognizedLine]

    /// The segmentation mode that produced this result.
    let mode: SegmentationMode

    /// The page was too soft to read confidently.
    ///
    /// `CaptureQuality` has computed this since 2026-08-19 and nothing called it:
    /// the check and its seven tests existed, and no user was ever told. A blurred
    /// photograph does not fail, it returns confident nonsense, so the check is
    /// only worth having if it reaches the person holding the camera.
    let looksSoft: Bool

    /// Bands that are shaped like blocks rather than lines. Their text may be
    /// invented, so it is worth telling the user before they trust it.
    var unreliableLines: [RecognizedLine] {
        lines.filter { !$0.looksLikeALine }
    }
}
