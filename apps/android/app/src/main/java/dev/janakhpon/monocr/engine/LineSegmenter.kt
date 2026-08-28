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
    private const val MIN_LINE_HEIGHT = 10

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
        var startY: Int? = null

        for (y in 0 until height) {
            val isText = hist[y] > threshold
            if (isText && startY == null) {
                startY = y
            } else if (!isText && startY != null) {
                val endY = y
                if (endY - startY!! >= MIN_LINE_HEIGHT) {
                    addSegment(smeared, width, height, startY!!, endY, segments)
                }
                startY = null
            }
        }
        
        startY?.let { sy ->
            if (height - sy >= MIN_LINE_HEIGHT) {
                addSegment(smeared, width, height, sy, height, segments)
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
