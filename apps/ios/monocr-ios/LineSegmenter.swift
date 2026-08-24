import Foundation

// LineSegment is already defined in LineSegment.swift

nonisolated enum LineSegmenter {

    private static let windowSize = 25
    private static let cThreshold = 8 // Lowered to capture faint strokes
    private static let smoothKernel = 3
    private static let minLineHeight = 10

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
            let isText = hist[y] > threshold
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
