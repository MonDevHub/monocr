package dev.janakhpon.monocr.engine

/**
 * A piece of one line, as a half-open horizontal span of the line crop.
 */
data class TileSpan(val x0: Int, val x1: Int) {
    val width: Int get() = x1 - x0
}

/**
 * Splits a wide line into pieces that each fit the model window at full resolution.
 *
 * Without this, a line wider than `TARGET_WIDTH / (TARGET_HEIGHT / lineHeight)` gets
 * squeezed horizontally to fit. Measured on the same book pages: v2 at height 128
 * scored 0.0676 CER squeezed, v3.5 at height 160 scored 0.1434 squeezed, and v3.5
 * tiled scored 0.0795. Raising the input height without tiling made Android worse
 * than the model it replaced, because a taller window means a larger scale factor
 * and so a harder horizontal squeeze for the same line.
 *
 * REMEASURED 2026-08-22, and the figures above did not reproduce.
 * `mon_OCR/eval/tiling-ab-2026-08-22.md` scored 201 rendered lines through both
 * the Python arms and the Rust binding and found the answer is width-dependent:
 * squeezing wins at 2 tiles, the two are at parity at 3, and tiling wins from 4
 * tiles up, reaching 20x-36x by 6 where squeezing exceeds 0.83 CER. At median 3
 * tiles -- the population the numbers above were taken on -- there is no tiling
 * advantage. Tiling stays the default because its downside is bounded and
 * squeezing's is not, so this is a safety net rather than a general win.
 *
 * Ported from monocr-onnx `segmenter.tile_line` / `segmenter.cut_column`. The
 * constants and the arithmetic order match, so the two produce the same cuts on the
 * same input; `LineTilerFixtureTest` checks that against the shared fixture.
 */
object LineTiler {

    /**
     * Where a tile may be cut, as a fraction of the tile width, searching backwards
     * from the ideal boundary. 0.12 of a 1024px window is about 123px, roughly two
     * Mon glyphs: wide enough to find a gap, narrow enough that tiles stay near full
     * width.
     */
    const val CUT_SEARCH_FRACTION = 0.12

    /** A column counts as carrying ink below this grey value. */
    const val CUT_INK_THRESHOLD = 250

    /**
     * Where to end a tile that starts at [x0] and may not pass [ideal].
     *
     * Cutting at exactly [ideal] lands wherever the arithmetic falls, which is
     * usually the middle of a glyph. Both halves keep their pixels, so a coverage
     * check still passes, but the model reads each half as a whole character and one
     * glyph becomes two. Measured upstream on 120 drawn lines this showed up as
     * `ဗော်` read back as `ဗေဗိာ်`.
     *
     * So search backwards from [ideal] for a column of white. A tile may only get
     * narrower, never wider, or it stops fitting the model window. Returns [ideal]
     * unchanged when there is no gap to cut at, which is the honest outcome for a
     * continuous script: a known-bad seam beats an overflowing tile.
     */
    fun cutColumn(crop: GreyImage, x0: Int, ideal: Int, cropW: Int): Int {
        if (ideal >= cropW) return cropW

        val window = maxOf(1, ((ideal - x0) * CUT_SEARCH_FRACTION).toInt())
        val lo = maxOf(x0 + 1, ideal - window)
        if (lo >= ideal) return ideal

        val ink = IntArray(ideal - lo)
        for (x in lo until ideal) {
            var count = 0
            for (y in 0 until crop.height) {
                if (crop.at(x, y) < CUT_INK_THRESHOLD) count++
            }
            ink[x - lo] = count
        }

        // Prefer a truly empty column, and the rightmost one, so tiles stay as wide
        // as the window allows. Fall back to the lightest column present, taking the
        // first on a tie to match numpy argmin — the reference implementation's choice,
        // and the tie is the common case on solid ink.
        var offset = -1
        for (i in ink.indices.reversed()) {
            if (ink[i] == 0) {
                offset = i
                break
            }
        }
        if (offset < 0) {
            var best = 0
            for (i in 1 until ink.size) {
                if (ink[i] < ink[best]) best = i
            }
            offset = best
        }
        return lo + offset
    }

    /**
     * Split one line crop into pieces that each fit the model window.
     *
     * Returns a single full-width span when the line already fits after being scaled
     * to [targetH]. Otherwise cuts at whitespace columns and returns the pieces left
     * to right, to be read separately and joined with no separator.
     *
     * The arithmetic is [Double] throughout because the reference implementation is
     * Python. Narrowing `scale` to [Float] moves boundary cases: at height 100 a
     * 640px line is exactly 1024px scaled, and single-precision rounding decides
     * whether it is one tile or two.
     */
    fun tileLine(crop: GreyImage, targetH: Int, targetW: Int): List<TileSpan> {
        val cropW = crop.width
        val cropH = crop.height
        if (cropH <= 0 || cropW <= 0) return listOf(TileSpan(0, cropW))

        val scale = targetH.toDouble() / cropH
        if ((cropW * scale).toInt() <= targetW) return listOf(TileSpan(0, cropW))

        val tileWSrc = maxOf(1, (targetW / scale).toInt())
        val tiles = mutableListOf<TileSpan>()
        var x0 = 0
        while (x0 < cropW) {
            val ideal = minOf(x0 + tileWSrc, cropW)
            // Structural guard, not a tuning knob: cutColumn can only return a value
            // in (x0, ideal], but if it ever returned x0 this loop would spin forever
            // on a page. One pixel of forced progress bounds it.
            val x1 = maxOf(cutColumn(crop, x0, ideal, cropW), x0 + 1)
            tiles.add(TileSpan(x0, x1))
            x0 = x1
        }
        return tiles
    }

    /**
     * Tile [segment] of [page] and return the pieces in page coordinates, so the
     * preprocessor can crop each one straight out of the page without an
     * intermediate bitmap per tile.
     *
     * Each piece keeps the parent segment's [LineSegment.looksLikeALine] verdict:
     * a tile of a fused block is still part of a fused block.
     */
    fun tileSegment(page: GreyImage, segment: LineSegment, targetH: Int, targetW: Int): List<LineSegment> {
        val x = segment.x.coerceIn(0, page.width)
        val y = segment.y.coerceIn(0, page.height)
        val w = minOf(segment.width, page.width - x)
        val h = minOf(segment.height, page.height - y)
        if (w <= 0 || h <= 0) return listOf(segment)

        val crop = page.crop(x, y, w, h)
        val spans = tileLine(crop, targetH, targetW)
        if (spans.size == 1) return listOf(segment)

        return spans.map { span ->
            LineSegment(
                x = x + span.x0,
                y = y,
                width = span.width,
                height = h,
                looksLikeALine = segment.looksLikeALine
            )
        }
    }
}
