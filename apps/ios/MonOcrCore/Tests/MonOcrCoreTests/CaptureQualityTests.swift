import Foundation
import Testing

@testable import MonOcrCore

/**
 Sharpness measurement.

 The analytic case is the load-bearing one: it pins the kernel coefficient, not
 just the ordering of two images. A test that only asserts "sharp scores higher
 than soft" passes with the wrong coefficient, which is how the same test on the
 web port survived a mutation from -4 to -3 until it was tightened.

 Values are shared with `apps/web/src/lib/capture-quality.test.ts` so the two ports
 are pinned to one derivation rather than two.
 */
struct CaptureQualityTests {

    /// White page with full-height dark bars in one row band, `pitch` apart.
    static func page(
        _ width: Int, _ height: Int, band: Range<Int>?, pitch: Int = 8, ink: UInt8 = 0
    ) -> GreyImage {
        var pixels = [UInt8](repeating: 255, count: width * height)
        if let band {
            for y in band {
                var x = 20
                while x < width - 20 {
                    pixels[y * width + x] = ink
                    x += pitch
                }
            }
        }
        return GreyImage(pixels: pixels, width: width, height: height)
    }

    /**
     The analytic case, derived by hand and independently reproduced on the web port.

     5x5, all 255, centre 100. A grey centre is required: a black centre gives the
     same answer under -4 and -3 because it is multiplied by zero either way, so it
     cannot pin the coefficient.

     Interior is 3x3 = 9 samples. The centre responds 4*255 - 4*100 = 620; its four
     edge-neighbours each 3*255 + 100 - 4*255 = -155; the four interior corners 0.
     The sum is 620 - 620 = 0 exactly, so the mean is 0 and the variance is the mean
     square: (620^2 + 4*155^2)/9 = 480500/9.
     */
    @Test func matchesTheAnalyticResponseOfAKnownKernel() {
        var pixels = [UInt8](repeating: 255, count: 25)
        pixels[2 * 5 + 2] = 100
        let page = GreyImage(pixels: pixels, width: 5, height: 5)

        #expect(abs(CaptureQuality.laplacianVariance(page) - 480500.0 / 9.0) < 1e-9)
    }

    @Test func aFlatFieldHasNoEdges() {
        #expect(CaptureQuality.laplacianVariance(Self.page(60, 60, band: nil)) == 0)
    }

    @Test func aHardEdgedPageScoresAboveTheSoftFloor() {
        let sharp = CaptureQuality.laplacianVariance(Self.page(200, 200, band: 80..<120))
        #expect(sharp > CaptureQuality.softImageLaplacianVariance)
    }

    /// Same content, half the step at every edge — no edge moves, only its contrast.
    @Test func softeningTheSameContentLowersTheScore() {
        let hard = CaptureQuality.laplacianVariance(Self.page(200, 200, band: 80..<120))
        let soft = CaptureQuality.laplacianVariance(
            Self.page(200, 200, band: 80..<120, ink: 128))
        #expect(soft < hard)
    }

    @Test func anImageTooSmallForTheKernelScoresZeroRatherThanTrapping() {
        #expect(CaptureQuality.laplacianVariance(Self.page(2, 2, band: nil)) == 0)
        #expect(CaptureQuality.laplacianVariance(Self.page(1, 40, band: nil)) == 0)
        #expect(CaptureQuality.laplacianVariance(GreyImage(pixels: [], width: 0, height: 0)) == 0)
    }

    @Test func softnessIsReportedAgainstTheFloor() {
        #expect(CaptureQuality.isSoft(Self.page(120, 120, band: nil)))
        #expect(!CaptureQuality.isSoft(Self.page(200, 200, band: 80..<120)))
    }

    /// One value for one question, across both ports.
    @Test func theFloorMatchesTheWebPort() {
        #expect(CaptureQuality.softImageLaplacianVariance == 100)
    }
}
