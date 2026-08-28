/**
 * Robust Horizontal Projection Profile segmentation for finding text lines.
 *
 * Algorithm:
 * 1. Convert to grayscale
 * 2. Adaptive Binarization (handling shadows/uneven lighting)
 * 3. Calculate row density (sum of text pixels per row)
 * 4. Smooth profile
 * 5. Find valleys (whitespace) to split lines with generous padding
 */

export interface LineSegment {
	x: number;
	y: number;
	width: number;
	height: number;
	/**
	 * False when the band is not shaped like a line — read that before trusting
	 * the text. Optional so existing callers that construct a segment by hand
	 * still typecheck; `segmentLines` always sets it.
	 */
	lineShaped?: boolean;
}

/**
 * Is this band plausibly one line of text, or a fused block of several?
 *
 * A port of `looks_like_a_line` in `mon_OCR/src/monocr/segmenter.py:181-215`, with
 * the same two constants. Android (`LineSegmenter.looksLikeALine`), iOS
 * (`LineSegmenter.looksLikeALine`) and the Rust CLI (`apps/cli/src/mode.rs:161-168`)
 * all carry it; the web app was the last surface with no equivalent and no field to
 * report one, so a fused band was rendered as ordinary output.
 *
 * Confidence cannot substitute for this. Upstream measured a photograph where five
 * lines fused into one band and the recogniser returned fluent Mon that appears
 * nowhere on the page, **at confidence 0.83** — while a genuinely blank crop scores
 * 0.00. The signal is the wrong way round, which is why this is geometric.
 */
export function looksLikeALine(segment: LineSegment, pageHeight: number): boolean {
	if (segment.height <= 0 || pageHeight <= 0) return false;
	// A band can be line-shaped, or it can be small relative to the page. It is
	// only implausible when it is neither: tall enough to be a block AND not
	// elongated enough to be a line.
	const fillsThePage = segment.height > pageHeight * IMPLAUSIBLE_LINE_FRACTION;
	const lineShaped = segment.width / segment.height >= LINE_SHAPE_ASPECT;
	return lineShaped || !fillsThePage;
}

/**
 * Bands shorter than this are discarded, silently and with no signal to the
 * caller. Exported so `capture-quality` can warn about text that is close to it
 * rather than letting lines vanish — a page captured too small loses text here and
 * nowhere else reports it.
 *
 * The canonical value is 20 (`mon_OCR` `_MIN_LINE_HEIGHT`); this port has always
 * used 10. That divergence is recorded in the "Canonical Algorithm Spec v1" header
 * at `mon_OCR/src/monocr/segmenter.py:7-78`, which also forbids reconciling it by
 * editing a constant: which value is right is a measurement question and nothing in
 * this ecosystem can yet measure it.
 */
export const MIN_LINE_HEIGHT = 10;

/** Padded bands smaller than this in either axis are noise, not text. See the use
 * site: this is a bound on the padded bbox, not on the raw run MIN_LINE_HEIGHT
 * governs, so the shared value 10 is a coincidence rather than a link. */
const NOISE_SPECK_PX = 10;

/** A band taller than this fraction of the page is suspect unless it is elongated. */
const IMPLAUSIBLE_LINE_FRACTION = 0.4;
/** Minimum width-to-height ratio for a band to read as a line regardless of size. */
const LINE_SHAPE_ASPECT = 4.0;

/** Background luminance below this reads as a dark background to invert. */
const DARK_BACKGROUND_MEDIAN = 128;
/** Corner patch size as a fraction of each page dimension, floored at 3px. */
const CORNER_FRACTION = 10;
const CORNER_FLOOR = 3;

/** Rec.601 luma, the same weights `segmentLines` uses. */
function luma(data: Uint8ClampedArray, pixel: number): number {
	const o = pixel * 4;
	return 0.299 * data[o] + 0.587 * data[o + 1] + 0.114 * data[o + 2];
}

/**
 * Is this page light text on a dark background?
 *
 * Sampled from the four corner patches rather than a global mean: page corners are
 * almost always background, so their median survives a dense, text-heavy page
 * where a mean would be dragged down by ink.
 *
 * Ported from iOS `PageNormalizer.backgroundIsDark`, itself from mon_OCR
 * `utils.to_normalized_grayscale`. The median averages the two middle values, as
 * numpy does, and the sample is always even (four patches of equal size).
 */
export function backgroundIsDark(image: ImageData): boolean {
	const { width, height, data } = image;
	if (width <= 0 || height <= 0) return false;

	const patchH = Math.min(Math.max(CORNER_FLOOR, Math.floor(height / CORNER_FRACTION)), height);
	const patchW = Math.min(Math.max(CORNER_FLOOR, Math.floor(width / CORNER_FRACTION)), width);

	// Counting sort, not a real sort: four patches of a 12MP photo is ~4% of it and
	// only two order statistics are needed.
	const histogram = new Int32Array(256);
	let count = 0;
	const rowBands: [number, number][] = [
		[0, patchH],
		[height - patchH, height]
	];
	const colBands: [number, number][] = [
		[0, patchW],
		[width - patchW, width]
	];
	for (const [y0, y1] of rowBands) {
		for (const [x0, x1] of colBands) {
			for (let y = y0; y < y1; y++) {
				for (let x = x0; x < x1; x++) {
					histogram[Math.round(luma(data, y * width + x))]++;
					count++;
				}
			}
		}
	}
	if (count === 0) return false;

	const lowerRank = (count - 1) >> 1;
	const upperRank = count >> 1;
	let seen = 0;
	// -1, not 0, as the "not found yet" sentinel. 0 is a legal luma — a genuinely
	// black corner — and using it for both meanings meant the sentinel never cleared
	// on such a page, so `lower` was reassigned once more and came out 1 instead of 0.
	// Measured: a page half black and half white reported median 128 against a true
	// 127.5, which is on the wrong side of the threshold, so the inverted scan this
	// function exists to catch was read as a light page. iOS `PageNormalizer.swift`
	// uses the same -1 sentinel and records the same measurement, so the two agree.
	let lower = -1;
	let upper = -1;
	for (let value = 0; value < 256; value++) {
		seen += histogram[value];
		if (lower === -1 && seen > lowerRank) lower = value;
		if (seen > upperRank) {
			upper = value;
			break;
		}
	}
	return (lower + upper) / 2 < DARK_BACKGROUND_MEDIAN;
}

/**
 * Put the page into the polarity the model was trained on, **once**, before
 * anything reads it. Mutates `image` and reports whether it inverted.
 *
 * WHY THIS IS A PAGE-LEVEL OPERATION. Inversion used to happen per *tile*, inside
 * `processLine`, after `segmentLines` had already run on the un-inverted pixels. On
 * a dark-mode screenshot or an inverted scan the segmenter therefore measured the
 * BACKGROUND as ink and returned the gaps between lines. Inverting afterwards
 * cannot undo that. Worse than the iOS bug it mirrors: because the decision was
 * per tile, two tiles of one line could invert differently.
 *
 * iOS removed exactly this and recorded why (`ImagePreprocessor.swift:108-113`).
 *
 * NOT PORTED: background levelling. iOS's `PageNormalizer` also divides out a
 * dilated background estimate to flatten sepia paper and grey panels. That is a
 * separate enhancement, it is the expensive half, and it is explicitly not
 * idempotent — mixing it in here without the memory work this file already needs
 * would trade one silent bug for another.
 */
export function normalizePagePolarity(image: ImageData): boolean {
	if (!backgroundIsDark(image)) return false;
	const { data } = image;
	for (let i = 0; i < data.length; i += 4) {
		data[i] = 255 - data[i];
		data[i + 1] = 255 - data[i + 1];
		data[i + 2] = 255 - data[i + 2];
	}
	return true;
}

/**
 * A printed rule spans at least this fraction of the page in one direction.
 *
 * Deliberately coarse: no Mon, Burmese or Latin glyph holds an unbroken stroke
 * half a page long, so the false-positive risk against text is structural rather
 * than merely small. Lowering it toward a glyph's width is what would make rule
 * suppression dangerous.
 */
export const RULE_SPAN = 0.5;

/**
 * Suppression that would remove more than this share of the page's ink has found
 * text, not rules, and is abandoned.
 *
 * RULE_SPAN is a fraction of the page, so on a SHORT page a tall block of text
 * exceeds it vertically and every glyph column reads as a rule. Upstream this was
 * caught by an existing test losing 98.7% of its ink and returning zero lines.
 *
 * The threshold sits in a measured gap rather than on a round number: real framed
 * pages classify 21.5%–58.8% of their ink as rules, pages with no rules 0.00%,
 * and that false positive 98.7%.
 */
export const RULE_MAX_INK_SHARE = 0.8;

/**
 * Remove printed rules — page borders, table rules, underlines — from a text mask.
 *
 * A printed page border adds a constant ink floor to every row it spans, and once
 * that floor clears the gap threshold no in-frame row reads as a gap: the page
 * comes back as one band and is squeezed into the model window.
 *
 * Measured 2026-08-27 with THIS parameter set over twelve real MNEC page-ones:
 * nine collapsed to three bands or fewer, and the twelve together went from 68
 * bands to 160. Pages carrying no rules are untouched to the pixel, which is what
 * makes the pass safe to run unconditionally.
 *
 * Implemented as a run-length scan rather than a generic erode-then-dilate. An
 * opening with a 1×L line kernel keeps exactly those ink runs at least L long, and
 * a run-length pass computes that directly in one sweep per axis instead of two
 * passes over an L-wide window.
 *
 * There is deliberately NO thickness test. "A rule is long AND thin" was written,
 * measured and deleted upstream: the rule pixels found with a thickness limit and
 * with none were identical across twelve real pages, and it cannot work anyway —
 * an adaptive threshold compares against a LOCAL mean, so the interior of a thick
 * ink region is not ink and only its edges are.
 *
 * Mutates `binary` in place and returns whether anything was removed.
 *
 * THE SIZE CHECK IS NOT DEFENSIVE PADDING. Android and iOS fail loudly on a mask
 * that does not match its declared dimensions — Kotlin throws
 * ArrayIndexOutOfBoundsException, Swift traps — but a typed array reads `undefined`
 * out of range and drops out-of-range writes. Measured: a 232 000-byte mask declared
 * 800x340 returned `true` with no throw, having silently left the last 50 rows
 * unsuppressed, and the caller got a plausible-looking result. So this port has to
 * raise the error the language will not.
 */
export function suppressPageRules(binary: Uint8Array, width: number, height: number): boolean {
	if (width <= 0 || height <= 0) return false;
	if (binary.length !== width * height) {
		throw new Error(
			`suppressPageRules: mask has ${binary.length} entries, but ${width}x${height} needs ${width * height}`
		);
	}
	const minH = Math.max(15, Math.floor(width * RULE_SPAN));
	const minV = Math.max(15, Math.floor(height * RULE_SPAN));
	const rules = new Uint8Array(width * height);

	// Horizontal runs: mark any unbroken run of at least minH.
	for (let y = 0; y < height; y++) {
		const row = y * width;
		let start = 0;
		for (let x = 0; x <= width; x++) {
			if (x < width && binary[row + x]) continue;
			if (x - start >= minH) for (let i = start; i < x; i++) rules[row + i] = 1;
			start = x + 1;
		}
	}
	// Vertical runs: the same scan down each column.
	for (let x = 0; x < width; x++) {
		let start = 0;
		for (let y = 0; y <= height; y++) {
			if (y < height && binary[y * width + x]) continue;
			if (y - start >= minV) for (let i = start; i < y; i++) rules[i * width + x] = 1;
			start = y + 1;
		}
	}

	let ink = 0;
	for (let i = 0; i < binary.length; i++) if (binary[i]) ink++;
	if (ink === 0) return false;

	let ruleInk = 0;
	for (let i = 0; i < rules.length; i++) if (rules[i]) ruleInk++;
	if (ruleInk === 0) return false;
	// Integer arithmetic, matching Android and iOS. They evaluate this product in
	// Float32, where it disagrees with double at ink = 5_242_881 / ruleInk =
	// 4_194_305 — double abandons, float32 suppresses ~80% of the page's ink.
	// `x * 5 > y * 4` is exact everywhere, so all four implementations agree.
	if (ruleInk * 5 > ink * 4) {
		// Found the text. Leaving the page alone is strictly better than emptying
		// it, and the caller is no worse off than before this step existed.
		return false;
	}

	for (let i = 0; i < rules.length; i++) if (rules[i]) binary[i] = 0;
	return true;
}

export function segmentLines(
	imageData: ImageData,
	smoothKernel: number = 3 // Default changed to 3 to match Android/Python
): LineSegment[] {
	const { width, height, data } = imageData;
	const grayData = new Uint8Array(width * height);

	// 1. Convert to Grayscale
	for (let i = 0; i < width * height; i++) {
		const offset = i * 4;
		const r = data[offset];
		const g = data[offset + 1];
		const b = data[offset + 2];
		// Standard luma: 0.299R + 0.587G + 0.114B
		grayData[i] = 0.299 * r + 0.587 * g + 0.114 * b;
	}

	// 1b. Smooth Grayscale (3x3 Box Blur)
	const smoothedGray = new Uint8Array(width * height);
	for (let y = 0; y < height; y++) {
		for (let x = 0; x < width; x++) {
			let sum = 0;
			let count = 0;
			for (let ky = -1; ky <= 1; ky++) {
				for (let kx = -1; kx <= 1; kx++) {
					const ny = y + ky;
					const nx = x + kx;
					if (ny >= 0 && ny < height && nx >= 0 && nx < width) {
						sum += grayData[ny * width + nx];
						count++;
					}
				}
			}
			smoothedGray[y * width + x] = sum / count;
		}
	}
	const activeGray = smoothedGray;

	// 2. Adaptive Binarization (Integral Image) using smoothed buffer
	const binaryData = new Uint8Array(width * height);
	const windowSize = 25;
	const C = 8;
	const integral = new Float64Array(width * height);

	for (let y = 0; y < height; y++) {
		let rowSum = 0;
		for (let x = 0; x < width; x++) {
			rowSum += activeGray[y * width + x];
			if (y === 0) {
				integral[y * width + x] = rowSum;
			} else {
				integral[y * width + x] = integral[(y - 1) * width + x] + rowSum;
			}
		}
	}

	function getSum(x1: number, y1: number, x2: number, y2: number): number {
		const a = x1 > 0 && y1 > 0 ? integral[(y1 - 1) * width + (x1 - 1)] : 0;
		const b = y1 > 0 ? integral[(y1 - 1) * width + x2] : 0;
		const c = x1 > 0 ? integral[y2 * width + (x1 - 1)] : 0;
		const d = integral[y2 * width + x2];
		return d - b - c + a;
	}

	const halfWin = Math.floor(windowSize / 2);
	for (let y = 0; y < height; y++) {
		for (let x = 0; x < width; x++) {
			const x1 = Math.max(0, x - halfWin);
			const y1 = Math.max(0, y - halfWin);
			const x2 = Math.min(width - 1, x + halfWin);
			const y2 = Math.min(height - 1, y + halfWin);

			const count = (x2 - x1 + 1) * (y2 - y1 + 1);
			const mean = getSum(x1, y1, x2, y2) / count;
			binaryData[y * width + x] = grayData[y * width + x] < mean - C ? 1 : 0;
		}
	}

	// 2.5 Printed-rule suppression. Before the smear, because the smear widens a
	// rule into something no line kernel matches cleanly, and because the crop's
	// column extents come from the smeared mask — removing rules first also keeps
	// the border out of the crops.
	if (suppressPageRules(binaryData, width, height)) {
		console.info('[segmentation] printed rules removed before the projection profile');
	}

	// 3. Morphological Filtering (2D Smearing / Dilation)
	// Separable passes for performance: O(N*Kx + N*Ky) instead of O(N*Kx*Ky)
	// Pass A: Horizontal Smear
	const smearedH = new Uint8Array(width * height);
	const smearKernelX = 11;
	const halfSmearX = Math.floor(smearKernelX / 2);

	for (let y = 0; y < height; y++) {
		for (let x = 0; x < width; x++) {
			let found = 0;
			const start = Math.max(0, x - halfSmearX);
			const end = Math.min(width - 1, x + halfSmearX);
			for (let kx = start; kx <= end; kx++) {
				if (binaryData[y * width + kx] === 1) {
					found = 1;
					break;
				}
			}
			smearedH[y * width + x] = found;
		}
	}

	// Pass B: Vertical Smear (tethering floating marks)
	const smearedData = new Uint8Array(width * height);
	const smearKernelY = 5;
	const halfSmearY = Math.floor(smearKernelY / 2);

	for (let y = 0; y < height; y++) {
		for (let x = 0; x < width; x++) {
			let found = 0;
			const start = Math.max(0, y - halfSmearY);
			const end = Math.min(height - 1, y + halfSmearY);
			for (let ky = start; ky <= end; ky++) {
				if (smearedH[ky * width + x] === 1) {
					found = 1;
					break;
				}
			}
			smearedData[y * width + x] = found;
		}
	}

	// 4. Horizontal Projection Profile (using Smeared Data)
	const rawHist = new Float32Array(height);
	for (let y = 0; y < height; y++) {
		let count = 0;
		for (let x = 0; x < width; x++) {
			if (smearedData[y * width + x]) count++;
		}
		rawHist[y] = count;
	}

	// 5. Smoothing (Box filter)
	const hist = new Float32Array(height);
	const halfK = Math.floor(smoothKernel / 2);
	for (let y = 0; y < height; y++) {
		let sum = 0;
		let count = 0;
		for (let k = -halfK; k <= halfK; k++) {
			const ky = y + k;
			if (ky >= 0 && ky < height) {
				sum += rawHist[ky];
				count++;
			}
		}
		hist[y] = sum / count;
	}

	// 6. Valley Detection with Dynamic Thresholding
	const nonZeroHist = Array.from(hist).filter((v) => v > 0);
	const meanDensity =
		nonZeroHist.length > 0 ? nonZeroHist.reduce((a, b) => a + b, 0) / nonZeroHist.length : 0;
	// Use extreme low threshold (3%) to ensure faint diacritics spanning valleys don't get cut
	const threshold = meanDensity * 0.03;

	const segments: LineSegment[] = [];
	let startY: number | null = null;

	for (let y = 0; y < height; y++) {
		const isText = hist[y] > threshold;
		if (isText && startY === null) {
			startY = y;
		} else if (!isText && startY !== null) {
			const endY = y;
			if (endY - startY >= MIN_LINE_HEIGHT) {
				addSegment(startY, endY);
			}
			startY = null;
		}
	}

	if (startY !== null && height - startY >= MIN_LINE_HEIGHT) {
		addSegment(startY, height);
	}

	function addSegment(sY: number, eY: number) {
		// Vertical projection within this horizontal strip to find x boundaries
		// WE USE THE SMEARED DATA HERE to ensure wide diacritics are enveloped
		let minX = width;
		let maxX = 0;
		let found = false;

		for (let y = sY; y < eY; y++) {
			for (let x = 0; x < width; x++) {
				if (smearedData[y * width + x]) {
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					found = true;
				}
			}
		}

		if (found) {
			const coreH = eY - sY;
			// iOS-aligned padding for Mon/Myanmar script:
			// 25% vertical captures ascenders, descenders, and floating vowel marks;
			// 20% horizontal prevents narrow glyphs from being clipped at edges.
			const padY = Math.ceil(coreH * 0.25);
			const padX = Math.ceil(coreH * 0.2);

			const x1 = Math.max(0, Math.floor(minX - padX));
			const x2 = Math.min(width, Math.ceil(maxX + padX));
			const y1 = Math.max(0, Math.floor(sY - padY));
			const y2 = Math.min(height, Math.ceil(eY + padY));

			segments.push({
				x: x1,
				y: y1,
				width: x2 - x1,
				height: y2 - y1
			});
		}
	}

	// 7. Post-processing: Outlier Rejection (Logos/Graphics/Noise)
	// Find median height of "obvious" text lines (width >= 2.0x height)
	const clearText = segments.filter((s) => s.width / s.height >= 2.0);
	let medianH = 0;
	if (clearText.length > 0) {
		const heights = clearText.map((s) => s.height).sort((a, b) => a - b);
		medianH = heights[Math.floor(heights.length / 2)];
	}

	const finalSegments = segments.filter((seg) => {
		const ratio = seg.width / seg.height;

		// Drop vertical lines / margin noise
		if (ratio < 0.2) return false;

		// Drop tiny noise specks
		// Not MIN_LINE_HEIGHT, deliberately, and not a copy of it either. This tests
		// the PADDED bbox where MIN_LINE_HEIGHT tests the raw run, so they are
		// thresholds on two different quantities that happen to share a number — the
		// core-vs-padded confusion `capture-quality.ts` documents in the canonical
		// segmenter. Named here rather than unified, because unifying them changes
		// which bands survive and that needs a measurement.
		if (seg.width < NOISE_SPECK_PX || seg.height < NOISE_SPECK_PX) return false;

		// Reject logos/images:
		// If it's squarish (not a distinct wide line) AND much taller than normal text
		if (medianH > 0 && ratio < 2.5 && seg.height > medianH * 2.5) {
			return false;
		}

		return true;
	});

	// Flag rather than drop. A fused block still carries text a reader may want,
	// and `mon_OCR`'s api.read_page makes the same choice: return it, mark it, let
	// the caller decide.
	return finalSegments.map((seg) => ({ ...seg, lineShaped: looksLikeALine(seg, height) }));
}

/**
 * Where to cut a wide line, and why this exists.
 *
 * A line wider than the model window is otherwise SQUEEZED into it — the app
 * scales by height and then clamps the width to TARGET_WIDTH, compressing the
 * glyphs horizontally. Measured over 240 rendered Mon lines wide enough to need
 * the choice, median 3 tiles at the model height, one harness, only the graph
 * swapped:
 *
 *     v2     squeezed 0.0676   tiled 0.0758   CER
 *     v3.5   squeezed 0.1434   tiled 0.0795   CER
 *
 * This app pins v3.5 (`d3d9d5e`), so it was on the worse side of that. Tiling
 * hurts v2, so anything repinned to `a51be11` must stop calling this.
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
 * Ported from monocr-onnx `python/monocr_onnx/segmenter.py`; the constants are
 * the same so the two produce identical cuts on the same input, which is what
 * segmentation.test.ts checks against fixtures generated from that module.
 */
const CUT_SEARCH_FRACTION = 0.12;
const CUT_INK_THRESHOLD = 250;

/**
 * Where to end a tile that starts at `x0` and may not pass `ideal`.
 *
 * Cutting at exactly `ideal` lands wherever the arithmetic falls, usually mid
 * glyph. Both halves keep their pixels, so a coverage check still passes, but
 * the model reads each half as a whole character and one glyph becomes two —
 * upstream this showed up as `ဗော်` read back as `ဗေဗိာ်`.
 *
 * So search backwards from `ideal` for a column of white. A tile may only get
 * narrower, never wider, or it stops fitting the window. Returns `ideal`
 * unchanged when there is no gap: for a continuous script a known-bad seam
 * beats an overflowing tile.
 */
// Exported for the parity tests only. A tiling failure is otherwise reported as
// a wrong tile width, which does not say whether the cut search or the loop is
// at fault; the fixture carries probes that pin the cut search on its own.
export function cutColumn(
	page: ImageData,
	seg: LineSegment,
	x0: number,
	ideal: number,
	cropW: number
): number {
	if (ideal >= cropW) return cropW;

	const window = Math.max(1, Math.trunc((ideal - x0) * CUT_SEARCH_FRACTION));
	const lo = Math.max(x0 + 1, ideal - window);
	if (lo >= ideal) return ideal;

	// Ink per column across the band, counted on the page buffer directly rather
	// than through a canvas crop: the pixels are already here, and a round trip
	// through drawImage would resample them.
	let best = lo;
	let bestInk = Infinity;
	let rightmostBlank = -1;

	for (let x = lo; x < ideal; x++) {
		let ink = 0;
		for (let y = 0; y < seg.height; y++) {
			const px = seg.x + x;
			const py = seg.y + y;
			if (px < 0 || py < 0 || px >= page.width || py >= page.height) continue;
			const o = (py * page.width + px) * 4;
			// Same luma as segmentLines and PIL's convert("L").
			const gray = 0.299 * page.data[o] + 0.587 * page.data[o + 1] + 0.114 * page.data[o + 2];
			if (gray < CUT_INK_THRESHOLD) ink++;
		}
		// Prefer a truly empty column, and the rightmost one, so tiles stay as
		// wide as the window allows. Otherwise the lightest, first one wins to
		// match numpy's argmin.
		if (ink === 0) rightmostBlank = x;
		if (ink < bestInk) {
			bestInk = ink;
			best = x;
		}
	}

	return rightmostBlank >= 0 ? rightmostBlank : best;
}

/**
 * Split one line into pieces that each fit the model window.
 *
 * Returns the segment unchanged when it already fits after being scaled to
 * `targetH`. Otherwise cuts at whitespace columns, left to right; the pieces
 * are read separately and joined with no separator, because the cut falls
 * inside a word and a space there would be wrong.
 */
export function tileLine(
	page: ImageData,
	seg: LineSegment,
	targetH: number,
	targetW: number
): LineSegment[] {
	if (seg.height <= 0 || seg.width <= 0) return [seg];

	const scale = targetH / seg.height;
	if (Math.trunc(seg.width * scale) <= targetW) return [seg];

	const tileWSrc = Math.max(1, Math.trunc(targetW / scale));
	const tiles: LineSegment[] = [];
	let x0 = 0;

	while (x0 < seg.width) {
		const ideal = Math.min(x0 + tileWSrc, seg.width);
		// Structural guard, not a tuning knob: cutColumn can only return a value
		// in (x0, ideal], but if it ever returned x0 this loop would spin forever
		// on a page. One pixel of forced progress bounds it.
		const x1 = Math.max(cutColumn(page, seg, x0, ideal, seg.width), x0 + 1);
		tiles.push({ x: seg.x + x0, y: seg.y, width: x1 - x0, height: seg.height });
		x0 = x1;
	}

	return tiles;
}
