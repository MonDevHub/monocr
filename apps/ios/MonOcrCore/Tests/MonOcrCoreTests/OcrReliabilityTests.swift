import Foundation
import Testing
@testable import MonOcrCore

struct OcrReliabilityTests {
    private let box = LineSegment(x: 3, y: 7, width: 20, height: 10)

    @Test func cancelledAndReplacedJobsCannotPublishLate() async {
        var generation = OcrJobGeneration()
        let jobA = generation.advance()
        _ = generation.advance() // Clear while A is still in inference.
        #expect(!generation.accepts(jobA))
        let jobB = generation.advance()
        await Task.yield()
        #expect(!generation.accepts(jobA))
        #expect(generation.accepts(jobB))
    }

    @Test func scopedLeaseBalancesSuccessFailureAndRepeatedCleanup() {
        var starts = 0
        var stops = 0
        do {
            let lease = ScopedResourceLease(start: { starts += 1; return true }, stop: { stops += 1 })
            #expect(lease.acquired)
            lease.close()
            lease.close()
        }
        #expect(starts == 1)
        #expect(stops == 1)
        do {
            let lease = ScopedResourceLease(start: { starts += 1; return false }, stop: { stops += 1 })
            #expect(!lease.acquired)
        }
        #expect(starts == 2)
        #expect(stops == 1)
    }

    @Test func scopedLeaseReleasesOnThrownBody() {
        enum Failure: Error { case expected }
        var stops = 0
        func body() throws {
            let lease = ScopedResourceLease(start: { true }, stop: { stops += 1 })
            defer { lease.close() }
            throw Failure.expected
        }
        #expect(throws: Failure.self) { try body() }
        #expect(stops == 1)
    }

    /// The decoded scalars reach the output unchanged. This sequence has a
    /// different canonical order (U+1037 before U+103A), so any normalization on
    /// the output path fails here; a change to the text produced needs its own A/B.
    @Test func assemblyKeepsDecodedScalarsInTileOrderWithoutNormalizing() {
        let result = TileAssembly.assemble([
            .decoded(index: 1, bbox: box, rawText: "\u{1037}"),
            .decoded(index: 0, bbox: box, rawText: "\u{1000}\u{103A}"),
        ])
        #expect(result.rawText.unicodeScalars.map(\.value) == [0x1000, 0x103A, 0x1037])
        #expect(Array(result.text.utf8) == Array(result.rawText.utf8))
        #expect(result.reviewReasons.isEmpty)
    }

    @Test func failedMiddleTileDoesNotInventAJoinedLineAndKeepsEvidence() {
        let tiles = [
            TileReading.decoded(index: 0, bbox: box, rawText: "left"),
            TileReading.failed(index: 1, bbox: box, error: "runtime failure"),
            TileReading.decoded(index: 2, bbox: box, rawText: "right"),
        ]
        let result = TileAssembly.assemble(tiles)
        #expect(result.text.isEmpty)
        #expect(result.reviewReasons == ["tile_failed"])
        let line = RecognizedLine(text: result.text, bbox: box, tileCount: 3, looksLikeALine: true,
                                  rawText: result.rawText, tiles: tiles, reviewReasons: result.reviewReasons)
        #expect(line.tiles[0].rawText == "left")
        #expect(line.tiles[1].state == .failed)
        #expect(line.tiles[1].error == "runtime failure")
        #expect(line.tiles[2].rawText == "right")
    }

    @Test func emptyMiddleTileIsReviewableAndDistinctFromFailure() {
        let result = TileAssembly.assemble([
            .decoded(index: 0, bbox: box, rawText: "left"),
            .decoded(index: 1, bbox: box, rawText: ""),
            .decoded(index: 2, bbox: box, rawText: "right"),
        ])
        #expect(result.text == "leftright") // Existing join, explicitly uncertain.
        #expect(result.reviewReasons == ["empty_tile_between_text"])
        #expect(TileAssembly.assemble([.decoded(index: 0, bbox: box, rawText: "")]).reviewReasons.isEmpty)
    }

    @Test func repeatedCharactersAcrossWindowsAreNotCollapsedAgain() {
        let result = TileAssembly.assemble([
            .decoded(index: 0, bbox: box, rawText: "A"),
            .decoded(index: 1, bbox: box, rawText: "A"),
        ])
        #expect(result.text == "AA")
    }

    @Test func partialPageMetadataSurvivesHistorySerialization() throws {
        let line = RecognizedLine(text: "", bbox: box, tileCount: 1, looksLikeALine: false,
            pageIndex: 2, tiles: [.failed(index: 0, bbox: box, error: "bad tensor")], reviewReasons: ["tile_failed"])
        let metadata = OcrReviewMetadata(modelVersion: "fixture", pages: [
            PageOutcome(pageIndex: 0, state: .completed, error: nil),
            PageOutcome(pageIndex: 1, state: .renderFailed, error: "bad page"),
            PageOutcome(pageIndex: 2, state: .partial, error: nil),
            PageOutcome(pageIndex: 3, state: .cancelled, error: nil),
        ], lines: [line], looksSoft: true, cancelled: true)
        let json = try #require(metadata.encoded())
        let decoded = try JSONDecoder().decode(OcrReviewMetadata.self, from: Data(json.utf8))
        #expect(decoded.pages.map(\.pageIndex) == [0, 1, 2, 3])
        #expect(decoded.pages[1].state == .renderFailed)
        #expect(decoded.lines[0].pageIndex == 2)
        #expect(decoded.lines[0].tiles[0].error == "bad tensor")
        #expect(decoded.lines[0].bbox.y == 7)
        #expect(decoded.warningSummary?.contains("Pages could not be read: 2.") == true)
        #expect(decoded.warningSummary?.contains("cancelled") == true)
        #expect(decoded.warningSummary?.contains("line(s) could not be read") == true)
    }

    @Test func allFailedRegionsAreAPageFailureRatherThanBlankSuccess() {
        let failed = RecognizedLine(text: "", bbox: box, tileCount: 1,
            looksLikeALine: true, reviewReasons: ["tile_failed"])
        let success = RecognizedLine(text: "read", bbox: box, tileCount: 1, looksLikeALine: true)
        #expect(PageOutcome.recognition(pageIndex: 0, lines: [failed]).state == .inferenceFailed)
        #expect(PageOutcome.recognition(pageIndex: 0, lines: [failed, success]).state == .partial)
        #expect(PageOutcome.recognition(pageIndex: 0, lines: [success]).state == .completed)
    }

    @Test func engineWideErrorsStopRecognitionAndTileErrorsDoNot() {
        struct Engine: EngineWideError { let isEngineWide: Bool }
        struct Other: Error {}
        #expect(TileFailurePolicy.isEngineWide(ModelContractError(predictedClass: 9, charsetLength: 2)))
        #expect(TileFailurePolicy.isEngineWide(Engine(isEngineWide: true)))
        #expect(!TileFailurePolicy.isEngineWide(Engine(isEngineWide: false)))
        #expect(!TileFailurePolicy.isEngineWide(Other()))
        #expect(!TileFailurePolicy.isEngineWide(CtcDecoder.InvalidOutput.nonfinite))
        #expect(!TileFailurePolicy.isEngineWide(CancellationError()))
    }

    @Test func aScanInWhichNoPageWasReadIsAFailureNotAResult() {
        let failed = RecognizedLine(text: "", bbox: box, tileCount: 1,
            looksLikeALine: true, reviewReasons: ["tile_failed"])
        let image = [PageOutcome.recognition(pageIndex: 0, lines: [failed])]
        #expect(PageOutcome.noPageRead(image))
        let imageRead = image.contains(where: \.wasRead)
        #expect(!imageRead)
        let pdf = [PageOutcome(pageIndex: 0, state: .renderFailed, error: "bad page"),
                   PageOutcome(pageIndex: 1, state: .inferenceFailed, error: "bad tensor")]
        #expect(PageOutcome.noPageRead(pdf))
        // One page read, even with no text on it, is a result with warnings.
        #expect(!PageOutcome.noPageRead(pdf + [PageOutcome(pageIndex: 2, state: .emptyUnverified, error: nil)]))
        #expect(!PageOutcome.noPageRead(pdf + [PageOutcome(pageIndex: 2, state: .partial, error: nil)]))
        let fallbackRead = (pdf + [PageOutcome(pageIndex: 2, state: .noRegionsDetected, error: nil)])
            .contains(where: \.wasRead)
        #expect(fallbackRead)
        // A page not reached or cancelled is not a failure, and nothing is not all-failed.
        #expect(!PageOutcome.noPageRead(pdf + [PageOutcome(pageIndex: 2, state: .cancelled, error: nil)]))
        #expect(!PageOutcome.noPageRead(pdf + [PageOutcome(pageIndex: 2, state: .notAttempted, error: nil)]))
        #expect(!PageOutcome.noPageRead([]))
    }

    @Test func emptyRecognitionDoesNotCertifyABlankPage() {
        let line = RecognizedLine(text: "", bbox: box, tileCount: 1, looksLikeALine: true)
        let outcome = PageOutcome.recognition(pageIndex: 0, lines: [line])
        #expect(outcome.state == .emptyUnverified)
        let review = OcrReviewMetadata(modelVersion: "fixture", pages: [outcome],
            lines: [line], looksSoft: false, cancelled: false)
        #expect(review.warningSummary?.contains("does not confirm a blank page") == true)
    }

    @Test func wholePageFallbackRemainsExplicitEvenWhenItEmitsText() {
        let line = RecognizedLine(text: "fluent", bbox: box, tileCount: 1,
            looksLikeALine: true, reviewReasons: ["no_regions_detected"])
        let outcome = PageOutcome.recognition(pageIndex: 0, lines: [line])
        #expect(outcome.state == .noRegionsDetected)
        let review = OcrReviewMetadata(modelVersion: "fixture", pages: [outcome],
            lines: [line], looksSoft: false, cancelled: false)
        #expect(review.warningSummary?.contains("whole-page reading needs review") == true)
    }

    /// A page counts as read when recognition ran on it and returned, even with no
    /// text: that is what keeps a scan of blank pages from reading as a failure. The
    /// expected set is spelled out so a new state has to be classified here too.
    @Test(arguments: PageOutcome.State.allCases)
    func wasReadIsTrueExactlyWhenRecognitionRanOnThePage(state: PageOutcome.State) {
        let ranAndReturned: Set<PageOutcome.State> =
            [.completed, .partial, .emptyUnverified, .noRegionsDetected]
        let outcome = PageOutcome(pageIndex: 0, state: state, error: nil)
        #expect(outcome.wasRead == ranAndReturned.contains(state))
    }

    @Test func softAndBlockWarningsKeepTheirActionableHints() {
        let block = RecognizedLine(text: "x", bbox: box, tileCount: 1, looksLikeALine: false)
        let review = OcrReviewMetadata(modelVersion: "fixture", pages: [], lines: [block],
            looksSoft: true, cancelled: false)
        let summary = review.warningSummary ?? ""
        #expect(summary.contains("try again with steadier focus"))
        #expect(summary.contains("1 block(s) were too tall to be one line"))
        #expect(summary.contains("try the Sparse or Line mode"))
    }

    @Test func unfinishedPagesAreNotReportedAsCompleteAfterRestart() {
        let metadata = OcrReviewMetadata(modelVersion: "fixture", pages: [
            PageOutcome(pageIndex: 0, state: .completed, error: nil),
            PageOutcome(pageIndex: 1, state: .notAttempted, error: nil),
        ], lines: [], looksSoft: false, cancelled: false)
        #expect(metadata.warningSummary?.contains("1 of 2 pages have not been read") == true)
    }

    @Test func pdfCombinerRetainsRawTextAndPageLocalGeometry() {
        let raw = "\u{1000}\u{103A}\u{1037}"
        let line = RecognizedLine(text: raw, bbox: box, tileCount: 1, looksLikeALine: true)
        let combined = PdfPageCombiner.combine(readings: [2: PageReading(text: raw,
            wordCount: 1, charCount: 1, lines: [line], looksSoft: false, rawText: raw)], totalPages: 3)
        #expect(combined.lines[0].pageIndex == 2)
        #expect(combined.lines[0].bbox.x == 3)
        #expect(Array(combined.text.utf8) == Array(combined.rawText.utf8))
        #expect(Array(combined.text.utf8.suffix(raw.utf8.count)) == Array(raw.utf8))
    }

    @Test(arguments: [Float.nan, Float.infinity, -Float.infinity])
    func nonfiniteLogitsFailInsteadOfDecodingBlankOrText(value: Float) {
        #expect(throws: CtcDecoder.InvalidOutput.self) {
            try CtcDecoder.decode(logits: [0, value], timeSteps: 1, numClasses: 2, charset: "A")
        }
    }

    @Test func malformedTensorFailsWithoutOutOfBoundsRead() {
        #expect(throws: CtcDecoder.InvalidOutput.self) {
            try CtcDecoder.decode(logits: [1], timeSteps: 2, numClasses: 2, charset: "A")
        }
    }
}
