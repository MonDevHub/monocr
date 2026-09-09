import Foundation
import Testing

@testable import MonOcrCore

/**
 `PdfPageCombiner` used to live inline inside `runPdf`'s `withTaskGroup` closure
 in a `@MainActor class`, where none of it could be tested directly. Extracted
 behaviour-neutrally: these pin the same four properties the extraction was
 done to protect — absolute page numbering across a gap, the join format and
 final trim, line order across pages, and deterministic failure selection.
 */
struct PdfPageCombinerTests {

    static func line(_ text: String) -> RecognizedLine {
        RecognizedLine(
            text: text, bbox: LineSegment(x: 0, y: 0, width: 10, height: 10),
            tileCount: 1, looksLikeALine: true)
    }

    static func reading(_ text: String, words: Int = 1, chars: Int? = nil, soft: Bool = false, lines: [RecognizedLine]? = nil)
        -> PageReading
    {
        PageReading(
            text: text, wordCount: words, charCount: chars ?? text.count,
            lines: lines ?? [Self.line(text)], looksSoft: soft)
    }

    /// A page that failed (or never finished) must not compact the numbering
    /// of the pages after it — the heading on the page after a gap still names
    /// its own absolute page number, not its position among the pages present.
    @Test func absolutePageNumberingAcrossAGap() {
        let readings: [Int: PageReading] = [
            0: Self.reading("first"),
            2: Self.reading("third"),
        ]
        let combined = PdfPageCombiner.combine(readings: readings, totalPages: 3)
        #expect(combined.text.contains("--- Page 1 ---\nfirst"))
        #expect(combined.text.contains("--- Page 3 ---\nthird"))
        #expect(!combined.text.contains("Page 2"))
    }

    /// The exact join format, and that the whole result is trimmed once at the
    /// end rather than per page — a per-page trim would not remove the last
    /// page's own trailing blank line.
    @Test func theJoinFormatAndFinalTrim() {
        let readings: [Int: PageReading] = [
            0: Self.reading("alpha"),
            1: Self.reading("beta"),
        ]
        let combined = PdfPageCombiner.combine(readings: readings, totalPages: 2)
        #expect(combined.text == "--- Page 1 ---\nalpha\n\n--- Page 2 ---\nbeta")
    }

    /// Lines come back in page order regardless of the order pages finished
    /// in — the dictionary is keyed by absolute index and iterated `0..<totalPages`,
    /// never in insertion order.
    @Test func lineOrderAcrossPages() {
        let pageZeroLines = [Self.line("z0-a"), Self.line("z0-b")]
        let pageOneLines = [Self.line("z1-a")]
        // Insert page 1 before page 0, so insertion order disagrees with page order.
        var readings: [Int: PageReading] = [:]
        readings[1] = Self.reading("one", lines: pageOneLines)
        readings[0] = Self.reading("zero", lines: pageZeroLines)

        let combined = PdfPageCombiner.combine(readings: readings, totalPages: 2)
        #expect(combined.lines.map(\.text) == ["z0-a", "z0-b", "z1-a"])
    }

    /// A missing page (never finished, or failed) contributes no lines, and
    /// does not break the ordering of the pages around it.
    @Test func aMissingPageContributesNoLinesButDoesNotBreakOrder() {
        let readings: [Int: PageReading] = [
            0: Self.reading("zero", lines: [Self.line("z0")]),
            2: Self.reading("two", lines: [Self.line("z2")]),
        ]
        let combined = PdfPageCombiner.combine(readings: readings, totalPages: 3)
        #expect(combined.lines.map(\.text) == ["z0", "z2"])
    }

    /// Word and char counts sum across every page present; a soft page makes
    /// the whole combined reading suspect, so this is an OR across pages, not
    /// the last page's verdict alone.
    @Test func countsSumAndSoftnessIsAnOrAcrossPages() {
        let readings: [Int: PageReading] = [
            0: Self.reading("one two", words: 2, chars: 7, soft: false),
            1: Self.reading("three", words: 1, chars: 5, soft: true),
        ]
        let combined = PdfPageCombiner.combine(readings: readings, totalPages: 2)
        #expect(combined.wordCount == 3)
        #expect(combined.charCount == 12)
        #expect(combined.looksSoft == true)
    }

    /// No pages read yet (or all failed): the combiner still returns a value,
    /// not a crash on an empty dictionary.
    @Test func noReadingsYetCombinesToEmpty() {
        let combined = PdfPageCombiner.combine(readings: [:], totalPages: 3)
        #expect(combined.text == "")
        #expect(combined.lines.isEmpty)
        #expect(combined.looksSoft == false)
    }

    /// Failures arrive in whatever order the task group happens to finish
    /// pages in. The user-facing message names one specific page, so which one
    /// gets named has to be reproducible for a given failure set, not an
    /// artifact of scheduling.
    @Test func deterministicFailureSelection() {
        let failures: [Int: String] = [4: "timed out", 1: "could not be rendered", 7: "decode error"]
        let picked = PdfPageCombiner.firstFailure(failures)
        #expect(picked?.page == 1)
        #expect(picked?.reason == "could not be rendered")
    }

    /// No failures at all is a real case (the common one), not an error.
    @Test func noFailuresReturnsNil() {
        #expect(PdfPageCombiner.firstFailure([:]) == nil)
    }
}
