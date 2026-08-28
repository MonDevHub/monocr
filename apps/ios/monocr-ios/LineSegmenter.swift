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
     Two runs separated by at most this many rows are one text line, provided the
     raw profile never reaches zero inside the gap.

     WHY THIS EXISTS. Boundaries come off the raw profile (see step 6), and on Mon
     text that splits a single line between the upper diacritic zone and the
     consonant bodies: the diacritics are sparse, so the rows between them and the
     bodies carry a little ink or none, and either way they fall under a threshold
     that is 3% of the mean. The strip of glyph tops then decodes as Mon digits,
     because a row of circle-tops IS digits, and the decapitated body decodes
     without its asats because the asat went with the strip.

     MEASURED ON THIS PORT, 2026-08-28, at ITS parameters - density ratio 0.03,
     `minLineHeight` 10, 3x3 grey blur, smear kernel 11x5 - over a page of 10
     Mon-shaped lines: a 20-row sparse strip above a 45-row body, with 0, 1 or 2
     strokes bridging the gap. Rust's figures do not transfer, and neither do web's
     even though the kernels match, for the reason noted below:

         source gap   bridging strokes   dip row raw ink   bands (10 drawn)
         0-6 rows     any                372-650           10   blur+dilation close it
         8 rows       1                  14 vs thr 16.43   *20* -> 10 after the merge
         8 rows       2                  28 vs thr 16.56   10   above threshold
         8 rows       0                  0                 *20* see the note below

     So the split needs an 8-row source gap here, against 1 row in Rust. The
     14-columns-against-16.43 row is this port's version of the reference's measured
     `row 280 carrying 6 ink pixels against a threshold of 7.0`, and the ink clause
     rejoins it.

     THE ZERO-INK CASE IS NARROWER HERE THAN IN RUST, and the reason is the
     dilation. Blur plus reach-2 dilation adds about six rows to EVERY run, which
     inflates a short strip proportionally far more than a full line: measured, a
     20-row strip above a 45-row body becomes a 26-row run against a 51-row one, and
     `2 * 26 > 51` so the fragment clause declines and the line stays split. At an
     18-row strip - a 24-row run - it merges, returning 10 bands of 117px. Applying
     the measured +6 to Rust's own pair, 19 rows against 42, gives 25 against 48
     here, which would not qualify either; that last pair is an inference from the
     growth above rather than a measurement of that pair. That is the clause behaving as specified rather than a
     porting error: a run more than half a typical line IS a line by this test. It is
     recorded because it means the ink clause carries more of the load on the
     dilating ports than it does in Rust, and because raising the ratio to cover it
     would be an unmeasured constant. Android measures the same effect at 27-vs-51.

     WEB'S NUMBERS DO NOT TRANSFER even though its kernels are identical, and the
     reason is a pre-existing divergence in binarization rather than anything here:
     web compares the RAW grey value against the smoothed local mean
     (`grayData[i] < mean - C`), while this port and Android compare the SMOOTHED
     value (`activeGray[i] < mean - C`). Web therefore splits at a 5-row source gap
     where this port needs 8. Recorded rather than changed: aligning the three
     changes which pixels are ink on every page, which needs its own measurement.

     Two clauses, because one does not cover it. The ink test crosses a dip that
     stays above zero. A zero-ink gap is one no ink test can cross, so the second
     clause is a height ratio: a run at most half a typical line is a fragment of a
     line, not a line. Two REAL lines 8 rows apart are each full height, so they
     stay apart.

     The size bound is the third condition and does a different job: it refuses to
     merge real inter-line spacing even when overlapping diacritics hold the raw
     profile above zero right across it.

     Ported from `monocr-onnx` `rust/src/segmenter.rs` (`MIN_GAP_MERGE`,
     `merge_runs`), which took it from `mon_OCR` `segmenter.py` step 8. The value is
     the reference's.
     */
    private static let minGapMerge = 10

    /**
     Fuse runs that a sub-threshold dip or a few empty rows split apart.

     `rawHist` is the RAW profile, and the name is deliberate: this file's `hist` is
     the SMOOTHED one. Passing the smoothed profile here would test for ink in rows
     that only borrowed it from their neighbours.

     Internal rather than private so the arithmetic is testable without building a
     page, and called from `segment` BEFORE the height filter - see the call site
     for why the order is load-bearing.

     Runs are `(start, end)` pairs with `end` one past the last row.

     Both tests are relative to the page's own median run height rather than to the
     neighbouring run, and that is a correction rather than a preference: judging a
     fragment against its neighbour CASCADES. The merge mutates the accumulated run,
     so every merge makes it taller, and a taller run makes the next line look more
     like a fragment. Measured in the Rust port on page 47 of a 56-page book: 36
     bands collapsed to 10, with single bands of 534, 632 and 732 rows, and the page
     lost 92% of its readable characters. `ceiling` is the backstop for that, and
     twice a typical line rather than tighter because a legitimate merge of two
     halves lands at about one typical line and must not be refused.
     */
    static func mergeRuns(
        _ runs: [(Int, Int)],
        rawHist: [Float],
        maxGap: Int
    ) -> [(Int, Int)] {
        if runs.isEmpty { return [] }

        let heights = runs.map { $0.1 - $0.0 }.sorted()
        let typical = max(1, heights[heights.count / 2])
        let ceiling = typical * 2

        var merged = [(Int, Int)]()
        merged.reserveCapacity(runs.count)
        for (r0, r1) in runs {
            if let last = merged.last {
                let gapStart = last.1
                let gapSize = max(0, r0 - gapStart)
                // A caller can hand us touching runs; an empty range is vacuously
                // inked, which treats them as already one line.
                var gapHasInk = true
                if gapStart < r0 {
                    // Negated rather than `<= 0`, so a NaN reads as "no ink" the way
                    // Rust's `v > 0.0` and web's `!(rawHist[y] > 0)` do. A row count
                    // cannot be NaN today; the four are kept identical because
                    // divergence between them is the defect this pass exists to stop.
                    for y in gapStart..<r0 where y < 0 || y >= rawHist.count || !(rawHist[y] > 0) {
                        gapHasInk = false
                        break
                    }
                }
                let fragment = 2 * (r1 - r0) <= typical || 2 * (last.1 - last.0) <= typical

                if gapSize <= maxGap && (gapHasInk || fragment) && r1 - last.0 <= ceiling {
                    merged[merged.count - 1] = (last.0, r1)
                    continue
                }
            }
            merged.append((r0, r1))
        }
        return merged
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
        // [start, end) pairs, end one past the last row.
        var runs = [(Int, Int)]()
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
                if let sY = startY { runs.append((sY, y)) }
                startY = nil
            }
        }

        if let sY = startY { runs.append((sY, height)) }

        // 6.5 Fuse runs a sub-threshold dip or a few empty rows split apart, BEFORE
        // the height filter. The order is the reference's and it matters: a
        // diacritic strip can be shorter than `minLineHeight`, and filtering first
        // discards the strip and leaves the decapitated body behind as a whole line.
        //
        // Measured on this port with a 3-row source strip above an 8-row gap, whose
        // run the blur and dilation grow to 9 rows - one short of `minLineHeight`.
        // Filtering first returned 10 bands 77px tall; this order returns 10 bands
        // 94px tall, and the 17px difference IS the strip of upper marks. Both counts
        // are 10, so no band count can catch this.
        for (r0, r1) in mergeRuns(runs, rawHist: rawHist, maxGap: minGapMerge)
        where r1 - r0 >= minLineHeight {
            addSegment(smeared: smeared, width: width, height: height, sY: r0, eY: r1, into: &rawSegments)
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
