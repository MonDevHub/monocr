import Foundation

/**
 Put a page into the polarity the model was trained on, once, before anything
 reads it.

 The bug this fixes: the segmenter has no polarity handling at all, and inversion
 used to happen per line inside `ImagePreprocessor`, AFTER segmentation had
 already run on the un-inverted grey. On a dark-mode screenshot or an inverted
 scan the segmenter therefore measured the BACKGROUND as ink, so the lines it
 returned were the gaps. Inverting later cannot undo that.

 Ported from mon_OCR `src/monocr/utils.py` (`to_normalized_grayscale` and
 `_level_background`). It is not idempotent — running it per line after running
 it per page would level an already-levelled crop — so the per-line inversion was
 removed in the same change that added this.
 */
nonisolated enum PageNormalizer {

    /// Background luminance below this reads as a dark background to invert.
    private static let darkBackgroundMedian = 128.0

    /// Corner patch size, as a fraction of each page dimension, with a 3px floor
    /// so a tiny image still samples something.
    private static let cornerFraction = 10
    private static let cornerFloor = 3

    /// Dark text on a light, level background, whatever the input looked like.
    static func normalize(_ page: GreyImage) -> GreyImage {
        guard page.width > 0, page.height > 0 else { return page }

        let upright = backgroundIsDark(page) ? inverted(page) : page
        return levelBackground(upright)
    }

    /**
     Is the page light text on a dark background?

     Sampled from the four corner patches rather than from a global mean: page
     corners are almost always background, so their median survives a dense,
     text-heavy page where the mean would be dragged down by ink.
     */
    static func backgroundIsDark(_ page: GreyImage) -> Bool {
        guard page.width > 0, page.height > 0 else { return false }

        let patchH = min(max(cornerFloor, page.height / cornerFraction), page.height)
        let patchW = min(max(cornerFloor, page.width / cornerFraction), page.width)

        // Counting sort rather than a real sort: the four patches together can be
        // 4% of a 12MP photo, and only two order statistics are needed.
        var histogram = [Int](repeating: 0, count: 256)
        var count = 0
        let rowBands = [0..<patchH, (page.height - patchH)..<page.height]
        let colBands = [0..<patchW, (page.width - patchW)..<page.width]
        for rows in rowBands {
            for cols in colBands {
                for y in rows {
                    for x in cols {
                        histogram[Int(page.pixel(x: x, y: y))] += 1
                        count += 1
                    }
                }
            }
        }
        guard count > 0 else { return false }

        // numpy's median averages the two middle values of an even sample, and
        // this sample is always even (four patches of equal size).
        let lowerRank = (count - 1) / 2
        let upperRank = count / 2
        var seen = 0
        var lower = 0
        var upper = 0
        for value in 0..<256 {
            seen += histogram[value]
            if lower == 0 && seen > lowerRank { lower = value }
            if seen > upperRank {
                upper = value
                break
            }
        }
        let median = (Double(lower) + Double(upper)) / 2.0
        return median < darkBackgroundMedian
    }

    static func inverted(_ page: GreyImage) -> GreyImage {
        GreyImage(pixels: page.pixels.map { 255 - $0 }, width: page.width, height: page.height)
    }

    /**
     Flatten grey boxes, sepia paper and coloured panels to near-white while
     leaving ink dark.

     Dilation is a max filter, and background pixels are the bright ones, so
     dilating with a kernel far wider than a glyph propagates the local paper
     tone across the ink and yields a smooth background estimate. Dividing by
     that estimate maps any background shade to 255. Already-white pages come
     back unchanged.
     */
    static func levelBackground(_ page: GreyImage) -> GreyImage {
        guard page.width > 0, page.height > 0 else { return page }

        // Downsampled first so the kernel covers proportionally more of the page
        // and the max filter runs on 1/16 of the pixels.
        let smallH = max(1, page.height / 4)
        let smallW = max(1, page.width / 4)
        let small = downsampleArea(page, width: smallW, height: smallH)

        // Kernel covers ~10% of the small image height, forced odd: the upstream
        // balance between smoothing box edges and keeping global brightness
        // gradients.
        let kernel = max(7, (smallH / 4) | 1)
        let background = dilate(small, kernel: kernel)
        let estimate = upsampleBilinear(background, width: page.width, height: page.height)

        var levelled = [UInt8](repeating: 0, count: page.width * page.height)
        for i in 0..<levelled.count {
            // A near-black estimate would blow the quotient up; polarity is
            // already corrected by the time we get here, so clamping at 1 only
            // guards the arithmetic.
            let bg = max(estimate[i], 1.0)
            let value = Float(page.pixels[i]) / bg * 255.0
            levelled[i] = UInt8(max(0.0, min(255.0, value)))
        }
        return GreyImage(pixels: levelled, width: page.width, height: page.height)
    }

    // MARK: - Resampling and morphology

    /// Area-average downsample, matching OpenCV's INTER_AREA: each destination
    /// pixel is the mean of the source rectangle it covers, fractional edges
    /// weighted by overlap.
    static func downsampleArea(_ src: GreyImage, width dstW: Int, height dstH: Int) -> GreyImage {
        guard dstW > 0, dstH > 0, src.width > 0, src.height > 0 else {
            return GreyImage(pixels: [], width: 0, height: 0)
        }

        let scaleX = Double(src.width) / Double(dstW)
        let scaleY = Double(src.height) / Double(dstH)
        var out = [UInt8](repeating: 0, count: dstW * dstH)

        for dy in 0..<dstH {
            let y0 = Double(dy) * scaleY
            let y1 = min(Double(dy + 1) * scaleY, Double(src.height))
            let iy0 = min(Int(y0), src.height - 1)
            let iy1 = min(Int(y1.rounded(.up)), src.height)
            for dx in 0..<dstW {
                let x0 = Double(dx) * scaleX
                let x1 = min(Double(dx + 1) * scaleX, Double(src.width))
                let ix0 = min(Int(x0), src.width - 1)
                let ix1 = min(Int(x1.rounded(.up)), src.width)

                var sum = 0.0
                var weight = 0.0
                for y in iy0..<max(iy1, iy0 + 1) {
                    let wy = min(Double(y + 1), y1) - max(Double(y), y0)
                    if wy <= 0 { continue }
                    for x in ix0..<max(ix1, ix0 + 1) {
                        let wx = min(Double(x + 1), x1) - max(Double(x), x0)
                        if wx <= 0 { continue }
                        sum += wx * wy * Double(src.pixel(x: x, y: y))
                        weight += wx * wy
                    }
                }
                let mean = weight > 0 ? sum / weight : Double(src.pixel(x: ix0, y: iy0))
                out[dy * dstW + dx] = UInt8(max(0.0, min(255.0, mean.rounded())))
            }
        }
        return GreyImage(pixels: out, width: dstW, height: dstH)
    }

    /**
     Grey dilation with a square kernel, clamped at the page edge so pixels
     outside the image never win — the same border rule as OpenCV's dilate,
     whose default border value for dilation is effectively minus infinity.

     Deviation from the port, deliberate and the only one: upstream uses an
     ellipse (`MORPH_ELLIPSE`) and this uses the ellipse's bounding square. A
     square max filter is separable, so it costs two passes whose cost does not
     grow with the kernel; an exact ellipse needs one horizontal pass per
     distinct row half-width plus one vertical pass per band of equal ones.
     Measured on this implementation for a 288 DPI A4 render, which downsamples
     to 595x842 and gives a kernel of 211: the two square passes take 5.6ms, and
     the ellipse's 63 + 125 passes extrapolate to ~0.52s per page — against 62ms
     for this whole function. OpenCV hides that cost behind SIMD; scalar Swift
     does not.

     The square is a superset of the ellipse, so the estimate is never darker
     than upstream's and ink comes out marginally darker. Nothing measures the
     kernel shape; the CER numbers that justify this change came from tiling.
     */
    static func dilate(_ src: GreyImage, kernel: Int) -> GreyImage {
        let radius = max(0, kernel / 2)
        guard radius > 0, src.width > 0, src.height > 0 else { return src }

        let horizontal = slidingMaxRows(src.pixels, width: src.width, height: src.height, radius: radius)
        let both = slidingMaxColumns(horizontal, width: src.width, height: src.height, radius: radius)
        return GreyImage(pixels: both, width: src.width, height: src.height)
    }

    /// Sliding-window maximum along each row, window `[x-radius, x+radius]`
    /// clipped to the row. Monotonic deque, so the cost does not grow with the
    /// window.
    private static func slidingMaxRows(
        _ src: [UInt8], width: Int, height: Int, radius: Int
    ) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: src.count)
        var deque = [Int](repeating: 0, count: width)

        for y in 0..<height {
            let row = y * width
            var head = 0
            var tail = 0
            var next = 0
            for x in 0..<width {
                let hi = min(x + radius, width - 1)
                while next <= hi {
                    let value = src[row + next]
                    while tail > head && src[row + deque[tail - 1]] <= value { tail -= 1 }
                    deque[tail] = next
                    tail += 1
                    next += 1
                }
                let lo = max(0, x - radius)
                while deque[head] < lo { head += 1 }
                out[row + x] = src[row + deque[head]]
            }
        }
        return out
    }

    /// The same window, down each column.
    private static func slidingMaxColumns(
        _ src: [UInt8], width: Int, height: Int, radius: Int
    ) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: src.count)
        var deque = [Int](repeating: 0, count: height)

        for x in 0..<width {
            var head = 0
            var tail = 0
            var next = 0
            for y in 0..<height {
                let hi = min(y + radius, height - 1)
                while next <= hi {
                    let value = src[next * width + x]
                    while tail > head && src[deque[tail - 1] * width + x] <= value { tail -= 1 }
                    deque[tail] = next
                    tail += 1
                    next += 1
                }
                let lo = max(0, y - radius)
                while deque[head] < lo { head += 1 }
                out[y * width + x] = src[deque[head] * width + x]
            }
        }
        return out
    }

    /// Bilinear upsample to the full page, using OpenCV's half-pixel centres so
    /// the estimate lines up with the pixels it will divide.
    static func upsampleBilinear(_ src: GreyImage, width: Int, height: Int) -> [Float] {
        var out = [Float](repeating: 0, count: width * height)
        guard src.width > 0, src.height > 0, width > 0, height > 0 else { return out }

        let scaleX = Double(src.width) / Double(width)
        let scaleY = Double(src.height) / Double(height)

        for y in 0..<height {
            let sy = max(0.0, (Double(y) + 0.5) * scaleY - 0.5)
            let y0 = min(Int(sy), src.height - 1)
            let y1 = min(y0 + 1, src.height - 1)
            let fy = Float(sy - Double(y0))
            for x in 0..<width {
                let sx = max(0.0, (Double(x) + 0.5) * scaleX - 0.5)
                let x0 = min(Int(sx), src.width - 1)
                let x1 = min(x0 + 1, src.width - 1)
                let fx = Float(sx - Double(x0))

                let top = Float(src.pixel(x: x0, y: y0)) * (1 - fx) + Float(src.pixel(x: x1, y: y0)) * fx
                let bottom = Float(src.pixel(x: x0, y: y1)) * (1 - fx) + Float(src.pixel(x: x1, y: y1)) * fx
                out[y * width + x] = top * (1 - fy) + bottom * fy
            }
        }
        return out
    }
}
