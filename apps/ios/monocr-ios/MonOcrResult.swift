import Foundation
import UIKit

/**
 Data model for MonOCR results.

 `RecognizedLine` used to be defined in this file; it moved to its own file
 (`RecognizedLine.swift`) because it is Foundation-only and this struct is not
 — `debugImage: UIImage?` below means this file cannot be symlinked into
 `MonOcrCore`, which also builds for macOS, where UIKit does not exist.
 */
nonisolated struct MonOcrResult {
    /// The extracted text
    let text: String
    let rawText: String
    let pages: [PageOutcome]
    let cancelled: Bool

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

    var reviewMetadata: OcrReviewMetadata {
        OcrReviewMetadata(modelVersion: MonOcrEngine.MODEL_VERSION, pages: pages,
                          lines: lines, looksSoft: looksSoft, cancelled: cancelled)
    }

    var warningSummary: String? { reviewMetadata.warningSummary }

    init(text: String, wordCount: Int, charCount: Int, durationMs: Int,
         debugImage: UIImage?, lines: [RecognizedLine], mode: SegmentationMode,
         looksSoft: Bool, rawText: String? = nil, pages: [PageOutcome]? = nil,
         cancelled: Bool = false) {
        self.text = text
        self.rawText = rawText ?? text
        self.wordCount = wordCount
        self.charCount = charCount
        self.durationMs = durationMs
        self.debugImage = debugImage
        self.lines = lines
        self.mode = mode
        self.looksSoft = looksSoft
        self.cancelled = cancelled
        self.pages = pages ?? [.recognition(pageIndex: 0, lines: lines)]
    }
}
