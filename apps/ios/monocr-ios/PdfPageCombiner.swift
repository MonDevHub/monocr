import Foundation

/**
 One page's OCR reading, stripped to what `PdfPageCombiner` needs to join pages
 together.

 `MonOcrResult` carries this same data plus `debugImage` (UIKit-bound) and
 `durationMs`/`mode` (caller-scoped, not per-page). Those three stay in
 `MainViewModel`; everything a page-combining decision actually depends on is
 here, Foundation-only, so the combiner can be tested off-device.
 */
struct PageReading {
    let text: String
    let wordCount: Int
    let charCount: Int
    let lines: [RecognizedLine]
    let looksSoft: Bool
}

/**
 Joins per-page readings into one combined result.

 Re-run from scratch on every new page rather than accumulated incrementally —
 `MainViewModel` already keeps pages keyed by absolute index as they arrive in
 whatever order the task group finishes them, and recombining from the whole
 dictionary each time is what keeps the join in page order regardless of
 arrival order. `combine` and `firstFailure` are pure functions over that
 dictionary; nothing here touches `Task`, `MainActor`, or UIKit, which is the
 point — this used to live inside a `withTaskGroup` closure in a `@MainActor`
 class, where none of it could be tested directly.
 */
enum PdfPageCombiner {
    struct Combined {
        let text: String
        let wordCount: Int
        let charCount: Int
        let lines: [RecognizedLine]
        let looksSoft: Bool
    }

    /// `totalPages` is the absolute page count, not `readings.count` — a page
    /// that has not finished yet (or never will, on failure) must not compact
    /// the numbering of the pages after it. A missing page is skipped in the
    /// join, never renumbered, so page 5's heading still reads "Page 5" even
    /// if page 3 failed.
    static func combine(readings: [Int: PageReading], totalPages: Int) -> Combined {
        let text = (0..<totalPages)
            .compactMap { i -> String? in
                guard let r = readings[i] else { return nil }
                return "--- Page \(i + 1) ---\n\(r.text)\n\n"
            }
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)

        let wordCount = readings.values.reduce(0) { $0 + $1.wordCount }
        let charCount = readings.values.reduce(0) { $0 + $1.charCount }
        let lines = (0..<totalPages).flatMap { readings[$0]?.lines ?? [] }
        // Any soft page makes the combined reading suspect, so this is an OR
        // across every page read so far, not the last page's verdict alone.
        let looksSoft = readings.values.contains { $0.looksSoft }

        return Combined(text: text, wordCount: wordCount, charCount: charCount, lines: lines, looksSoft: looksSoft)
    }

    /// The lowest-index failure, deterministically. Failures arrive in whatever
    /// order the task group happens to finish pages in, and the error message
    /// shown to the user names one specific page — picking the same one every
    /// time a given failure set occurs is what makes that message reproducible.
    static func firstFailure(_ failures: [Int: String]) -> (page: Int, reason: String)? {
        guard let entry = failures.min(by: { $0.key < $1.key }) else { return nil }
        return (entry.key, entry.value)
    }
}
