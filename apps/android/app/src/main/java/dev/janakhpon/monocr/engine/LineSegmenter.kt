package dev.janakhpon.monocr.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One band of the page, in page coordinates.
 *
 * [looksLikeALine] is the shape verdict from [LineSegmenter.looksLikeALine]. It does
 * not drop anything; callers decide what to do with a band that is shaped like a
 * block.
 */
data class LineSegment(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val looksLikeALine: Boolean = true
)

/**
 * Segments a page into text lines using a horizontal projection profile.
 *
 * Takes a [GreyImage] rather than a bitmap, and expects it already normalised to
 * dark ink on white by [PageNormalizer]. Reading polarity per line after this ran
 * was the old bug: the profile measured the background of an inverted page as ink.
 */
object LineSegmenter {

    private const val WINDOW_SIZE = 25
    private const val C_THRESHOLD = 8
    private const val SMOOTH_KERNEL = 5
    internal const val MIN_LINE_HEIGHT = 10

    /**
     * A printed rule spans at least this fraction of the page in one direction.
     *
     * Deliberately coarse: no Mon, Burmese or Latin glyph holds an unbroken stroke
     * half a page long, so the false-positive risk against text is structural rather
     * than merely small. Lowering it toward a glyph's width is what would make rule
     * suppression dangerous.
     */
    const val RULE_SPAN = 0.5f

    /**
     * Suppression that would remove more than this share of the page's ink has found
     * text, not rules, and is abandoned.
     *
     * [RULE_SPAN] is a fraction of the page, so on a SHORT page a tall block of text
     * exceeds it vertically and every glyph column reads as a rule. Upstream this was
     * caught by an existing test losing 98.7% of its ink and returning zero lines.
     *
     * The threshold sits in a measured gap rather than on a round number: real framed
     * pages classify 21.5%-58.8% of their ink as rules, pages with no rules 0.00%,
     * and that false positive 98.7%.
     */
    const val RULE_MAX_INK_SHARE = 0.8f

    /**
     * A band taller than this fraction of the page is too tall to be one line.
     * Upstream, across the sample images and both PDFs, the tallest band on a
     * readable multi-line page was 29% of page height, against 65% and 100% on the
     * two camera photos the model cannot read.
     */
    private const val IMPLAUSIBLE_LINE_FRACTION = 0.40f

    /** Wider than this against its own height and it is line-shaped, whatever its height. */
    // Not private: `SegmentationMode.forGalleryImage` needs the same number for the
    // same judgement. Two constants for one question drift apart, and this
    // ecosystem has a documented history of exactly that.
    const val LINE_SHAPE_ASPECT = 4.0f

    /**
     * Is this band shaped like one line of text, or is it a fused block?
     *
     * The projection profile can return a band covering most of the page when the
     * gaps between lines never fall under the density threshold — which is a fraction
     * of the mean, and sits below the noise floor of a photograph. Nothing downstream
     * notices. The recogniser scales whatever it is given to 160px and answers, and it
     * does not answer "I cannot read this": measured upstream 2026-08-15, a 493px band
     * on a 760px page came back as fluent Mon meaning "Mudon township" at confidence
     * 0.83, appearing nowhere on the page. So confidence cannot be the filter. Shape can.
     *
     * Two conditions, because either alone gets a real case wrong. Page fraction alone
     * rejects a single-line crop, which is 100% of its own image. Aspect alone rejects
     * a short word on a page, which can be taller than it is wide. A band has to fail
     * both to be called a block.
     *
     * Ported from mon_OCR `segmenter.looks_like_a_line`; same two constants.
     */
    fun looksLikeALine(bbox: LineSegment, pageHeight: Int): Boolean {
        if (bbox.height <= 0 || pageHeight <= 0) return false
        val fillsThePage = bbox.height > pageHeight * IMPLAUSIBLE_LINE_FRACTION
        val lineShaped = bbox.width.toFloat() / bbox.height >= LINE_SHAPE_ASPECT
        return lineShaped || !fillsThePage
    }

    /**
     * Remove printed rules - page borders, table rules, underlines - from a text mask.
     *
     * A printed page border adds a constant ink floor to every row it spans, and once
     * that floor clears the gap threshold no in-frame row reads as a gap: the page
     * comes back as one band and is squeezed into the model window.
     *
     * Measured upstream 2026-08-27 over twelve real MNEC page-ones: nine collapsed to
     * three bands or fewer, and the twelve together went from 68 bands to 160. Pages
     * carrying no rules are untouched to the pixel, which is what makes the pass safe
     * to run unconditionally.
     *
     * Implemented as a run-length scan rather than a generic erode-then-dilate. An
     * opening with a 1xL line kernel keeps exactly those ink runs at least L long, and
     * a run-length pass computes that directly in one sweep per axis instead of two
     * passes over an L-wide window.
     *
     * There is deliberately NO thickness test. "A rule is long AND thin" was written,
     * measured and deleted upstream: the rule pixels found with a thickness limit and
     * with none were identical across twelve real pages, and it cannot work anyway -
     * an adaptive threshold compares against a LOCAL mean, so the interior of a thick
     * ink region is not ink and only its edges are.
     *
     * Ported from `mon_OCR/src/monocr/segmenter.py` (`_suppress_page_rules`) via
     * `apps/web/src/lib/segmentation.ts` (`suppressPageRules`), whose run-length form
     * this follows exactly so the three stay comparable.
     *
     * Mutates [binary] in place and returns whether anything was removed.
     */
    fun suppressPageRules(binary: BooleanArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        // Stated rather than left to an index error further in, the same argument
        // GreyImage's init makes: a buffer that does not match its dimensions
        // produces a wrong answer at some later offset instead of a failure at the
        // mistake. The web port used to return a plausible result here, having
        // silently skipped the rows it could not reach.
        require(binary.size == width * height) {
            "rule mask is ${binary.size} long but ${width}x$height needs ${width * height}"
        }
        val minH = maxOf(15, (width * RULE_SPAN).toInt())
        val minV = maxOf(15, (height * RULE_SPAN).toInt())
        val rules = BooleanArray(width * height)

        // Horizontal runs: mark any unbroken run of at least minH.
        for (y in 0 until height) {
            val row = y * width
            var start = 0
            for (x in 0..width) {
                if (x < width && binary[row + x]) continue
                if (x - start >= minH) for (i in start until x) rules[row + i] = true
                start = x + 1
            }
        }
        // Vertical runs: the same scan down each column.
        for (x in 0 until width) {
            var start = 0
            for (y in 0..height) {
                if (y < height && binary[y * width + x]) continue
                if (y - start >= minV) for (i in start until y) rules[i * width + x] = true
                start = y + 1
            }
        }

        var ink = 0
        for (v in binary) if (v) ink++
        if (ink == 0) return false

        var ruleInk = 0
        for (v in rules) if (v) ruleInk++
        if (ruleInk == 0) return false
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
        if (ruleInk.toLong() * 5 > ink.toLong() * 4) {
            // Found the text. Leaving the page alone is strictly better than emptying
            // it, and the caller is no worse off than before this step existed.
            return false
        }

        for (i in rules.indices) if (rules[i]) binary[i] = false
        return true
    }

    /**
     * Two runs separated by at most this many rows are one text line, provided the
     * raw profile never reaches zero inside the gap.
     *
     * WHY THIS EXISTS. Boundaries come off the raw profile (see step 6), and on Mon
     * text that splits a single line between the upper diacritic zone and the
     * consonant bodies: the diacritics are sparse, so the rows between them and the
     * bodies carry a little ink or none, and either way they fall under a threshold
     * that is 3% of the mean. The strip of glyph tops then decodes as Mon digits,
     * because a row of circle-tops IS digits, and the decapitated body decodes
     * without its asats because the asat went with the strip.
     *
     * MEASURED ON THIS PORT, 2026-08-28, at ITS parameters - density ratio 0.03,
     * [MIN_LINE_HEIGHT] 10, 5x5 grey blur, smear kernel 11x5 - over a page of 10
     * Mon-shaped lines: a sparse strip of upper marks above a 45-row body, with 0,
     * 1 or 2 strokes bridging the gap. Neither Rust's figures nor the web port's
     * transfer, because this port blurs the greyscale with 5x5 where web uses 3x3
     * and Rust does not blur at all, and a gap must survive that blur AND the
     * reach-2 dilation before the profile sees it:
     *
     *     source gap   bridging strokes   dip row raw ink   bands (10 drawn)
     *     0-6 rows     any                252-676           10   blur+dilation close it
     *     8 rows       1                  16 vs thr 16.85   *20* -> 10 after the merge
     *     8 rows       2                  32 vs thr 16.98   10   above threshold
     *     8 rows       0                  0                 *20* see the note below
     *
     * So the split needs an 8-row source gap here, against 5 rows on web and 1 row
     * in Rust. The 16-columns-against-16.85 row is this port's version of the
     * reference's measured `row 280 carrying 6 ink pixels against a threshold of
     * 7.0`, and the ink clause rejoins it.
     *
     * THE ZERO-INK CASE IS NARROWER HERE THAN IN RUST, and the reason is the blur
     * and the dilation. Both grow every run, and they grow a SHORT run by more:
     * measured on this fixture, a 45-row body becomes 51 (+6), a 20-row strip
     * becomes 27 (+7), and a 1-row strip becomes 9 (+8), because a short run has
     * proportionally more edge for a 5x5 blur and a reach-2 dilation to spread.
     *
     * The effect is to inflate a strip toward its body. A 20-row strip above a
     * 45-row body becomes a 27-row run against a 51-row one, `2 * 27 > 51`, so the
     * fragment clause declines and the line stays split. At an 18-row strip - a
     * 25-row run - it merges. Applying the measured deltas to Rust's own pair, 19
     * rows against 42, gives about 26 against 48 here, which would not qualify
     * either; that last figure is an inference from the growth above rather than a
     * measurement of that pair.
     *
     * That is the clause behaving as specified rather than a porting error: a run
     * more than half a typical line IS a line by this test. It is recorded because
     * it means the ink clause carries more of the load on the dilating ports than
     * it does in Rust, and because raising the ratio to cover it would be an
     * unmeasured constant.
     *
     * Two clauses, because one does not cover it. The ink test crosses a dip that
     * stays above zero. A zero-ink gap is one no ink test can cross, so the second
     * clause is a height ratio. Two REAL lines 8 rows apart are each full height,
     * so they stay apart.
     *
     * The size bound is the third condition and does a different job: it refuses to
     * merge real inter-line spacing even when overlapping diacritics hold the raw
     * profile above zero right across it.
     *
     * Ported from `monocr-onnx` `rust/src/segmenter.rs` (`MIN_GAP_MERGE`,
     * `merge_runs`), which took it from `mon_OCR` `segmenter.py` step 8. The value
     * is the reference's.
     */
    internal const val MIN_GAP_MERGE = 10

    /**
     * Fuse runs that a sub-threshold dip or a few empty rows split apart.
     *
     * [rawHist] is the RAW profile, and the name is deliberate: this file's `hist`
     * is the SMOOTHED one. Passing the smoothed profile here would test for ink in
     * rows that only borrowed it from their neighbours.
     *
     * Internal rather than private so the arithmetic is testable without building a
     * page, and called from [segment] BEFORE the height filter - see the call site
     * for why the order is load-bearing.
     *
     * Both tests are relative to the page's own median run height rather than to
     * the neighbouring run, and that is a correction rather than a preference:
     * judging a fragment against its neighbour CASCADES. The merge mutates the
     * accumulated run, so every merge makes it taller, and a taller run makes the
     * next line look more like a fragment. Measured in the Rust port on page 47 of
     * a 56-page book: 36 bands collapsed to 10, with single bands of 534, 632 and
     * 732 rows, and the page lost 92% of its readable characters. `ceiling` is the
     * backstop for that, and twice a typical line rather than tighter because a
     * legitimate merge of two halves lands at about one typical line and must not
     * be refused.
     */
    internal fun mergeRuns(
        runs: List<Pair<Int, Int>>,
        rawHist: FloatArray,
        maxGap: Int,
        minLine: Int
    ): List<Pair<Int, Int>> {
        if (runs.isEmpty()) return emptyList()

        // Median over runs that could BE a line, not over every run. The merge
        // deliberately runs before the height filter, so `runs` still holds every
        // speckle the profile picked up, and medianing over all of them lets noise
        // decide what a typical line is. On a heavily speckled scan the noise wins:
        // measured on a sibling port, 30% of collected runs were under the minimum,
        // and on 8 of 55 pages that drove `typical` below 10 - one page reached 2,
        // and a ceiling of 4, against a real line height of 35. The ceiling then
        // refuses every merge, so the pass switches itself off on exactly the pages
        // that need it most.
        //
        // Falling back to the unfiltered median when nothing clears the minimum is
        // safe rather than principled: on such a page the height filter discards
        // everything anyway, so no crop depends on the value.
        val allHeights = runs.map { it.second - it.first }
        val qualifying = allHeights.filter { it >= minLine }
        val heights = (if (qualifying.isEmpty()) allHeights else qualifying).sorted()
        val typical = maxOf(1, heights[heights.size / 2])
        val ceiling = typical * 2

        val merged = ArrayList<Pair<Int, Int>>(runs.size)
        for ((r0, r1) in runs) {
            val last = merged.lastOrNull()
            if (last != null) {
                val gapStart = last.second
                val gapSize = maxOf(0, r0 - gapStart)
                // A caller can hand us touching runs; an empty range is vacuously
                // inked, which treats them as already one line.
                var gapHasInk = true
                for (y in gapStart until r0) {
                    // Negated rather than `<= 0f`, so a NaN reads as "no ink" the
                    // way Rust's `v > 0.0` and web's `!(rawHist[y] > 0)` do. A row
                    // count cannot be NaN today; the four are kept identical
                    // because divergence between them is the defect this pass
                    // exists to stop.
                    if (y < 0 || y >= rawHist.size || !(rawHist[y] > 0f)) {
                        gapHasInk = false
                        break
                    }
                }
                // A run at most half a typical line is a fragment of a line, not a
                // line, and this is the clause that crosses a gap of genuinely ZERO
                // ink - which `gapHasInk` refuses and which a floating Mon diacritic
                // produces. Two REAL lines two rows apart are each a full line by
                // this test, so they stay apart.
                //
                // A fragment attaches to a LINE, never to another fragment. Without
                // the `minLine` half, a run of speckle merges with itself: measured
                // on a 12-speck fixture, twelve 2-row specks fused into one 46-row
                // band, which then CLEARS the height filter and is sent to the
                // recogniser as a line. Two pieces that are both too short to be a
                // line do not become one by being adjacent.
                val ha = last.second - last.first
                val hb = r1 - r0
                val fragment = 2 * minOf(ha, hb) <= typical && maxOf(ha, hb) >= minLine

                if (gapSize <= maxGap && (gapHasInk || fragment) && r1 - last.first <= ceiling) {
                    merged[merged.size - 1] = last.first to r1
                    continue
                }
            }
            merged.add(r0 to r1)
        }
        return merged
    }

    /**
     * @param densityThresholdRatio valley threshold as a fraction of mean row
     *   density. See [SegmentationMode] for why this is a parameter and not a
     *   constant: no single value works on both book pages and photographs.
     */
    suspend fun segment(
        page: GreyImage,
        densityThresholdRatio: Float
    ): List<LineSegment> = withContext(Dispatchers.Default) {
        val width = page.width
        val height = page.height
        if (width == 0 || height == 0) return@withContext emptyList()

        val gray = page.pixels

        // 1b. Smooth Grayscale — Separable 5x5 Box Blur (O(1) per pixel via sliding window)
        //     Pass A: Horizontal 1D blur
        val blurH = IntArray(width * height)
        for (y in 0 until height) {
            var sum = 0
            // Seed the window for x=0 (radius 2 for 5x5)
            for (kx in -2..2) {
                sum += gray[y * width + kx.coerceIn(0, width - 1)]
            }
            blurH[y * width] = sum / 5
            for (x in 1 until width) {
                // To move from x-1 to x: add gray[x+2] and remove gray[x-3]
                val add = gray[y * width + minOf(x + 2, width - 1)]
                val rem = gray[y * width + maxOf(x - 3, 0)]
                sum += add - rem
                blurH[y * width + x] = sum / 5
            }
        }
        //     Pass B: Vertical 1D blur on top of the horizontal result
        val smoothedGray = IntArray(width * height)
        for (x in 0 until width) {
            var sum = 0
            // Seed the window for y=0
            for (ky in -2..2) {
                sum += blurH[ky.coerceIn(0, height - 1) * width + x]
            }
            smoothedGray[x] = sum / 5
            for (y in 1 until height) {
                val add = blurH[minOf(y + 2, height - 1) * width + x]
                val rem = blurH[maxOf(y - 3, 0) * width + x]
                sum += add - rem
                smoothedGray[y * width + x] = sum / 5
            }
        }
        val activeGray = smoothedGray

        // 2. Adaptive binarization using integral image (using smoothed buffer)
        val integral = LongArray(width * height)
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += activeGray[y * width + x]
                integral[y * width + x] = rowSum + (if (y > 0) integral[(y - 1) * width + x] else 0L)
            }
        }

        fun rectSum(x1: Int, y1: Int, x2: Int, y2: Int): Long {
            val a = if (x1 > 0 && y1 > 0) integral[(y1 - 1) * width + (x1 - 1)] else 0L
            val b = if (y1 > 0) integral[(y1 - 1) * width + x2] else 0L
            val c = if (x1 > 0) integral[y2 * width + (x1 - 1)] else 0L
            return integral[y2 * width + x2] - b - c + a
        }

        val binary = BooleanArray(width * height)
        val halfWin = WINDOW_SIZE / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                val x1 = maxOf(0, x - halfWin); val x2 = minOf(width - 1, x + halfWin)
                val y1 = maxOf(0, y - halfWin); val y2 = minOf(height - 1, y + halfWin)
                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val mean = rectSum(x1, y1, x2, y2).toFloat() / count
                binary[y * width + x] = activeGray[y * width + x] < (mean - C_THRESHOLD)
            }
        }

        // 2.5 Printed-rule suppression. Before the smear, because the smear widens a
        // rule into something no line kernel matches cleanly, and because the crop's
        // column extents come from the smeared mask - removing rules first also keeps
        // them out of the returned bounding boxes.
        suppressPageRules(binary, width, height)

        // 3. Separable 2D Morphological Filtering (Smearing)
        val smearedH = BooleanArray(width * height)
        val halfSmearX = 5
        for (y in 0 until height) {
            for (x in 0 until width) {
                var found = false
                val start = maxOf(0, x - halfSmearX)
                val end = minOf(width - 1, x + halfSmearX)
                for (kx in start..end) {
                    if (binary[y * width + kx]) {
                        found = true
                        break
                    }
                }
                smearedH[y * width + x] = found
            }
        }

        val smeared = BooleanArray(width * height)
        val halfSmearY = 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                var found = false
                val start = maxOf(0, y - halfSmearY)
                val end = minOf(height - 1, y + halfSmearY)
                for (ky in start..end) {
                    if (smearedH[ky * width + x]) {
                        found = true
                        break
                    }
                }
                smeared[y * width + x] = found
            }
        }

        // 4. Horizontal projection profile (on smeared data)
        val rawHist = FloatArray(height)
        for (y in 0 until height) {
            var count = 0
            for (x in 0 until width) { if (smeared[y * width + x]) count++ }
            rawHist[y] = count.toFloat()
        }

        // 5. Smoothing
        val hist = FloatArray(height)
        val halfK = SMOOTH_KERNEL / 2
        for (y in 0 until height) {
            var sum = 0f; var cnt = 0
            for (k in -halfK..halfK) {
                val ky = y + k
                if (ky in 0 until height) { sum += rawHist[ky]; cnt++ }
            }
            hist[y] = sum / cnt
        }

        // 6. Valley detection
        val nonZeroHist = hist.filter { it > 0 }
        val meanDensity = if (nonZeroHist.isEmpty()) 0f else nonZeroHist.average().toFloat()
        val threshold = meanDensity * densityThresholdRatio

        val segments = mutableListOf<LineSegment>()
        // [start, end) pairs, which is why they are not IntRange: an IntRange's
        // `last` is inclusive and every arithmetic here treats the second value as
        // one past the end.
        val runs = mutableListOf<Pair<Int, Int>>()
        var startY: Int? = null

        for (y in 0 until height) {
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
            val isText = rawHist[y] > threshold
            if (isText && startY == null) {
                startY = y
            } else if (!isText && startY != null) {
                runs.add(startY!! to y)
                startY = null
            }
        }

        startY?.let { sy -> runs.add(sy to height) }

        // 6.5 Fuse runs a sub-threshold dip or a few empty rows split apart, BEFORE
        // the height filter. The order is the reference's and it matters: a
        // diacritic strip can be shorter than MIN_LINE_HEIGHT, and filtering first
        // would discard the strip and leave the decapitated body behind as a whole
        // line.
        //
        // Measured on this port with a 1-row source strip, which blur and dilation
        // grow to a run of 9 rows - one short of MIN_LINE_HEIGHT. Filtering first
        // returned 10 bands 77px tall; this order returns 10 bands 93px tall, and
        // the 16px difference IS the strip of upper marks. Both counts are 10, so no
        // band count can catch this.
        for ((r0, r1) in mergeRuns(runs, rawHist, MIN_GAP_MERGE, MIN_LINE_HEIGHT)) {
            if (r1 - r0 >= MIN_LINE_HEIGHT) {
                addSegment(smeared, width, height, r0, r1, segments)
            }
        }

        // 7. Post-processing: Outlier Rejection
        val clearText = segments.filter { it.width.toFloat() / it.height >= 2.0f }
        var medianH = 0f
        if (clearText.isNotEmpty()) {
            val sortedHeights = clearText.map { it.height.toFloat() }.sorted()
            medianH = sortedHeights[sortedHeights.size / 2]
        }

        segments.filter { seg ->
            val ratio = seg.width.toFloat() / seg.height
            if (ratio < 0.2f || seg.width < 10 || seg.height < 10) false
            else if (medianH > 0 && ratio < 2.5f && seg.height > medianH * 2.5f) false
            else true
        }.map { seg ->
            seg.copy(looksLikeALine = looksLikeALine(seg, height))
        }
    }

    private fun addSegment(
        smeared: BooleanArray,
        width: Int,
        height: Int,
        startY: Int,
        endY: Int,
        segments: MutableList<LineSegment>
    ) {
        var minX = width
        var maxX = 0
        var found = false

        for (y in startY until endY) {
            for (x in 0 until width) {
                if (smeared[y * width + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    found = true
                }
            }
        }

        if (!found) return

        val coreH = endY - startY
        val padY = Math.ceil(coreH * 0.25).toInt()
        val padX = Math.ceil(coreH * 0.20).toInt()

        val x1 = maxOf(0, minX - padX)
        val x2 = minOf(width, maxX + padX)
        val y1 = maxOf(0, startY - padY)
        val y2 = minOf(height, endY + padY)
        segments.add(LineSegment(x1, y1, x2 - x1, y2 - y1))
    }
}
