/**
 * Measure what a captured image is going to cost the recogniser, before running it.
 *
 * WHY. The apps already advertise a quality bar and never check it: iOS
 * `DocsView.swift:76` tells the user "300 DPI min. 600 DPI for manuscripts." and
 * nothing anywhere measures resolution, sharpness or text size. When capture is
 * poor the user finds out from bad Mon text, which is the least diagnostic possible
 * signal — and in one case it is not a signal at all, because bands shorter than
 * `MIN_LINE_HEIGHT` are discarded silently.
 *
 * The second reason is structural. The segmenter's tuning constants are absolute
 * pixel counts — blur kernel, adaptive-threshold window, smear kernels, smoothing
 * width, gap-merge bound, minimum line height — and none of them scales with the
 * resolution of the input. So the pipeline is tuned for one text size, and nothing
 * tells the user when their capture is nowhere near it. This module cannot fix that
 * (see the note on thresholds below); it can say when it is about to bite.
 *
 * WHAT THIS CANNOT DO. Report DPI. A bare bitmap carries no physical scale, so
 * "300 DPI" is unknowable from pixels alone — what is knowable is how tall the text
 * came out in pixels, which is the quantity the segmenter actually reacts to. Read
 * `medianLineHeight` as the honest stand-in for the advertised DPI bar.
 */

import { MIN_LINE_HEIGHT, segmentLines, type LineSegment } from './segmentation';

/**
 * Laplacian-variance floor below which an image reads as soft.
 *
 * **This is a heuristic, not a measurement.** ~100 is the common rule of thumb for
 * 8-bit grayscale and it has NOT been calibrated against Mon pages, or against any
 * page in this project. It is set deliberately low so the warning is rare and
 * therefore worth reading; a floor tuned to fire often would be ignored.
 *
 * Calibrating it needs the real-photograph set `mon_OCR/docs/DATA_STRATEGY.md`
 * calls rung D1, which does not exist yet. Until it does, treat a warning here as a
 * prompt to look at the image, not as a verdict.
 */
export const SOFT_IMAGE_LAPLACIAN_VARIANCE = 100;

/**
 * A reported band height at or below this is close to being discarded.
 *
 * **Reported height is not core height, and the difference is the whole subtlety
 * here.** `MIN_LINE_HEIGHT` is applied to the raw projection run, whereas
 * `LineSegment.height` is that run after vertical smearing widened it and after
 * padding was added on both sides. Comparing the reported height directly against
 * `MIN_LINE_HEIGHT` compares two different quantities — the same confusion the
 * canonical segmenter contains, where the minimum is tested once on the core run
 * and once on the padded bbox.
 *
 * The factor between them was measured on a synthetic band, not on real text: a
 * core band of `MIN_LINE_HEIGHT + 1` reports as 27px, i.e. roughly 2.5x. So a
 * reported median at 3x the drop threshold sits just above the danger line, which
 * is where this is set. `reportedHeightOfABarelyKeptBand` pins that relationship,
 * so a change to padding or smearing breaks a test instead of silently moving this
 * warning's meaning.
 */
export const SMALL_TEXT_HEIGHT = MIN_LINE_HEIGHT * 3;

export interface CaptureAssessment {
	/** Variance of the Laplacian response. Higher is sharper. */
	sharpness: number;
	/**
	 * Median *reported* band height in pixels, or 0 if none. Includes padding and
	 * vertical smearing, so it overstates the ink by roughly 2.5x — see
	 * `SMALL_TEXT_HEIGHT`.
	 */
	medianLineHeight: number;
	/** How many bands were found. Zero means the segmenter had nothing to work with. */
	lineCount: number;
	/** Human-readable problems, most consequential first. Empty means nothing to say. */
	warnings: string[];
}

/**
 * Variance of the Laplacian over the image interior.
 *
 * A 4-neighbour Laplacian is a second-derivative filter, so it responds to edges;
 * a soft image has few strong edges and the response has little spread. The
 * interior only, because the kernel needs all four neighbours and clamping at the
 * border would manufacture edges that are not in the image.
 */
export function laplacianVariance(image: ImageData): number {
	const { width, height, data } = image;
	if (width < 3 || height < 3) return 0;

	// Grayscale once. Rec.601, matching `segmentLines` so both judge the same signal.
	const grey = new Float32Array(width * height);
	for (let i = 0; i < width * height; i++) {
		const o = i * 4;
		grey[i] = 0.299 * data[o] + 0.587 * data[o + 1] + 0.114 * data[o + 2];
	}

	// Welford would be tidier, but the interior fits comfortably in a double and the
	// two-pass form is easier to read against the definition.
	let sum = 0;
	let sumSq = 0;
	let n = 0;
	for (let y = 1; y < height - 1; y++) {
		for (let x = 1; x < width - 1; x++) {
			const i = y * width + x;
			const response =
				grey[i - width] + grey[i + width] + grey[i - 1] + grey[i + 1] - 4 * grey[i];
			sum += response;
			sumSq += response * response;
			n++;
		}
	}
	if (n === 0) return 0;
	const mean = sum / n;
	return sumSq / n - mean * mean;
}

/**
 * What this capture is likely to cost, and why.
 *
 * Pass `segments` if you have already segmented the page. Segmentation allocates
 * six full-page buffers plus a Float64 integral image — on a 12MP photo that is
 * well over 150MB — so a caller that segments anyway must not pay for it twice.
 * The recognise path does exactly that. Omit `segments` and this will segment for
 * you, which is the right shape for a standalone pre-flight check.
 */
export function assessCapture(image: ImageData, segments?: LineSegment[]): CaptureAssessment {
	const sharpness = laplacianVariance(image);
	segments ??= segmentLines(image);

	let medianLineHeight = 0;
	if (segments.length > 0) {
		const heights = segments.map((s) => s.height).sort((a, b) => a - b);
		// Upper median on an even count, matching the segmenter's own outlier
		// reference (`heights[Math.floor(heights.length / 2)]`) so the two agree.
		medianLineHeight = heights[Math.floor(heights.length / 2)];
	}

	const warnings: string[] = [];

	if (segments.length === 0) {
		warnings.push(
			'No text lines were found. The whole image will be read as one line, which on a ' +
				'page produces text that does not correspond to it.'
		);
	} else if (medianLineHeight <= SMALL_TEXT_HEIGHT) {
		warnings.push(
			`Lines measure about ${medianLineHeight}px including padding, so the ink is nearer ` +
				`${Math.round(medianLineHeight / 2.5)}px. Runs under ${MIN_LINE_HEIGHT}px are ` +
				'discarded without notice, so some lines may already be missing. Capture closer, ' +
				'or at a higher resolution.'
		);
	}

	if (sharpness < SOFT_IMAGE_LAPLACIAN_VARIANCE) {
		warnings.push(
			`The image is soft (Laplacian variance ${sharpness.toFixed(0)}). Mon diacritics are ` +
				'thin and stack above and below the line, so they are what blur removes first.'
		);
	}

	const fused = segments.filter((s) => s.lineShaped === false).length;
	if (fused > 0) {
		warnings.push(
			`${fused} of ${segments.length} bands are not line-shaped and may be several fused ` +
				'lines. Their text is unreliable and confidence will not tell you so.'
		);
	}

	return { sharpness, medianLineHeight, lineCount: segments.length, warnings };
}
