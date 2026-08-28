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
        // -1, not 0, as the "not found yet" sentinel. 0 is a legal luminance — a
        // genuinely black corner — so using it for both meanings meant the sentinel
        // never cleared on such a page and `lower` was reassigned once more, coming
        // out 1 instead of 0. Measured on the web port, which was a line-for-line
        // copy of this: a page half black and half white reported median 128 against
        // a true 127.5, landing on the wrong side of `darkBackgroundMedian` — so the
        // inverted scan this function exists to catch was read as a light page.
        var lower = -1
        var upper = -1
        for value in 0..<256 {
            seen += histogram[value]
            if lower == -1 && seen > lowerRank { lower = value }
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
     Dilate with a DISK, matching `cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))`.

     **This was a square until 2026-08-28, and that was wrong.** The reference
     (`mon_OCR/src/monocr/utils.py`, `_level_background`) asks cv2 for `MORPH_ELLIPSE`,
     and Android's `PageNormalizer.dilateDisk` has always matched it. A square contains
     the inscribed disk, so the square propagated more background over the ink, the
     estimate came back brighter, and dividing by it made every iOS page 0.13%-0.34%
     darker than the same page on Android. Measured over eight synthetic pages, seven
     disagreed; only the 1x1 case matched.

     The old test did not catch it because it compared this function against a naive
     max filter that was also a square: it pinned the sliding-window optimisation
     correctly and never asked what shape was being optimised.

     cv2's ellipse is not the geometric disk `dx^2 + dy^2 <= r^2` — that disagrees with
     it at every kernel size tested. It is the per-row half-width `round(sqrt(r^2 - dy^2))`,
     which agrees at all of them, so that is what this builds.

     Ported from Android's implementation, which is the one already verified against
     cv2, rather than rewritten: two independent derivations of the same shape is how
     the ports drifted apart in the first place.

     **It costs more than the square did, and the old comment here was right to say
     so.** A square max filter is separable, so two monotonic-deque passes cost the
     same whatever the radius; a disk needs one horizontal growth pass per radius
     plus one vertical fold per kernel row. Measured on this machine with `swiftc -O`
     at the size a 12 MP photo actually produces — 3024x4032 downsamples to 756x1008,
     and `max(7, (1008 / 4) | 1)` gives a kernel of 253:

         square, separable      10.5 ms
         disk                  125.8 ms

     That is the price of computing what the reference computes, and Android has
     always paid it — its own comment budgets "a few hundred milliseconds" for this
     function on a 300 DPI page. The old comment used this cost to justify the square;
     the cost was real and the conclusion was not, because it traded a correctness
     property nothing was measuring for milliseconds nothing was waiting on.

     The 125.8 ms is with `UInt8` buffers rather than `Int` (a third of the memory
     traffic: three 762k arrays at 1 byte instead of 8), `swap` rather than array
     assignment, and clipped `y` ranges instead of a per-row bounds test. The
     straightforward form measured 188.5 ms and produces bit-identical output.
     */
    static func dilate(_ src: GreyImage, kernel: Int) -> GreyImage {
        let r = max(0, kernel / 2)
        guard r > 0, src.width > 0, src.height > 0 else { return src }

        let w = src.width
        let h = src.height

        // Half-width of the disk on each row of the structuring element.
        var halfWidth = [Int](repeating: 0, count: 2 * r + 1)
        for i in 0...(2 * r) {
            let dy = Double(i - r)
            halfWidth[i] = Int((Double(r * r) - dy * dy).squareRoot().rounded())
        }

        // `current` holds src dilated horizontally by d, grown one step per pass.
        // 0 is the identity for a maximum over 0...255 data, so no sentinel is needed
        // and these can stay UInt8.
        var current = src.pixels
        var next = [UInt8](repeating: 0, count: w * h)
        var out = [UInt8](repeating: 0, count: w * h)

        for d in 0...r {
            if d >= 1 && w > 1 {
                current.withUnsafeBufferPointer { cur in
                    next.withUnsafeMutableBufferPointer { nxt in
                        for y in 0..<h {
                            let base = y * w
                            // Half-width d covers [x-d, x+d]. For d == 1 that needs
                            // the centre too; from d == 2 on, the two half-width
                            // d-1 windows either side of x already cover it.
                            var first: UInt8 = d == 1 ? cur[base] : 0
                            if cur[base + 1] > first { first = cur[base + 1] }
                            nxt[base] = first

                            if w > 2 {
                                for x in 1..<(w - 1) {
                                    var m: UInt8 = d == 1 ? cur[base + x] : 0
                                    let left = cur[base + x - 1]
                                    let right = cur[base + x + 1]
                                    if left > m { m = left }
                                    if right > m { m = right }
                                    nxt[base + x] = m
                                }
                            }

                            var last: UInt8 = d == 1 ? cur[base + w - 1] : 0
                            if cur[base + w - 2] > last { last = cur[base + w - 2] }
                            nxt[base + w - 1] = last
                        }
                    }
                }
                // `next` is fully overwritten on every pass, so swapping is safe and
                // avoids the copy an assignment would make.
                swap(&current, &next)
            }

            for i in 0...(2 * r) where halfWidth[i] == d {
                let dy = i - r
                let yLo = max(0, -dy)
                let yHi = min(h, h - dy)
                if yLo >= yHi { continue }
                current.withUnsafeBufferPointer { cur in
                    out.withUnsafeMutableBufferPointer { o in
                        for y in yLo..<yHi {
                            let dstRow = y * w
                            let srcRow = (y + dy) * w
                            for x in 0..<w {
                                let v = cur[srcRow + x]
                                if v > o[dstRow + x] { o[dstRow + x] = v }
                            }
                        }
                    }
                }
            }
        }

        return GreyImage(pixels: out, width: w, height: h)
    }

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
