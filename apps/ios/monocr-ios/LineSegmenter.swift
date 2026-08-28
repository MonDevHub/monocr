import Foundation

// LineSegment is already defined in LineSegment.swift

nonisolated enum LineSegmenter {

    private static let windowSize = 25
    private static let cThreshold = 8 // Lowered to capture faint strokes
    private static let smoothKernel = 3
    private static let minLineHeight = 10

    /// A printed rule spans at least this fraction of the page in one direction.
    ///
    /// Deliberately coarse: no Mon, Burmese or Latin glyph holds an unbroken stroke
    /// half a page long, so the false-positive risk against text is structural
    /// rather than merely small. Lowering it toward a glyph's width is what would
    /// make rule suppression dangerous.
    static let ruleSpan: Float = 0.5

    /// Suppression that would remove more than this share of the page's ink has
    /// found text, not rules, and is abandoned.
    ///
    /// `ruleSpan` is a fraction of the page, so on a SHORT page a tall block of
    /// text exceeds it vertically and every glyph column reads as a rule. Upstream
    /// this was caught by an existing test losing 98.7% of its ink and returning
    /// zero lines.
    ///
    /// The threshold sits in a measured gap rather than on a round number: real
    /// framed pages classify 21.5%-58.8% of their ink as rules, pages with no rules
    /// 0.00%, and that false positive 98.7%.
    static let ruleMaxInkShare: Float = 0.8

    /// A band taller than this fraction of the page is unlikely to be one line.
    private static let implausibleLineFraction: Float = 0.40

    /// A band at least this wide relative to its height is line-shaped.
    private static let lineShapeAspect: Float = 4.0

    /**
     Is this band shaped like one line of text, or is it a fused block?

     The projection profile can return a band covering most of the page when the
     gaps between lines never fall under the density threshold — which is a
     fraction of the mean, and sits below the noise floor of a photograph.
     Nothing downstream notices: the recogniser scales whatever it is given to
     the model height and answers, and it does not answer "I cannot read this".
     Measured upstream 2026-08-15, a 493px band on a 760px page came back as
     fluent Mon that appears nowhere on the page, at confidence 0.83.

     So confidence cannot be the filter — it was 0.83 on that fabrication and
     0.00 on a genuinely blank crop. Shape can. Two conditions, because either
     alone gets a real case wrong: page fraction alone rejects a single-line crop,
     which is 100% of its own image, and aspect alone rejects a short word, which
     can be taller than it is wide. A band has to fail both to be called a block.

     This drops nothing. Callers decide what to do with an unreliable reading.

     Ported from mon_OCR `src/monocr/segmenter.py` (`looks_like_a_line`).
     */
    static func looksLikeALine(bbox: LineSegment, pageHeight: Int) -> Bool {
        guard bbox.height > 0, pageHeight > 0 else { return false }
        let fillsThePage = Float(bbox.height) > Float(pageHeight) * implausibleLineFraction
        let lineShaped = Float(bbox.width) / Float(bbox.height) >= lineShapeAspect
        return lineShaped || !fillsThePage
    }

    /**
     Remove printed rules - page borders, table rules, underlines - from a text mask.

     A printed page border adds a constant ink floor to every row it spans, and once
     that floor clears the gap threshold no in-frame row reads as a gap: the page
     comes back as one band and is squeezed into the model window.

     Measured upstream 2026-08-27 over twelve real MNEC page-ones: nine collapsed to
     three bands or fewer, and the twelve together went from 68 bands to 160. Pages
     carrying no rules are untouched to the pixel, which is what makes the pass safe
     to run unconditionally.

     Implemented as a run-length scan rather than a generic erode-then-dilate. An
     opening with a 1xL line kernel keeps exactly those ink runs at least L long, and
     a run-length pass computes that directly in one sweep per axis instead of two
     passes over an L-wide window.

     There is deliberately NO thickness test. "A rule is long AND thin" was written,
     measured and deleted upstream: the rule pixels found with a thickness limit and
     with none were identical across twelve real pages, and it cannot work anyway -
     an adaptive threshold compares against a LOCAL mean, so the interior of a thick
     ink region is not ink and only its edges are.

     Ported from `mon_OCR/src/monocr/segmenter.py` (`_suppress_page_rules`) via
     `apps/web/src/lib/segmentation.ts` (`suppressPageRules`), whose run-length form
     this follows exactly so the three stay comparable.

     Mutates `binary` in place and returns whether anything was removed.
     */
    static func suppressPageRules(_ binary: inout [Bool], width: Int, height: Int) -> Bool {
        guard width > 0, height > 0 else { return false }
        // Stated rather than left to an index trap further in, the same argument
        // GreyImage's init makes. The web port used to return a plausible result
        // here, having silently skipped the rows it could not reach.
        precondition(
            binary.count == width * height,
            "rule mask is \(binary.count) long but \(width)x\(height) needs \(width * height)"
        )
        let minH = max(15, Int(Float(width) * ruleSpan))
        let minV = max(15, Int(Float(height) * ruleSpan))
        var rules = [Bool](repeating: false, count: width * height)

        // Horizontal runs: mark any unbroken run of at least minH.
        for y in 0..<height {
            let row = y * width
            var start = 0
            for x in 0...width {
                if x < width && binary[row + x] { continue }
                if x - start >= minH { for i in start..<x { rules[row + i] = true } }
                start = x + 1
            }
        }
        // Vertical runs: the same scan down each column.
        for x in 0..<width {
            var start = 0
            for y in 0...height {
                if y < height && binary[y * width + x] { continue }
                if y - start >= minV { for i in start..<y { rules[i * width + x] = true } }
                start = y + 1
            }
        }

        var ink = 0
        for v in binary where v { ink += 1 }
        if ink == 0 { return false }

        var ruleInk = 0
        for v in rules where v { ruleInk += 1 }
        if ruleInk == 0 { return false }
        // Integer arithmetic, not `ruleInk > ink * RULE_MAX_INK_SHARE`.
        //
        // 0.8 is not representable in binary, and Kotlin and Swift evaluate that
        // product in Float where TS and the fixture generator use double. At
        // ink = 5_242_881 and ruleInk = 4_194_305 the two disagree: double gives
        // 4_194_304.8 and abandons, float32 rounds the product to exactly
        // 4_194_305.0 and suppresses ~80% of the page's ink. That is the precise
        // failure this ceiling exists to prevent, and `OcrRepository` hands the
        // segmenter an un-resized bitmap, so a 12 MP page reaches that ink count.
        // No smaller pair diverges. `x * 5 > y * 4` is exact for every input.
        if ruleInk * 5 > ink * 4 {
            // Found the text. Leaving the page alone is strictly better than
            // emptying it, and the caller is no worse off than before this
            // step existed.
            return false
        }

        for i in 0..<rules.count where rules[i] { binary[i] = false }
        return true
    }

    /**
     Cut a page into text lines.

     `densityThresholdRatio` is the row-density threshold as a fraction of the
     mean, and it is a parameter because no single value fits both a book page
     and a slide; see `SegmentationMode`. The page must already be
     polarity-normalised — this profile treats dark pixels as ink and has no way
     to notice that it was handed a dark-mode screenshot.
     */
    static func segment(page: GreyImage, densityThresholdRatio: Float) -> [LineSegment] {
        let width = page.width
        let height = page.height
        guard width > 0, height > 0 else { return [] }
        let gray = page.pixels

        // 1b. Smooth Grayscale (3x3 Box Blur)
        var smoothedGray = [UInt8](repeating: 0, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                var sum: Int = 0
                var count: Int = 0
                for ky in -1...1 {
                    for kx in -1...1 {
                        let ny = y + ky; let nx = x + kx
                        if ny >= 0 && ny < height && nx >= 0 && nx < width {
                            sum += Int(gray[ny * width + nx])
                            count += 1
                        }
                    }
                }
                smoothedGray[y * width + x] = UInt8(sum / count)
            }
        }
        let activeGray = smoothedGray
        
        // 2. Integral Image for Binarization (using smoothed buffer)
        var integral = [Int64](repeating: 0, count: width * height)
        for y in 0..<height {
            var rowSum: Int64 = 0
            for x in 0..<width {
                rowSum += Int64(activeGray[y * width + x])
                integral[y * width + x] = rowSum + (y > 0 ? integral[(y - 1) * width + x] : 0)
            }
        }
        
        var binary = [Bool](repeating: false, count: width * height)
        let halfWin = windowSize / 2
        for y in 0..<height {
            for x in 0..<width {
                let x1 = max(0, x - halfWin); let x2 = min(width - 1, x + halfWin)
                let y1 = max(0, y - halfWin); let y2 = min(height - 1, y + halfWin)
                let a = (x1 > 0 && y1 > 0) ? integral[(y1 - 1) * width + (x1 - 1)] : 0
                let b = y1 > 0 ? integral[(y1 - 1) * width + x2] : 0
                let c = x1 > 0 ? integral[y2 * width + (x1 - 1)] : 0
                let sum = integral[y2 * width + x2] - b - c + a
                let count = (x2 - x1 + 1) * (y2 - y1 + 1)
                let mean = Float(sum) / Float(count)
                binary[y * width + x] = Float(activeGray[y * width + x]) < (mean - Float(cThreshold))
            }
        }
        
        // 2.5 Printed-rule suppression. Before the smear, because the smear widens a
        // rule into something no line kernel matches cleanly, and because the crop's
        // column extents come from the smeared mask - removing rules first also keeps
        // them out of the returned bounding boxes.
        _ = suppressPageRules(&binary, width: width, height: height)

        // 3. Separable 2D Morphological Filtering (Smearing)
        let halfSmearX = 5 // kernel 11
        let halfSmearY = 2 // kernel 5
        
        var smearedH = [Bool](repeating: false, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                var found = false
                let start = max(0, x - halfSmearX); let end = min(width - 1, x + halfSmearX)
                for kx in start...end { if binary[y * width + kx] { found = true; break } }
                smearedH[y * width + x] = found
            }
        }
        
        var smeared = [Bool](repeating: false, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                var found = false
                let start = max(0, y - halfSmearY); let end = min(height - 1, y + halfSmearY)
                for ky in start...end { if smearedH[ky * width + x] { found = true; break } }
                smeared[y * width + x] = found
            }
        }
        
        // 4. Horizontal Projection Profile (on smeared data)
        var rawHist = [Float](repeating: 0.0, count: height)
        for y in 0..<height {
            var count = 0
            for x in 0..<width { if smeared[y * width + x] { count += 1 } }
            rawHist[y] = Float(count)
        }
        
        // 5. Smoothing
        var hist = [Float](repeating: 0.0, count: height)
        let halfK = smoothKernel / 2
        for y in 0..<height {
            var sum: Float = 0; var cnt = 0
            for k in -halfK...halfK {
                let ky = y + k
                if ky >= 0 && ky < height { sum += rawHist[ky]; cnt += 1 }
            }
            hist[y] = sum / Float(cnt)
        }
        
        let nonZeroHist = hist.filter { $0 > 0 }
        let meanDensity = nonZeroHist.isEmpty ? 0 : nonZeroHist.reduce(0, +) / Float(nonZeroHist.count)
        let threshold = meanDensity * densityThresholdRatio
        
        // 6. Extract Segments
        var rawSegments = [LineSegment]()
        var startY: Int? = nil
        
        for y in 0..<height {
            // the RAW profile, not the smoothed one.
            //
            // The threshold is still calibrated from the smoothed mean, because a mean is more
            // stable with smoothing. Boundaries are detected on the raw profile, because
            // smoothing bleeds ink across a true inter-line gap narrower than about half the
            // kernel, and a bled gap never falls under the threshold.
            //
            // The reference states this and says why (`mon_OCR/src/monocr/segmenter.py`,
            // "Valley detection (dual-histogram)"). All three ports read the smoothed profile
            // here instead, and the cost was measured on this port before changing it: pages of
            // 14px lines separated by 5, 6 and 8 pixels came back as ONE band each, against 29,
            // 28 and 25 lines actually drawn. Reading the raw profile returns exactly the drawn
            // count. At 12px and wider the two agree exactly, and every pre-existing test in all
            // three suites passes either way.
            //
            // Gaps under 5px still fuse, and that is the vertical smear doing its job rather
            // than this line: a kernel of 5 is meant to bridge a 4px gap so a floating diacritic
            // stays attached to its base.
            let isText = rawHist[y] > threshold
            if isText && startY == nil {
                startY = y
            } else if !isText && startY != nil {
                if let sY = startY, y - sY >= minLineHeight {
                    addSegment(smeared: smeared, width: width, height: height, sY: sY, eY: y, into: &rawSegments)
                }
                startY = nil
            }
        }
        
        if let sY = startY, height - sY >= minLineHeight {
            addSegment(smeared: smeared, width: width, height: height, sY: sY, eY: height, into: &rawSegments)
        }
        
        // 7. Post-processing: Outlier Rejection (Logos/Graphics/Noise)
        let clearText = rawSegments.filter { Float($0.width) / Float($0.height) >= 2.0 }
        var medianH: Float = 0
        if !clearText.isEmpty {
            let sortedHeights = clearText.map { Float($0.height) }.sorted()
            medianH = sortedHeights[sortedHeights.count / 2]
        }
        
        return rawSegments.filter { seg in
            let ratio = Float(seg.width) / Float(seg.height)
            if ratio < 0.2 || seg.width < 10 || seg.height < 10 { return false }
            if medianH > 0 && ratio < 2.5 && Float(seg.height) > medianH * 2.5 { return false }
            return true
        }
    }
    
    private static func addSegment(smeared: [Bool], width: Int, height: Int, sY: Int, eY: Int, into list: inout [LineSegment]) {
        var minX = width
        var maxX = 0
        var found = false
        
        for y in sY..<eY {
            for x in 0..<width {
                if smeared[y * width + x] {
                    if x < minX { minX = x }
                    if x > maxX { maxX = x }
                    found = true
                }
            }
        }
        
        if found {
            let coreH = eY - sY
            // iOS Specific Padding Rules (increased for PDF safety)
            // 25% vertical, 20% horizontal
            let padY = Int(ceil(Float(coreH) * 0.25))
            let padX = Int(ceil(Float(coreH) * 0.20))
            
            let x1 = max(0, minX - padX)
            let x2 = min(width, maxX + padX)
            let y1 = max(0, sY - padY)
            let y2 = min(height, eY + padY)
            list.append(LineSegment(x: x1, y: y1, width: x2 - x1, height: y2 - y1))
        }
    }
}
