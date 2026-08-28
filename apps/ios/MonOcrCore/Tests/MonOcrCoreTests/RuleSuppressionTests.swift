import Foundation
import Testing

@testable import MonOcrCore

/**
 Unit tests for `LineSegmenter.suppressPageRules`.

 The same eight cases exist in
 `apps/android/app/src/test/java/dev/janakhpon/monocr/engine/RuleSuppressionTest.kt`
 and cover the same three constants. Written as a matching pair on purpose: the
 defect this whole pass exists to stop is two ports of one algorithm drifting
 apart unnoticed, and a divergence is far easier to see in two test files that are
 meant to read identically than in two implementations that are not.

 These run against the binary mask directly rather than through `segment`, so a
 failure names the run-length scan instead of the adaptive threshold that feeds
 it. The one end-to-end case at the bottom is the product property.
 */
struct RuleSuppressionTests {

    /// Row-major mask, `true` where there is ink.
    static func mask(_ width: Int, _ height: Int, _ paint: (Int, Int) -> Bool) -> [Bool] {
        var m = [Bool](repeating: false, count: width * height)
        for y in 0..<height {
            for x in 0..<width { m[y * width + x] = paint(x, y) }
        }
        return m
    }

    static func inkCount(_ m: [Bool]) -> Int { m.filter { $0 }.count }

    // MARK: - Nothing to do

    /// The property that makes this pass safe to run on every page unconditionally:
    /// a page carrying no rules comes back identical to the pixel, so the step
    /// cannot cost anything on the pages it was not written for.
    @Test func aPageWithNoRulesIsUntouchedToThePixel() {
        // Glyph-scale strokes: runs of 2, nowhere near the 50px span this page requires.
        var m = Self.mask(100, 100) { x, y in (30...32).contains(y) && x < 40 && x % 4 < 2 }
        let before = m

        #expect(LineSegmenter.suppressPageRules(&m, width: 100, height: 100) == false)
        #expect(m == before)
    }

    /// An empty mask has no ink to measure a share against, so it returns early.
    ///
    /// The second assertion used to be `inkCount(m) == 0` on a mask constructed
    /// all-false, passed to a function whose only write sets a pixel FALSE. It
    /// could not fail under any mutation. Asserting the mask is unchanged is the
    /// same intent and can.
    @Test func aBlankPageIsRefusedBeforeTheInkShareIsComputed() {
        var m = [Bool](repeating: false, count: 100 * 100)
        let before = m
        #expect(LineSegmenter.suppressPageRules(&m, width: 100, height: 100) == false)
        #expect(m == before)
    }

    // MARK: - Rules found

    @Test func aFullWidthHorizontalRuleIsRemovedAndTheTextIsKept() {
        // minH = max(15, 100 * 0.5) = 50. The border row is a run of 100; the text
        // rows are runs of 2.
        var m = Self.mask(100, 100) { x, y in
            y == 10 || ((30...32).contains(y) && x < 40 && x % 4 < 2)
        }
        let textInkBefore = Self.inkCount(m) - 100

        #expect(LineSegmenter.suppressPageRules(&m, width: 100, height: 100))

        for x in 0..<100 { #expect(m[10 * 100 + x] == false, "the rule at row 10 should be gone at x=\(x)") }
        #expect(Self.inkCount(m) == textInkBefore, "no text pixel may be removed with it")
    }

    @Test func aFullHeightVerticalRuleIsRemoved() {
        // The margin rule of a ruled notebook, or the left edge of a table.
        var m = Self.mask(100, 100) { x, y in
            x == 10 || ((30...32).contains(y) && (40...79).contains(x) && x % 4 < 2)
        }
        let textInkBefore = Self.inkCount(m) - 100

        #expect(LineSegmenter.suppressPageRules(&m, width: 100, height: 100))

        for y in 0..<100 { #expect(m[y * 100 + 10] == false, "the rule at column 10 should be gone at y=\(y)") }
        #expect(Self.inkCount(m) == textInkBefore)
    }

    // MARK: - The two guards

    /// `ruleMaxInkShare`. When the scan classifies most of the page's ink as rules
    /// it has found the text, and emptying the page is strictly worse than leaving
    /// it alone.
    @Test func suppressionThatWouldEatThePageIsAbandoned() {
        // 80 solid rows: every row is a 100px run and every column an 80px run, so
        // the scan claims 100% of the ink.
        var m = Self.mask(100, 100) { _, y in y < 80 }
        let before = m

        #expect(LineSegmenter.suppressPageRules(&m, width: 100, height: 100) == false,
                "100 percent is over the 80 percent ceiling")
        #expect(m == before, "an abandoned pass must not have partially applied")
    }

    /// The 15px floor under the span. Without it a small crop would use a span of
    /// `width / 2`, which on a 20px-wide crop is 10px — inside glyph range.
    @Test func theSpanHasAFloorOfFifteenPixelsOnSmallCrops() {
        // 20 wide, so width * 0.5 = 10 and the floor of 15 is what governs.
        func withRowRun(_ length: Int) -> [Bool] {
            Self.mask(20, 20) { x, y in
                (y == 5 && x < length) || ((10...12).contains(y) && x < 8 && x % 4 < 2)
            }
        }

        var fifteen = withRowRun(15)
        #expect(LineSegmenter.suppressPageRules(&fifteen, width: 20, height: 20),
                "a run of exactly 15 reaches the floor")
        for x in 0..<15 { #expect(fifteen[5 * 20 + x] == false) }

        var fourteen = withRowRun(14)
        let before = fourteen
        #expect(LineSegmenter.suppressPageRules(&fourteen, width: 20, height: 20) == false,
                "a run of 14 is one short and must survive")
        #expect(fourteen == before)
    }

    /// The same boundary on the vertical axis.
    ///
    /// Added after a mutation run: flipping `>=` to `>` in the vertical scan survived
    /// the whole suite, because the vertical case above uses a full-height column and
    /// a run of 100 clears a span of 50 either way. Only an exact-length run pins it.
    @Test func theVerticalSpanBoundaryIsExact() {
        // Scattered single pixels keep the ink share under the ceiling without
        // forming a run of their own on either axis.
        func withColumnRun(_ rows: Int) -> [Bool] {
            Self.mask(20, 20) { x, y in
                (x == 5 && y < rows) || ((10...13).contains(x) && (x + y) % 2 == 0)
            }
        }

        var fifteen = withColumnRun(15)
        #expect(LineSegmenter.suppressPageRules(&fifteen, width: 20, height: 20),
                "a column run of exactly 15 reaches the floor")
        for y in 0..<15 { #expect(fifteen[y * 20 + 5] == false) }

        var fourteen = withColumnRun(14)
        let before = fourteen
        #expect(LineSegmenter.suppressPageRules(&fourteen, width: 20, height: 20) == false,
                "a column run of 14 is one short and must survive")
        #expect(fourteen == before)
    }

    // MARK: - The product property

    /**
     What the whole pass is for: a printed frame around a page must stop changing
     how many lines come out of it.

     A page border adds a constant ink floor to every row it spans. Once that floor
     clears the gap threshold no row inside the frame reads as a gap, the page
     returns as one band, and that band is squeezed into the model window and read
     as a sentence that is not on the page. Upstream, nine of twelve real MNEC
     papers collapsed this way.

     Asserted as an equality against the same page unframed rather than as "more
     than one band", because that is the actual claim — the frame is not supposed to
     be visible in the result at all.
     */
    @Test func aPrintedFrameDoesNotChangeTheReading() {
        let ratio = SegmentationMode.page.densityThresholdRatio!  // page always has one
        let w = 300
        let h = 200
        let stripes = [40...52, 90...102, 140...152]

        // Words with gaps, not a solid ribbon of ink. The first version of this
        // fixture drew each stripe as one unbroken 260px band, which on a 300px page
        // is longer than the 150px span — so the scan classified the TEXT as rules,
        // `ruleMaxInkShare` fired at 90.7%, and the pass abandoned. That is the guard
        // behaving exactly as designed, and it made the fixture prove nothing. Real
        // text is broken by word gaps and cannot form runs that long.
        func page(framed: Bool) -> GreyImage {
            var px = [UInt8](repeating: 255, count: w * h)
            for stripe in stripes {
                for y in stripe {
                    for x in 20..<(w - 20) {
                        let insideAWord = (x - 20) % 45 < 30
                        if insideAWord && x % 4 < 2 { px[y * w + x] = 0 }
                    }
                }
            }
            if framed {
                for x in 0..<w { px[x] = 0; px[(h - 1) * w + x] = 0 }
                for y in 0..<h { px[y * w] = 0; px[y * w + w - 1] = 0 }
            }
            return GreyImage(pixels: px, width: w, height: h)
        }

        func geometry(_ bands: [LineSegment]) -> [String] {
            bands.map { "\($0.x),\($0.y),\($0.width),\($0.height)" }
        }

        let plain = LineSegmenter.segment(page: page(framed: false), densityThresholdRatio: ratio)
        let framed = LineSegmenter.segment(page: page(framed: true), densityThresholdRatio: ratio)

        #expect(plain.count == stripes.count,
                "the unframed page is the control and must find its stripes")

        // Band-for-band, not just the count. A frame that shifted every box by a
        // pixel would pass a count check and still be visible in every crop.
        #expect(geometry(plain) == geometry(framed),
                "the frame must not be visible in the result at all")
    }
}
