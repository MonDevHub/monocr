import Foundation

/**
 How sharp a captured page is, before anything tries to read it.

 WHY. The app advertises a quality bar and has never checked it: `DocsView` tells
 the user "300 DPI min. 600 DPI for manuscripts." and nothing measures resolution,
 sharpness or text size. A soft capture is only diagnosable from bad Mon text,
 which is the least useful signal available.

 It matters more than a general nicety, for two reasons specific to this project.
 `LineSegmenter`'s tuning constants are absolute pixel counts — blur kernel,
 adaptive-threshold window, smear kernels, smoothing width, minimum line height —
 and none of them scales with the resolution of the input, so the pipeline is tuned
 for one text size and nothing says when a capture is nowhere near it. And any move
 to a document scanner (`VNDocumentCameraViewController`) rectifies perspective,
 which is a resample, which is a low-pass filter — so it will soften the image
 while also cropping and deskewing it. Whether that trade helps or hurts Mon is
 unmeasured, and without a sharpness number on this platform it stays unmeasurable.

 Ported from `apps/web/src/lib/capture-quality.ts` so the two surfaces report the
 same quantity. The web version also assesses band height and fused blocks; this is
 the sharpness half only, because `GreyImage` is the seam that is testable without a
 simulator and band height needs the segmenter's padded output.

 WHAT THIS CANNOT DO. Report DPI. A bitmap carries no physical scale. And the floor
 below is a heuristic, not a measurement — see `softImageLaplacianVariance`.
 */
nonisolated enum CaptureQuality {

    /**
     Laplacian-variance floor below which an image reads as soft.

     **A heuristic, not a measurement.** ~100 is the common rule of thumb for 8-bit
     grey and it has been calibrated against nothing in this project. It is set low
     deliberately so a warning is rare and therefore worth reading.

     Calibrating it needs the real-photograph set `mon_OCR/docs/DATA_STRATEGY.md`
     calls rung D1, which does not exist yet. Until then this should log, never gate.
     Same value as the web port so the two agree on what "soft" means.
     */
    static let softImageLaplacianVariance: Double = 100

    /**
     Variance of the 4-neighbour Laplacian over the image interior. Higher is sharper.

     A Laplacian is a second-derivative filter, so it responds to edges; a soft image
     has few strong edges and its response has little spread. Interior only, because
     the kernel needs all four neighbours and clamping at the border would manufacture
     edges that are not in the image.

     Returns 0 for anything too small to hold the kernel, rather than trapping — a
     3x3 minimum is a property of the filter, not a caller error.
     */
    static func laplacianVariance(_ page: GreyImage) -> Double {
        guard page.width >= 3, page.height >= 3 else { return 0 }

        // The naive single-pass form, E[x^2] - E[x]^2. That is the numerically
        // unstable one in general, and it is safe here for a specific reason: a
        // discrete Laplacian over the interior telescopes to boundary terms, so the
        // mean is ~0 for any real image and sumSq/n dominates mean^2 by orders of
        // magnitude. No cancellation to lose.
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        page.pixels.withUnsafeBufferPointer { buf in
            for y in 1..<(page.height - 1) {
                let row = y * page.width
                for x in 1..<(page.width - 1) {
                    let i = row + x
                    // Each conversion is bound to its own `Double` local rather
                    // than summed inline. The inline five-term version compiled
                    // under the Command Line Tools' Swift 6.3.3 and failed under
                    // Xcode 26.3's Swift 6.2.4 with "unable to type-check this
                    // expression in reasonable time" — pointing at line 66, the
                    // `withUnsafeBufferPointer` call, not at the sum itself.
                    //
                    // `buf[...]` is UInt8 and the literal `4` is polymorphic, so
                    // the older solver explores the operator overload space for
                    // the whole closure body at once. Annotating the locals
                    // collapses it. This is a workaround for one compiler
                    // version, not a style preference, so it should outlive the
                    // toolchain that needed it or be removed deliberately.
                    let up: Double = Double(buf[i - page.width])
                    let down: Double = Double(buf[i + page.width])
                    let left: Double = Double(buf[i - 1])
                    let right: Double = Double(buf[i + 1])
                    let centre: Double = Double(buf[i])
                    let response: Double = up + down + left + right - 4 * centre
                    sum += response
                    sumSq += response * response
                    n += 1
                }
            }
        }
        guard n > 0 else { return 0 }
        let mean = sum / Double(n)
        return sumSq / Double(n) - mean * mean
    }

    /// True when the page is soft enough to be worth telling the user about.
    ///
    /// Mon diacritics are thin and stack above and below the line, so they are what
    /// blur removes first — which is why softness is worth surfacing on this script
    /// specifically rather than treated as a generic photography tip.
    static func isSoft(_ page: GreyImage) -> Bool {
        laplacianVariance(page) < softImageLaplacianVariance
    }
}
