import Foundation

/// An error that every tile would hit alike, because the engine or the model itself
/// is unusable. Marking it on one tile and reading on would turn a broken engine into
/// a page of failed lines that is then saved and announced as a result.
nonisolated protocol EngineWideError: Error {
    var isEngineWide: Bool { get }
}

nonisolated enum TileFailurePolicy {
    /// True when a tile's error has to stop the whole recognition instead of being
    /// recorded against that tile.
    static func isEngineWide(_ error: Error) -> Bool {
        // The charset and the model disagree, which no other tile can avoid.
        if error is ModelContractError { return true }
        return (error as? EngineWideError)?.isEngineWide ?? false
    }
}

nonisolated struct TileReading: Codable {
    enum State: String, Codable { case text, empty, failed }
    let index: Int
    let bbox: LineSegment
    let state: State
    let rawText: String
    let error: String?

    static func decoded(index: Int, bbox: LineSegment, rawText: String) -> TileReading {
        TileReading(index: index, bbox: bbox, state: rawText.isEmpty ? .empty : .text,
                    rawText: rawText, error: nil)
    }

    static func failed(index: Int, bbox: LineSegment, error: String) -> TileReading {
        TileReading(index: index, bbox: bbox, state: .failed, rawText: "", error: error)
    }
}

nonisolated enum TileAssembly {
    struct Result {
        let rawText: String
        let text: String
        let reviewReasons: [String]
    }

    static func assemble(_ tiles: [TileReading]) -> Result {
        let ordered = tiles.sorted { $0.index < $1.index }
        // Never manufacture a continuous line across a failed crop. Keep all
        // fragments in the typed tile records for review, including valid ones.
        if ordered.contains(where: { $0.state == .failed }) {
            return Result(rawText: "", text: "", reviewReasons: ["tile_failed"])
        }
        let raw = ordered.map(\.rawText).joined()
        let textIndices = ordered.indices.filter { ordered[$0].state == .text }
        var reasons = [String]()
        if let first = textIndices.first, let last = textIndices.last, first < last,
           ordered[first...last].contains(where: { $0.state == .empty }) {
            // An empty prediction does not establish whitespace; leave the
            // existing join policy intact and expose the uncertain seam.
            reasons.append("empty_tile_between_text")
        }
        return Result(rawText: raw, text: raw, reviewReasons: reasons)
    }
}

nonisolated struct PageOutcome: Codable {
    enum State: String, Codable, CaseIterable {
        case notAttempted, completed, partial, renderFailed, inferenceFailed, cancelled
        case emptyUnverified, noRegionsDetected
    }
    let pageIndex: Int
    let state: State
    let error: String?

    /// Recognition ran on this page and returned, whether or not it read text.
    var wasRead: Bool {
        switch state {
        case .completed, .partial, .emptyUnverified, .noRegionsDetected: return true
        case .notAttempted, .renderFailed, .inferenceFailed, .cancelled: return false
        }
    }

    /// Every page failed to render or to be read. That is a failed scan to report
    /// as an error, not a result to save or to confirm with a success haptic.
    static func noPageRead(_ pages: [PageOutcome]) -> Bool {
        !pages.isEmpty && pages.allSatisfy { $0.state == .renderFailed || $0.state == .inferenceFailed }
    }

    static func recognition(pageIndex: Int, lines: [RecognizedLine]) -> PageOutcome {
        let failed = lines.filter { $0.reviewReasons.contains("tile_failed") }.count
        if failed == 0 {
            let state: State
            if lines.contains(where: { $0.reviewReasons.contains("no_regions_detected") }) {
                state = .noRegionsDetected
            } else if lines.allSatisfy({ $0.text.isEmpty }) {
                state = .emptyUnverified
            } else { state = .completed }
            return PageOutcome(pageIndex: pageIndex, state: state, error: nil)
        }
        if failed == lines.count {
            return PageOutcome(pageIndex: pageIndex, state: .inferenceFailed, error: "No detected line could be read completely.")
        }
        return PageOutcome(pageIndex: pageIndex, state: .partial, error: nil)
    }
}

/// Additive, versioned history metadata. Older rows have no metadata.
nonisolated struct OcrReviewMetadata: Codable {
    var schemaVersion = 1
    let modelVersion: String
    let pages: [PageOutcome]
    let lines: [RecognizedLine]
    let looksSoft: Bool
    let cancelled: Bool

    var warningSummary: String? {
        var warnings = [String]()
        let failedPages = pages.filter { $0.state == .renderFailed || $0.state == .inferenceFailed }
        if !failedPages.isEmpty {
            warnings.append("Pages could not be read: " + failedPages.map { String($0.pageIndex + 1) }.joined(separator: ", ") + ".")
        }
        if cancelled { warnings.append("Processing was cancelled; this reading is incomplete.") }
        let unfinished = pages.filter { $0.state == .notAttempted || $0.state == .cancelled }.count
        if unfinished > 0 && !cancelled {
            warnings.append("\(unfinished) of \(pages.count) pages have not been read; this reading is incomplete.")
        }
        if pages.contains(where: { $0.state == .emptyUnverified }) {
            warnings.append("No text was read on one or more pages. Check the source; this does not confirm a blank page.")
        }
        if lines.contains(where: { $0.reviewReasons.contains("no_regions_detected") }) {
            warnings.append("Text regions were not detected. The whole-page reading needs review against the source.")
        }
        let failedLines = lines.filter { $0.reviewReasons.contains("tile_failed") }.count
        if failedLines > 0 { warnings.append("\(failedLines) line(s) could not be read completely. Check the source image.") }
        let uncertainSeams = lines.filter { $0.reviewReasons.contains("empty_tile_between_text") }.count
        if uncertainSeams > 0 { warnings.append("\(uncertainSeams) line(s) may have missing text around a gap. Check the source image.") }
        if looksSoft { warnings.append("The source looks soft; check the transcription against the image.") }
        let blocks = lines.filter { !$0.looksLikeALine }.count
        if blocks > 0 { warnings.append("\(blocks) region(s) may contain several lines; review their reading.") }
        return warnings.isEmpty ? nil : warnings.joined(separator: "\n")
    }

    func encoded() -> String? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
