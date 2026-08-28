import Foundation
import Testing

@testable import MonOcrCore

/**
 Unit tests for `LineSegmenter.mergeRuns`, the bounded merge that pairs with raw
 boundary detection.

 The same cases exist in `apps/web/src/lib/segmentation.test.ts` and in
 `apps/android/app/src/test/java/dev/janakhpon/monocr/engine/LineSegmenterTest.kt`.
 Written as a matching set on purpose: the defect this pass exists to stop is one
 algorithm's four ports drifting apart unnoticed, and a divergence is far easier
 to see in test files that are meant to read identically than in four
 implementations that are not.

 The fixtures are NOT copies of `monocr-onnx` `rust/src/segmenter.rs`, and that is
 deliberate. Three of the Rust fixtures are degenerate against the mutation they
 are named for, because with only two runs on the page the median run height IS
 one of the two:

 - its ink-alone case has runs of 40, 40, 82, 82, so the median is 82 and
   `2 * 40 <= 82` makes the fragment clause fire too, and dropping the ink clause
   survives it;
 - its wide-gap case has two runs of 40, so the ceiling is 80 against a 95-row
   merged span, and dropping the size bound survives;
 - its two-lines-apart case is refused by the same ceiling, so weakening the
   fragment ratio from 2x to 1x survives.

 Every fixture below whose verdict depends on the median therefore carries
 ordinary full-height lines as well as the pair under test, which puts the median
 where a real page would put it, and all three of those mutations die here;
 verified by running them.

 The first two fixtures are the exception and ARE the Rust geometry verbatim, on
 purpose: they are the two cases actually measured on a real page, so they are
 kept exactly as measured rather than reshaped. Both are degenerate in the sense
 above - the dip fixture has a median of 44, so `2 * 20 <= 44` fires the fragment
 clause alongside the ink clause - and neither is what kills a mutation. They are
 regression anchors for the measurement, and the isolating cases follow.
 */
struct LineMergeTests {

    /// A profile with the given half-open ranges filled to `value`.
    static func profile(_ length: Int, _ fills: [(Int, Int, Float)]) -> [Float] {
        var h = [Float](repeating: 0, count: length)
        for (a, b, v) in fills { for y in a..<b { h[y] = v } }
        return h
    }

    static func same(_ got: [(Int, Int)], _ want: [(Int, Int)]) -> Bool {
        got.count == want.count && zip(got, want).allSatisfy { $0 == $1 }
    }

    @Test func aSubThresholdDipDoesNotEndALine() {
        // The measured case, in the reference's numbers: one line, rows 260-324,
        // split by row 280 carrying 6 ink pixels against a threshold of 7.0. On this
        // port the same shape is a dip row of 14 ink columns against 16.43.
        var hist = Self.profile(400, [(260, 325, 200)])
        hist[280] = 6 // above zero, below the gap threshold
        let got = LineSegmenter.mergeRuns([(260, 280), (281, 325)], rawHist: hist, maxGap: 10)
        #expect(Self.same(got, [(260, 325)]), "a 1-row dip holding ink split one line in two: \(got)")
    }

    @Test func aZeroInkGapStillMergesAFragmentIntoItsLine() {
        // The other measured case: rows 341-360 are the upper marks and 362-404 the
        // body of one line, separated by TWO rows of genuinely zero ink. The ink
        // clause cannot cross that; the height ratio is what does.
        let hist = Self.profile(500, [(341, 360, 40), (362, 404, 300)])
        let got = LineSegmenter.mergeRuns([(341, 360), (362, 404)], rawHist: hist, maxGap: 10)
        #expect(Self.same(got, [(341, 404)]), "a 19-row fragment two empty rows from a 42-row line stayed separate: \(got)")
    }

    @Test func twoRealLinesTwoRowsApartStaySeparate() {
        // The case the fragment clause must NOT swallow, and the reason it is a
        // ratio: same gap, same emptiness, but both runs are full height against the
        // page median. A vertical smear was tried instead and fused exactly this
        // pair, which is why the fix is not a smear.
        let hist = Self.profile(400, [(20, 60, 300), (62, 102, 300), (150, 210, 300), (260, 320, 300)])
        let runs = [(20, 60), (62, 102), (150, 210), (260, 320)]
        let got = LineSegmenter.mergeRuns(runs, rawHist: hist, maxGap: 10)
        #expect(Self.same(got, runs), "two full-height lines were fused, which is what a smear would have done: \(got)")
    }

    @Test func aWideGapIsALineBoundaryHoweverMuchInkItHolds() {
        // The size bound on its own. Overlapping diacritics can hold the raw profile
        // above zero right across real inter-line spacing; upstream that collapsed 3
        // PDF lines into 1. The two 60-row lines put the ceiling at 120, above the
        // 95-row span a merge here would produce, so the size bound is the only thing
        // refusing it.
        let hist = Self.profile(400, [
            (20, 60, 300), (60, 75, 5), (75, 115, 300), (200, 260, 300), (300, 360, 300)
        ])
        let runs = [(20, 60), (75, 115), (200, 260), (300, 360)]
        let got = LineSegmenter.mergeRuns(runs, rawHist: hist, maxGap: 10)
        #expect(Self.same(got, runs), "a 15-row gap merged, so the size bound is not being applied: \(got)")
    }

    @Test func aDipBetweenEqualHalvesMergesOnTheInkClauseAlone() {
        // The ink clause isolated. In the dip case above the fragment clause ALSO
        // fires, so dropping the ink test survives it. Here the two halves are 40
        // rows against a page median of 60, so `2 * 40 <= 60` is false and only the
        // ink test can merge them.
        let hist = Self.profile(400, [
            (20, 60, 300),
            (60, 62, 5), // two rows of ink: below any threshold, above zero
            (62, 102, 300), (150, 210, 300), (260, 320, 300)
        ])
        let got = LineSegmenter.mergeRuns(
            [(20, 60), (62, 102), (150, 210), (260, 320)], rawHist: hist, maxGap: 10
        )
        #expect(
            Self.same(got, [(20, 102), (150, 210), (260, 320)]),
            "an ink-holding 2-row dip between two halves of a typical line did not merge: \(got)"
        )
    }

    @Test func aRunOfFragmentsCannotChainPastTwiceATypicalLine() {
        // The ceiling and the median, together, on the failure they exist for. Five
        // 20-row fragments two empty rows apart would chain into one 108-row band,
        // and this is the shape that cost 92% of a real page's characters when the
        // fragment test compared against the NEIGHBOUR instead of the median: every
        // merge makes the accumulated run taller, and a taller run makes the next
        // line look more like a fragment.
        //
        // Against a page median of 45 the ceiling is 90, so the chain stops after
        // four. Judging against the neighbour merges nothing here at all, so this
        // case pins both halves of the correction.
        var fills: [(Int, Int, Float)] = [
            (0, 20, 200), (22, 42, 200), (44, 64, 200), (66, 86, 200), (88, 108, 200)
        ]
        var lines = [(Int, Int)]()
        for i in 0..<5 {
            let y = 200 + i * 100
            fills.append((y, y + 45, 300))
            lines.append((y, y + 45))
        }
        let hist = Self.profile(700, fills)
        let runs = [(0, 20), (22, 42), (44, 64), (66, 86), (88, 108)] + lines
        let got = LineSegmenter.mergeRuns(runs, rawHist: hist, maxGap: 10)
        #expect(
            Self.same(got, [(0, 86), (88, 108)] + lines),
            "a chain of fragments was not bounded at twice a typical line: \(got)"
        )
    }

    @Test func anEmptyPageIsLeftAlone() {
        #expect(LineSegmenter.mergeRuns([], rawHist: [Float](repeating: 0, count: 10), maxGap: 10).isEmpty)
    }

    /**
     The merge must be reached THROUGH `segment`, not only unit-tested.

     In the Rust port a mutation that deleted the `merge_runs` call from the pipeline
     SURVIVED all four helper tests, because they call the helper directly and the
     call site was unguarded. That is the gap `se-brain`
     `rules/standards/testing.md` §23 names: a tested helper does not make its call
     site safe.

     Geometry is this port's measured one. Rust's is not reusable: this port blurs
     the greyscale 3x3 and dilates the mask with reach 2 before the profile, so a
     source gap under 8 rows never reaches the profile at all.
     */
    @Test func aBridgedDiacriticStripComesBackJoinedToItsLine() {
        // One stroke bridging an 8-row gap gives a dip row of 14 raw ink columns
        // against a threshold of 16.43. Before the merge this port returned 20 bands
        // for the 10 lines drawn: a 40px strip of glyph tops and a 77px decapitated
        // body each.
        let bands = LineSegmenter.segment(
            page: Self.monPage(gap: 8, stems: 1), densityThresholdRatio: 0.03
        )

        #expect(bands.count == 10, "10 lines were drawn; a sub-threshold dip returns 20, got \(bands.count)")
        for b in bands {
            // A body-only band is 77px.
            #expect(b.height > 100, "band is \(b.height)px, so it is the body without its marks")
        }
    }

    /**
     The merge must run BEFORE the height filter, and this is the only case where
     the two orders differ - so without it, moving the filter ahead of the merge is
     a one-line change no test notices.

     A 3-row source strip becomes a 9-row run, one short of `minLineHeight` 10.
     Measured both ways on this port: filtering first returns 10 bands 77px tall,
     this order returns 10 bands 94px tall, and the 17px difference IS the strip of
     upper marks. The COUNT is 10 either way, which is why the assertion is on
     height.
     */
    @Test func aStripShorterThanTheHeightFloorSurvivesSoTheOrderIsPinned() {
        let bands = LineSegmenter.segment(
            page: Self.monPage(gap: 8, stems: 0, stripH: 3), densityThresholdRatio: 0.03
        )

        #expect(bands.count == 10, "got \(bands.count)")
        for b in bands {
            #expect(
                b.height > 85,
                "band is \(b.height)px; filtering before the merge drops the strip and returns 77"
            )
        }
    }

    @Test func aSixRowGapIsClosedByTheBlurAndNeverReachesTheMerge() {
        // The control. Below 8 source rows the grey blur and the vertical smear
        // bridge the gap before the profile sees it, so this case never reached
        // `mergeRuns` and must still return the drawn count.
        let bands = LineSegmenter.segment(
            page: Self.monPage(gap: 6, stems: 1), densityThresholdRatio: 0.03
        )
        #expect(bands.count == 10, "got \(bands.count)")
    }

    /**
     A page of Mon-shaped lines: a sparse strip of upper marks, `gap` source rows,
     then a solid consonant body, with `stems` word positions whose stroke crosses
     the gap.

     Sparse marks and strokes, not solid bars: the segmenter binarizes against a
     25px local mean, so the inside of a solid bar is not darker than its own
     neighbourhood and only its edges register as ink. Words rather than a
     full-width ribbon, because a ribbon this wide reads as a printed rule and
     `suppressPageRules` removes it.
     */
    static func monPage(gap: Int, stems: Int, lines: Int = 10, stripH: Int = 20) -> GreyImage {
        let w = 900, h = 1200, bodyH = 45
        var px = [UInt8](repeating: 255, count: w * h)
        for l in 0..<lines {
            let y0 = 100 + l * 100
            let bodyStart = y0 + stripH + gap
            var g = 0
            var x = 60
            while x < w - 60 {
                if (x - 60) % 60 < 42 {
                    for y in bodyStart..<(bodyStart + bodyH) { px[y * w + x] = 0; px[y * w + x + 1] = 0 }
                    if g % 3 == 0 {
                        for y in y0..<(y0 + stripH) { px[y * w + x] = 0; px[y * w + x + 1] = 0 }
                    }
                    if (x - 60) / 60 < stems && (x - 60) % 60 == 0 {
                        for y in y0..<bodyStart { px[y * w + x] = 0; px[y * w + x + 1] = 0 }
                    }
                }
                x += 6; g += 1
            }
        }
        return GreyImage(pixels: px, width: w, height: h)
    }
}
