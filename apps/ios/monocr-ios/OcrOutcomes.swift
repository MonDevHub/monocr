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
    /// English, and a catalogue key: shown through `AppLocalization`, so it is
    /// translated where a translation exists.
    let error: String?

    static let linesUnreadError = "No detected line could be read completely."
    static let renderFailedError = "The page could not be rendered."
    static let errorKeys = [linesUnreadError, renderFailedError]

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
            return PageOutcome(pageIndex: pageIndex, state: .inferenceFailed, error: linesUnreadError)
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

    /// What the reader should know before trusting this reading, in display order.
    var warnings: [ReviewWarning] {
        var warnings = [ReviewWarning]()
        let failedPages = pages.filter { $0.state == .renderFailed || $0.state == .inferenceFailed }
        if !failedPages.isEmpty { warnings.append(.pagesUnread(failedPages.map { $0.pageIndex + 1 })) }
        if cancelled { warnings.append(.cancelled) }
        let unfinished = pages.filter { $0.state == .notAttempted || $0.state == .cancelled }.count
        if unfinished > 0 && !cancelled { warnings.append(.unfinished(unfinished, of: pages.count)) }
        if pages.contains(where: { $0.state == .emptyUnverified }) { warnings.append(.emptyUnverified) }
        if lines.contains(where: { $0.reviewReasons.contains("no_regions_detected") }) {
            warnings.append(.noRegionsDetected)
        }
        let failedLines = lines.filter { $0.reviewReasons.contains("tile_failed") }.count
        if failedLines > 0 { warnings.append(.linesUnread(failedLines)) }
        let uncertainSeams = lines.filter { $0.reviewReasons.contains("empty_tile_between_text") }.count
        if uncertainSeams > 0 { warnings.append(.uncertainSeams(uncertainSeams)) }
        if looksSoft { warnings.append(.soft) }
        let blocks = lines.filter { !$0.looksLikeALine }.count
        if blocks > 0 { warnings.append(.blocks(blocks)) }
        return warnings
    }

    /// The warnings in English, one per line. This is what `HistoryRecord.warningSummary`
    /// has always stored, so rows saved before the warnings became keys still read
    /// the same; new rows are shown from `ocrMetadata` in the picked language instead.
    var warningSummary: String? {
        let lines = warnings.map(\.english)
        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }

    /// The warnings in `language`, one per line.
    func localizedWarningSummary(in language: AppLanguage = .current, bundle: Bundle = .main) -> String? {
        let lines = warnings.map { $0.text(in: language, bundle: bundle) }
        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }

    static func decoded(from json: String?) -> OcrReviewMetadata? {
        guard let data = json?.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(OcrReviewMetadata.self, from: data)
    }

    func encoded() -> String? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

/// One thing to check about a reading. Kept as a case rather than a sentence, so
/// it is rendered in whichever language is picked when it is shown, history
/// included. Each `key` is the English sentence and its catalogue key.
nonisolated enum ReviewWarning: Equatable, Sendable {
    /// One-based page numbers.
    case pagesUnread([Int])
    case cancelled
    case unfinished(Int, of: Int)
    case emptyUnverified
    case noRegionsDetected
    case linesUnread(Int)
    case uncertainSeams(Int)
    case soft
    case blocks(Int)

    /// One instance of every case, so a test can check each key is in the catalogue.
    static let examples: [ReviewWarning] = [
        .pagesUnread([2]), .cancelled, .unfinished(1, of: 2), .emptyUnverified,
        .noRegionsDetected, .linesUnread(1), .uncertainSeams(1), .soft, .blocks(1),
    ]

    var key: String {
        switch self {
        case .pagesUnread: return "Pages could not be read: %@."
        case .cancelled: return "Processing was cancelled; this reading is incomplete."
        case .unfinished: return "%1$lld of %2$lld pages have not been read; this reading is incomplete."
        case .emptyUnverified:
            return "No text was read on one or more pages. Check the source; this does not confirm a blank page."
        case .noRegionsDetected:
            return "Text regions were not detected. The whole-page reading needs review against the source."
        case .linesUnread: return "%lld line(s) could not be read completely. Check the source image."
        case .uncertainSeams: return "%lld line(s) may have missing text around a gap. Check the source image."
        case .soft: return "This photo looks soft. The text may be misread — try again with steadier focus."
        case .blocks:
            return "%lld block(s) were too tall to be one line. Their text may be invented — try the Sparse or Line mode."
        }
    }

    var arguments: [CVarArg] {
        switch self {
        case .pagesUnread(let pages): return [pages.map(String.init).joined(separator: ", ")]
        case .unfinished(let count, let total): return [count, total]
        case .linesUnread(let count), .uncertainSeams(let count), .blocks(let count): return [count]
        case .cancelled, .emptyUnverified, .noRegionsDetected, .soft: return []
        }
    }

    /// The source-language sentence, independent of any bundle.
    var english: String {
        arguments.isEmpty ? key : String(format: key, arguments: arguments)
    }

    func text(in language: AppLanguage = .current, bundle: Bundle = .main) -> String {
        AppLocalization.format(key, arguments, language: language, bundle: bundle)
    }
}
