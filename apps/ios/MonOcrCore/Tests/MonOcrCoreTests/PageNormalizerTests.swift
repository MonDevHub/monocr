import Foundation
import Testing

@testable import MonOcrCore

/**
 Behavioural checks for `PageNormalizer`.

 The bug this class exists to prevent: the segmenter reads dark pixels as ink and
 cannot tell it was handed an inverted scan, so on a dark-mode screenshot it used
 to return the gaps between lines as the lines. Polarity therefore has to be
 settled before segmentation, and these tests pin that it is.

 The dilate check here pins the ALGORITHM and not the kernel shape, and the
 difference is not academic. `dilate` was a square-kernel max filter until
 2026-08-28, against an elliptical reference, and this file was green for six days
 because its oracle was a square too — an implementation and its oracle agreeing
 about a shape neither had checked. The shape is pinned by
 `PageNormalizerFixtureTests` against cv2; nothing here can catch a wrong one.

 The reason it stayed invisible is that a slightly wrong background estimate still
 produces a plausible-looking page, so nothing downstream complains.
 */
struct PageNormalizerTests {

    /// Page with vertical ink bars over the middle half of the height.
    static func makePage(
        width: Int, height: Int, background: UInt8, ink: UInt8, inkEvery: Int
    ) -> GreyImage {
        var px = [UInt8](repeating: background, count: width * height)
        for x in 0..<width where x % inkEvery == 0 {
            for y in (height / 4)..<(3 * height / 4) { px[y * width + x] = ink }
        }
        return GreyImage(pixels: px, width: width, height: height)
    }

    // MARK: - Polarity

    /// The sentinel case. `lower` used 0 for both "not found yet" and a legal luminance
    /// of 0, so on a page whose lower order statistic is genuinely black the sentinel
    /// never cleared, the median came back half a level high, and it landed on the
    /// wrong side of `darkBackgroundMedian`. Found on the web port, which copied this
    /// code line for line.
    @Test func aPageWhoseCornersAreHalfBlackReadsAsDark() {
        // Left half black, right half white: two corner patches are pure 0 and two
        // pure 255, so the lower order statistic is exactly 0 and the true median is
        // 127.5 — just below the threshold.
        let w = 200
        let h = 200
        var pixels = [UInt8](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                pixels[y * w + x] = x < w / 2 ? 0 : 255
            }
        }
        let page = GreyImage(pixels: pixels, width: w, height: h)
        #expect(PageNormalizer.backgroundIsDark(page))
    }

    @Test func darkBackgroundIsDetected() {
        let darkMode = Self.makePage(width: 400, height: 300, background: 18, ink: 235, inkEvery: 9)
        #expect(PageNormalizer.backgroundIsDark(darkMode))
    }

    @Test func lightBackgroundIsLeftAlone() {
        let paper = Self.makePage(width: 400, height: 300, background: 246, ink: 12, inkEvery: 9)
        #expect(!PageNormalizer.backgroundIsDark(paper))
    }

    /// The whole point of the class: whatever came in, what comes out is dark ink
    /// on a light background, so the segmenter's ink-is-dark assumption holds.
    @Test func darkModePageComesOutDarkInkOnWhite() {
        let darkMode = Self.makePage(width: 400, height: 300, background: 18, ink: 235, inkEvery: 9)
        let fixed = PageNormalizer.normalize(darkMode)

        // x=4 is background, x=9 is an ink column, y=150 is inside the inked band.
        #expect(fixed.pixel(x: 4, y: 150) > 200)
        #expect(fixed.pixel(x: 9, y: 150) < 120)
    }

    // MARK: - Background levelling

    @Test func greyPanelLevelsToNearWhiteWithoutErasingInk() {
        let panel = Self.makePage(width: 400, height: 300, background: 128, ink: 10, inkEvery: 9)
        let levelled = PageNormalizer.normalize(panel)

        #expect(levelled.pixel(x: 4, y: 150) > 240)
        #expect(levelled.pixel(x: 9, y: 150) < 120)
    }

    /// An already-clean page must survive untouched, or normalising would be a
    /// cost with a downside on the common case.
    @Test func whitePageIsUnchanged() {
        let white = Self.makePage(width: 400, height: 300, background: 255, ink: 0, inkEvery: 9)
        let unchanged = PageNormalizer.normalize(white)

        #expect(unchanged.pixel(x: 4, y: 150) == 255)
        #expect(unchanged.pixel(x: 9, y: 150) < 40)
    }

    // MARK: - Degenerate sizes

    /// These reach the downsample, the kernel-size arithmetic and the deque
    /// passes with sizes smaller than the window, which is where an off-by-one
    /// would trap or read out of bounds rather than return a wrong pixel.
    @Test(arguments: [(0, 0), (1, 1), (3, 2), (2, 3), (1, 500)])
    func degenerateSizesDoNotTrap(width: Int, height: Int) {
        let tiny = GreyImage(
            pixels: [UInt8](repeating: 200, count: width * height),
            width: width, height: height
        )
        let out = PageNormalizer.normalize(tiny)

        #expect(out.width == width)
        #expect(out.height == height)
    }

    // MARK: - Dilation parity

    /// A black region larger than the dilation kernel must come back black.
    ///
    /// This is where `levelBackground`'s `max(estimate, 1.0)` earns its place. Inside a
    /// black area wider than the kernel the background estimate is 0 and the pixel is
    /// 0, so without the guard the division is 0/0 — and Swift's `min(255, NaN)`
    /// returns 255, so the block comes out WHITE. Kotlin clamps the same NaN to 0.
    /// Same expression, same inputs, opposite answers, and neither port can see it
    /// alone: the identical case in `PageNormalizerTest.kt` passes with or without
    /// the guard, because on the JVM `NaN.toInt()` is already 0.
    @Test func aBlackRegionLargerThanTheKernelStaysBlack() {
        let w = 120
        let h = 120
        var px = [UInt8](repeating: 245, count: w * h)
        for y in 40..<80 { for x in 40..<80 { px[y * w + x] = 0 } }

        let out = PageNormalizer.normalize(GreyImage(pixels: px, width: w, height: h))

        // Deep inside the black square, well clear of its edges.
        #expect(out.pixel(x: 60, y: 60) == 0, "the middle of a black block must not come back white")
        #expect(out.pixel(x: 5, y: 5) > 200, "and the paper around it must stay light")
    }

    /// A naive max filter over the SAME disk, written as a plain double loop.
    ///
    /// This pins the incremental radius-growing algorithm in `dilate`, not the shape
    /// of the kernel — both sides build the half-width table the same way, so a wrong
    /// shape would agree with itself here. The shape is pinned independently by
    /// `PageNormalizerFixtureTests`, whose expectations come from cv2.
    ///
    /// It was a naive SQUARE until 2026-08-28, which is how iOS shipped a square
    /// kernel against an elliptical reference with a green test beside it.
    static func naiveDilate(_ src: GreyImage, kernel: Int) -> GreyImage {
        let r = kernel / 2
        var halfWidth = [Int](repeating: 0, count: 2 * r + 1)
        for i in 0...(2 * r) {
            let dy = Double(i - r)
            halfWidth[i] = Int((Double(r * r) - dy * dy).squareRoot().rounded())
        }
        var out = [UInt8](repeating: 0, count: src.width * src.height)
        for y in 0..<src.height {
            for x in 0..<src.width {
                var best: UInt8 = 0
                for dy in -r...r {
                    let hw = halfWidth[dy + r]
                    for dx in -hw...hw {
                        let ny = y + dy
                        let nx = x + dx
                        guard src.contains(x: nx, y: ny) else { continue }
                        best = max(best, src.pixel(x: nx, y: ny))
                    }
                }
                out[y * src.width + x] = best
            }
        }
        return GreyImage(pixels: out, width: src.width, height: src.height)
    }

    /// Seeded rather than system-random: a parity failure that cannot be
    /// reproduced from the test output is most of the way to not being a finding.
    /// SplitMix64, chosen only because it is short enough to read.
    struct SeededRandom: RandomNumberGenerator {
        private var state: UInt64
        init(seed: UInt64) { state = seed }

        mutating func next() -> UInt64 {
            state = state &+ 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    /// The kernels span both sides of what a real page produces: a 288 DPI A4
    /// render downsamples to 595x842 and asks for 211, and small crops land on
    /// the 7 floor. 41 is wider than the 47-row test image's half-height, so it
    /// also covers a window clipped on both ends at once.
    @Test(arguments: [7, 9, 15, 41])
    func diskDilateMatchesANaiveDiskFilter(kernel: Int) {
        var generator = SeededRandom(seed: 0x5EED_0000 | UInt64(kernel))
        var px = [UInt8]()
        px.reserveCapacity(63 * 47)
        for _ in 0..<(63 * 47) { px.append(UInt8.random(in: 0...255, using: &generator)) }
        let noise = GreyImage(pixels: px, width: 63, height: 47)

        #expect(PageNormalizer.dilate(noise, kernel: kernel).pixels
            == Self.naiveDilate(noise, kernel: kernel).pixels)
    }

    // MARK: - Resampling primitives

    /// Pinned against OpenCV's INTER_AREA on the same 2x2 input: the mean of
    /// 0, 100, 200 and 255 rounds to 139, and a plain integer mean would give 138.
    @Test func areaDownsampleAveragesTheSourceRectangle() {
        let quad = GreyImage(pixels: [0, 100, 200, 255], width: 2, height: 2)
        let averaged = PageNormalizer.downsampleArea(quad, width: 1, height: 1)
        #expect(averaged.pixels == [139])
    }

    /// A flat field is the one case where both resamplers have a single right
    /// answer at every output pixel, whatever the scale factor, so it catches an
    /// indexing or weighting mistake without pinning interpolated values.
    @Test func resamplingAFlatFieldStaysFlat() {
        let flat = GreyImage(pixels: [UInt8](repeating: 77, count: 16), width: 4, height: 4)

        let up = PageNormalizer.upsampleBilinear(flat, width: 11, height: 9)
        #expect(up.count == 11 * 9)
        #expect(up.allSatisfy { abs($0 - 77) < 0.001 })

        let down = PageNormalizer.downsampleArea(flat, width: 3, height: 2)
        #expect(down.pixels.allSatisfy { $0 == 77 })
    }
}
