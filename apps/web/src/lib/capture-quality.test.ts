import { describe, expect, it } from 'vitest';

import {
	assessCapture,
	laplacianVariance,
	SMALL_TEXT_HEIGHT,
	SOFT_IMAGE_LAPLACIAN_VARIANCE
} from './capture-quality';
import { MIN_LINE_HEIGHT } from './segmentation';

/** White page with full-height dark bars in the given row bands. */
function page(
	width: number,
	height: number,
	bands: [number, number][],
	pitch = 8,
	ink = 0
): ImageData {
	const data = new Uint8ClampedArray(width * height * 4);
	for (let i = 0; i < width * height; i++) {
		const o = i * 4;
		data[o] = data[o + 1] = data[o + 2] = 255;
		data[o + 3] = 255;
	}
	for (const [y0, y1] of bands) {
		for (let y = y0; y < y1; y++) {
			for (let x = 20; x < width - 20; x += pitch) {
				const o = (y * width + x) * 4;
				data[o] = data[o + 1] = data[o + 2] = ink;
			}
		}
	}
	return { width, height, data } as ImageData;
}

describe('laplacianVariance', () => {
	it('a flat field has no edges', () => {
		expect(laplacianVariance(page(60, 60, []))).toBe(0);
	});

	it('a hard-edged page scores far above a flat one', () => {
		const sharp = laplacianVariance(page(200, 200, [[80, 120]]));
		expect(sharp).toBeGreaterThan(SOFT_IMAGE_LAPLACIAN_VARIANCE);
	});

	it('softening the same content lowers the score', () => {
		const hard = page(200, 200, [[80, 120]]);
		// Blur by hand: replace hard black with mid grey, which halves the step at
		// every edge without moving any edge.
		const soft = page(200, 200, [[80, 120]], 8, 128);
		expect(laplacianVariance(soft)).toBeLessThan(laplacianVariance(hard));
	});

	/**
	 * Pins the kernel coefficient, not just the ordering.
	 *
	 * A single grey pixel on white has an analytically known response, and a grey
	 * centre is required: a black centre gives the same answer under -4 and -3
	 * because it is multiplied by zero either way.
	 *
	 * 5x5, centre 100 on 255. Interior is 3x3 = 9 samples. The centre responds
	 * 4*255 - 4*100 = 620; its four edge-neighbours each 3*255 + 100 - 4*255 = -155;
	 * the four corners 0. Mean is (620 - 620)/9 = 0, so the variance is the mean
	 * square: (620^2 + 4*155^2)/9 = 480500/9.
	 */
	it('matches the analytic response of a known kernel', () => {
		const w = 5;
		const h = 5;
		const data = new Uint8ClampedArray(w * h * 4);
		for (let i = 0; i < w * h; i++) {
			const o = i * 4;
			data[o] = data[o + 1] = data[o + 2] = 255;
			data[o + 3] = 255;
		}
		const centre = (2 * w + 2) * 4;
		data[centre] = data[centre + 1] = data[centre + 2] = 100;

		expect(laplacianVariance({ width: w, height: h, data } as ImageData)).toBeCloseTo(
			480500 / 9,
			2
		);
	});

	it('an image too small to hold the kernel scores zero rather than throwing', () => {
		expect(laplacianVariance(page(2, 2, []))).toBe(0);
		expect(laplacianVariance(page(1, 40, []))).toBe(0);
	});
});

describe('assessCapture', () => {
	it('a clean page of ordinary text has nothing to warn about', () => {
		const clean = page(600, 400, [
			[60, 110],
			[160, 210],
			[260, 310]
		]);
		const result = assessCapture(clean);
		expect(result.lineCount).toBeGreaterThan(0);
		expect(result.medianLineHeight).toBeGreaterThanOrEqual(SMALL_TEXT_HEIGHT);
		expect(result.warnings).toEqual([]);
	});

	it('warns when no lines were found at all', () => {
		const blank = page(200, 200, []);
		const result = assessCapture(blank);
		expect(result.lineCount).toBe(0);
		expect(result.warnings.join(' ')).toContain('No text lines were found');
	});

	/**
	 * Pins the core-to-reported factor that `SMALL_TEXT_HEIGHT` is derived from. A
	 * band one pixel above the drop threshold reports far taller than it is drawn,
	 * because vertical smearing widens the run and padding is added either side. If
	 * this number moves, `SMALL_TEXT_HEIGHT` no longer means what its comment says.
	 */
	it('reportedHeightOfABarelyKeptBand', () => {
		const core = MIN_LINE_HEIGHT + 1;
		const p = page(600, 300, [[40, 40 + core]]);
		const result = assessCapture(p);
		expect(result.lineCount).toBe(1);
		expect(result.medianLineHeight).toBe(27);
		// Which is what makes 3x the drop threshold the right place for the warning.
		expect(result.medianLineHeight).toBeLessThanOrEqual(SMALL_TEXT_HEIGHT);
	});

	it('warns about text close to the silent-drop threshold', () => {
		// Bands of MIN_LINE_HEIGHT + 1: kept, but only just.
		const h = MIN_LINE_HEIGHT + 1;
		const tiny = page(600, 300, [
			[40, 40 + h],
			[120, 120 + h],
			[200, 200 + h]
		]);
		const result = assessCapture(tiny);
		const text = result.warnings.join(' ');
		expect(result.lineCount).toBeGreaterThan(0);
		expect(text).toContain('discarded without notice');
		// The warning must name the real threshold, not a copy of it.
		expect(text).toContain(String(MIN_LINE_HEIGHT));
	});

	it('warns that a soft image will lose diacritics', () => {
		// A flat field has no edges at all, so it is the extreme of soft. It also has
		// no lines, so both warnings are expected — this asserts the soft one is
		// present, which is what the threshold controls.
		const result = assessCapture(page(120, 120, []));
		expect(result.sharpness).toBeLessThan(SOFT_IMAGE_LAPLACIAN_VARIANCE);
		expect(result.warnings.join(' ')).toContain('soft');
	});

	it('reports the sharpness it measured', () => {
		const clean = page(400, 300, [[100, 160]]);
		expect(assessCapture(clean).sharpness).toBeCloseTo(laplacianVariance(clean), 6);
	});

	it('warns about bands that are not line-shaped', () => {
		// One squarish block filling most of a small page: fails the aspect test and
		// exceeds the page fraction, so `looksLikeALine` rejects it.
		const w = 300;
		const h = 300;
		const data = new Uint8ClampedArray(w * h * 4);
		for (let i = 0; i < w * h; i++) {
			const o = i * 4;
			data[o] = data[o + 1] = data[o + 2] = 255;
			data[o + 3] = 255;
		}
		for (let y = 30; y < 270; y++) {
			for (let x = 30; x < 270; x += 6) {
				const o = (y * w + x) * 4;
				data[o] = data[o + 1] = data[o + 2] = 0;
			}
		}
		const result = assessCapture({ width: w, height: h, data } as ImageData);
		if (result.lineCount > 0) {
			expect(result.warnings.join(' ')).toContain('not line-shaped');
		}
	});
});
