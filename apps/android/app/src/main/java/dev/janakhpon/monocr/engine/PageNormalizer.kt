package dev.janakhpon.monocr.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Puts a page into the polarity and background the model was trained on: dark ink
 * on a white ground.
 *
 * This runs once, on the whole page, before segmentation. It used to run per line
 * inside the preprocessor, after segmentation had already measured ink on the
 * un-inverted grey — so on a dark-mode screenshot or an inverted scan the segmenter
 * found the background and called it text, and no amount of correct per-line
 * inversion afterwards could recover the lines it had already missed.
 *
 * It is not idempotent: the background levelling divides by an estimate of the
 * background, so running it twice divides twice. There is exactly one call site,
 * in [OcrRepository.performOcr], and the per-line inversion it replaced is gone.
 *
 * Ported from mon_OCR `utils.to_normalized_grayscale` and `utils._level_background`.
 * That port is faithful to the algorithm, not bit-exact to OpenCV: the area resize,
 * the elliptical dilation and the bilinear upsample are written out here in
 * floating point, where OpenCV uses fixed-point kernels. Expect agreement to about
 * a grey level, not to the bit.
 */
object PageNormalizer {

    // `backgroundIsDark`, `invert`, `levelBackground`, `resizeArea` and `dilateDisk`
    // are deliberately not private. Only `normalize` was reachable until 2026-08-28,
    // and the consequence was that the most numerically delicate file in `engine/`
    // had zero tests while the iOS port of the same code had eight — iOS having
    // widened exactly these for exactly this reason.
    //
    // What that cost: iOS's dilation used a square structuring element where this
    // one uses a disk, so the two ports disagreed on 7 of 8 synthetic pages, and
    // nothing on either side could see it. `buildLinearTable` and `areaWeights` stay
    // private; they are reached through the two functions above.

    /**
     * The dilation kernel for a downsampled page of [smallHeight] rows.
     *
     * `max(7, (h / 4) | 1)`: about 10% of the small image's height, forced odd so the
     * structuring element has a centre, with a floor that keeps it wider than a stroke
     * on small inputs. Named rather than inlined so `dilate-cases.json` can pin it
     * against the reference's own answers — a mutation dropping the floor from 7 to 3
     * survived the entire suite on 2026-08-28 because nothing asserted it.
     */
    fun kernelForSmallHeight(smallHeight: Int): Int = maxOf(7, (smallHeight / 4) or 1)

    /**
     * The corner-patch side for an image [side] pixels across that axis.
     *
     * `max(3, side / 10)`, matching `to_normalized_grayscale`. The floor of 3 is what
     * keeps a patch on a tiny crop from collapsing to a single pixel, and it was
     * likewise unpinned until `dilate-cases.json` carried the reference's answers.
     * The caller clamps to the axis length; on a small image the four patches overlap
     * and the shared pixels are counted twice, which is what the reference does.
     */
    fun cornerPatch(side: Int): Int = maxOf(3, side / 10)

    /** Corner patches below this median read as a dark background, so the page is inverted. */
    private const val DARK_BACKGROUND_MEDIAN = 128.0

    /**
     * Normalise [page] in place and return it. In place because both steps write each
     * output pixel from the same input pixel, and a full-size copy per step is 33 MB
     * on a 300 DPI A4 page.
     */
    fun normalize(page: GreyImage): GreyImage {
        if (page.width == 0 || page.height == 0) return page
        if (backgroundIsDark(page)) invert(page)
        levelBackground(page)
        return page
    }

    /**
     * Sample the four 10%-corner patches and take their median.
     *
     * Document corners are almost always background, so their median tracks
     * background luminance even on a dense, text-heavy page where a global mean
     * would be dragged down by ink.
     */
    fun backgroundIsDark(page: GreyImage): Boolean {
        val h = page.height
        val w = page.width
        val ch = minOf(h, cornerPatch(h))
        val cw = minOf(w, cornerPatch(w))

        // A 256-bin histogram gives the exact median in one pass; sorting the
        // concatenated patches would be a third of a million comparisons on a
        // 300 DPI page for the same answer.
        val histogram = IntArray(256)
        var total = 0L
        for (yRange in listOf(0 until ch, (h - ch) until h)) {
            for (xRange in listOf(0 until cw, (w - cw) until w)) {
                for (y in yRange) {
                    val row = y * w
                    for (x in xRange) {
                        histogram[page.pixels[row + x]]++
                        total++
                    }
                }
            }
        }
        if (total == 0L) return false

        // Four patches of equal size, so the count is even and the median is the
        // mean of the two middle values, as numpy.median gives it.
        val lowerRank = total / 2 - 1
        val upperRank = total / 2
        var seen = 0L
        var lower = -1
        var upper = -1
        for (value in 0..255) {
            seen += histogram[value]
            if (lower < 0 && seen > lowerRank) lower = value
            if (seen > upperRank) {
                upper = value
                break
            }
        }
        return (lower + upper) / 2.0 < DARK_BACKGROUND_MEDIAN
    }

    fun invert(page: GreyImage) {
        val pixels = page.pixels
        for (i in pixels.indices) pixels[i] = 255 - pixels[i]
    }

    /**
     * Flatten grey boxes, off-white paper and coloured panels to white while leaving
     * ink dark.
     *
     * Because background is bright and ink is dark, a max filter (dilation) with a
     * kernel wider than a glyph propagates background over the ink and leaves a
     * smooth estimate of the local background tone. Dividing by that estimate maps
     * any background shade to white. Already-white pages come out unchanged.
     *
     * The estimate is built at quarter resolution, which is where the cost sits: on
     * a 300 DPI A4 page this is a few hundred milliseconds, against tens of
     * milliseconds of model time per line. It is not free, and the upstream
     * docstring's "0.5 ms" is an OpenCV number, not this one.
     */
    fun levelBackground(page: GreyImage) {
        val h = page.height
        val w = page.width
        val smallH = maxOf(1, h / 4)
        val smallW = maxOf(1, w / 4)

        val small = resizeArea(page, smallW, smallH)
        // Kernel covers about 10% of the small image height, forced odd so the
        // structuring element has a centre. The floor of 7 keeps it wider than a
        // stroke on small inputs.
        val kernel = kernelForSmallHeight(smallH)
        val backgroundSmall = dilateDisk(small, smallW, smallH, kernel)

        // The bilinear upsample and the division are fused: a full-resolution copy of
        // the background estimate would be another w*h floats, 33 MB on a 300 DPI
        // page, and each output row only needs two rows of the estimate.
        val sxIndex = IntArray(w)
        val sxFrac = FloatArray(w)
        buildLinearTable(smallW, w, sxIndex, sxFrac)
        val syIndex = IntArray(h)
        val syFrac = FloatArray(h)
        buildLinearTable(smallH, h, syIndex, syFrac)

        // Written in place: each output pixel reads only its own input pixel and the
        // already-finished background estimate, so nothing downstream in this loop
        // needs the original value.
        val out = page.pixels
        for (y in 0 until h) {
            val y0 = syIndex[y]
            val wy = syFrac[y]
            val y1 = if (wy == 0f) y0 else y0 + 1
            val row0 = y0 * smallW
            val row1 = y1 * smallW
            val dstRow = y * w
            for (x in 0 until w) {
                val x0 = sxIndex[x]
                val wx = sxFrac[x]
                val x1 = if (wx == 0f) x0 else x0 + 1
                val top = backgroundSmall[row0 + x0] * (1f - wx) + backgroundSmall[row0 + x1] * wx
                val bottom = backgroundSmall[row1 + x0] * (1f - wx) + backgroundSmall[row1 + x1] * wx
                // Guard the division rather than trusting the estimate to stay bright:
                // an all-black input survives polarity normalisation as all-black.
                val background = maxOf(top * (1f - wy) + bottom * wy, 1f)
                val scaled = (out[dstRow + x] / background) * 255f
                out[dstRow + x] = scaled.coerceIn(0f, 255f).toInt()
            }
        }
    }

    /**
     * OpenCV's `INTER_LINEAR` sample positions: pixel centres at half-integers, the
     * source index clamped to the last column and its weight dropped there so the
     * edge replicates instead of reading past the buffer.
     */
    private fun buildLinearTable(srcLen: Int, dstLen: Int, index: IntArray, frac: FloatArray) {
        val scale = srcLen.toDouble() / dstLen
        for (d in 0 until dstLen) {
            val position = (d + 0.5) * scale - 0.5
            var s = floor(position).toInt()
            var a = (position - s).toFloat()
            if (s < 0) {
                s = 0
                a = 0f
            }
            if (s >= srcLen - 1) {
                s = srcLen - 1
                a = 0f
            }
            index[d] = s
            frac[d] = a
        }
    }

    /**
     * Area-averaged downscale, the separable equivalent of `cv2.INTER_AREA`. Each
     * output pixel is the mean of the source pixels its footprint covers, weighted
     * by how much of each it covers. Rounded back to 0..255 because `cv2.resize` on
     * a uint8 image returns uint8, and the dilation below then sees the same values
     * the reference implementation dilates.
     */
    fun resizeArea(page: GreyImage, dstW: Int, dstH: Int): IntArray {
        val srcW = page.width
        val srcH = page.height
        val xWeights = areaWeights(srcW, dstW)
        val yWeights = areaWeights(srcH, dstH)

        val horizontal = FloatArray(dstW * srcH)
        for (y in 0 until srcH) {
            val srcRow = y * srcW
            val dstRow = y * dstW
            for (dx in 0 until dstW) {
                val weights = xWeights.weights[dx]
                val first = xWeights.first[dx]
                var acc = 0f
                for (i in weights.indices) acc += weights[i] * page.pixels[srcRow + first + i]
                horizontal[dstRow + dx] = acc
            }
        }

        val out = IntArray(dstW * dstH)
        for (dy in 0 until dstH) {
            val weights = yWeights.weights[dy]
            val first = yWeights.first[dy]
            val dstRow = dy * dstW
            for (x in 0 until dstW) {
                var acc = 0f
                for (i in weights.indices) acc += weights[i] * horizontal[(first + i) * dstW + x]
                out[dstRow + x] = Math.round(acc).coerceIn(0, 255)
            }
        }
        return out
    }

    private class AreaWeights(val first: IntArray, val weights: Array<FloatArray>)

    private fun areaWeights(srcLen: Int, dstLen: Int): AreaWeights {
        val scale = srcLen.toDouble() / dstLen
        val first = IntArray(dstLen)
        val weights = Array(dstLen) { FloatArray(0) }
        for (d in 0 until dstLen) {
            val start = d * scale
            val end = minOf((d + 1) * scale, srcLen.toDouble())
            val i0 = start.toInt()
            val i1 = maxOf(i0 + 1, minOf(ceil(end).toInt(), srcLen))
            val row = FloatArray(i1 - i0)
            var total = 0.0
            for (i in i0 until i1) {
                val overlap = maxOf(0.0, minOf(end, (i + 1).toDouble()) - maxOf(start, i.toDouble()))
                row[i - i0] = overlap.toFloat()
                total += overlap
            }
            if (total > 0.0) {
                for (i in row.indices) row[i] = (row[i] / total).toFloat()
            } else {
                row[0] = 1f
            }
            first[d] = i0
            weights[d] = row
        }
        return AreaWeights(first, weights)
    }

    /**
     * Dilate with a disk of diameter [k], matching
     * `cv2.getStructuringElement(MORPH_ELLIPSE, (k, k))` then `cv2.dilate`.
     *
     * The disk is a union of horizontal segments, one per row offset, so the
     * dilation is the maximum over those segments. Growing the horizontal maximum
     * one pixel per step and folding in each row offset as its width is reached
     * makes this O((r + k) * w * h). The direct k-by-k scan is O(k^2) per pixel,
     * which on a 300 DPI page means a kernel around 200 wide and tens of billions of
     * comparisons — not slow, unusable.
     *
     * Out-of-image parts of the kernel are skipped, which is what OpenCV's default
     * dilation border does.
     */
    fun dilateDisk(src: IntArray, w: Int, h: Int, k: Int): IntArray {
        val r = k / 2
        val invR2 = if (r > 0) 1.0 / (r.toDouble() * r) else 0.0
        val halfWidth = IntArray(2 * r + 1)
        for (i in 0..2 * r) {
            val dy = (i - r).toDouble()
            halfWidth[i] = Math.round(r * sqrt((r.toDouble() * r - dy * dy) * invR2)).toInt()
        }

        var current = src.copyOf()
        val next = IntArray(w * h)
        // 0 is the identity for a maximum over 0..255 data, so no sentinel is needed.
        val out = IntArray(w * h)

        for (d in 0..r) {
            if (d >= 1) {
                for (y in 0 until h) {
                    val base = y * w
                    for (x in 0 until w) {
                        // Half-width d covers [x-d, x+d]. For d == 1 that needs the
                        // centre too; from d == 2 on, the two half-width d-1 windows
                        // either side of x already overlap and cover it.
                        var m = if (d == 1) current[base + x] else Int.MIN_VALUE
                        if (x > 0) m = maxOf(m, current[base + x - 1])
                        if (x + 1 < w) m = maxOf(m, current[base + x + 1])
                        next[base + x] = if (m == Int.MIN_VALUE) current[base + x] else m
                    }
                }
                System.arraycopy(next, 0, current, 0, current.size)
            }
            for (i in 0..2 * r) {
                if (halfWidth[i] != d) continue
                val dy = i - r
                for (y in 0 until h) {
                    val sy = y + dy
                    if (sy < 0 || sy >= h) continue
                    val dstRow = y * w
                    val srcRow = sy * w
                    for (x in 0 until w) {
                        val v = current[srcRow + x]
                        if (v > out[dstRow + x]) out[dstRow + x] = v
                    }
                }
            }
        }
        return out
    }
}
