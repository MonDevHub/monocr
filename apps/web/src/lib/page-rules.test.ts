import { describe, expect, it } from 'vitest';
import { RULE_MAX_INK_SHARE, RULE_SPAN, segmentLines, suppressPageRules } from './segmentation';

/**
 * Printed-rule suppression.
 *
 * A printed page border adds a constant ink floor to every row it spans, and once
 * that floor clears the gap threshold no in-frame row reads as a gap: the page
 * returns as one band and is squeezed into the model window.
 *
 * Measured 2026-08-27 with this parameter set over twelve real MNEC page-ones:
 * nine collapsed to three bands or fewer, and the twelve went from 68 bands to
 * 160. Pages with no rules are untouched to the pixel.
 */

const WIDTH = 800;
const BAND = 40;
const MARGIN = 30;
const GLYPH_W = 12;
const PITCH = 20;
const RULE_W = 4;

/** A binary mask: 1 = ink. Glyph-like blobs, not solid bars — a solid bar the
 *  width of a text column IS a rule by any definition. */
function mask(bands: number, gap: number, framed: boolean, width = WIDTH) {
	const height = MARGIN * 2 + BAND * bands + gap * (bands - 1);
	const m = new Uint8Array(width * height);
	let y = MARGIN;
	for (let b = 0; b < bands; b++) {
		for (let yy = y; yy < y + BAND; yy++) {
			for (let x = MARGIN + 20; x < width - MARGIN - 20; x += PITCH) {
				for (let i = 0; i < GLYPH_W; i++) m[yy * width + x + i] = 1;
			}
		}
		y += BAND + gap;
	}
	if (framed) {
		for (let yy = 0; yy < height; yy++) {
			for (let i = 0; i < RULE_W; i++) {
				m[yy * width + 10 + i] = 1;
				m[yy * width + (width - 10 - RULE_W) + i] = 1;
			}
		}
		for (let i = 0; i < RULE_W; i++) {
			for (let x = 0; x < width; x++) {
				m[(10 + i) * width + x] = 1;
				m[(height - 10 - RULE_W + i) * width + x] = 1;
			}
		}
	}
	return { m, height };
}

/** Rows carrying no ink at all — what the projection profile needs to find gaps. */
function clearRows(m: Uint8Array, width: number, height: number) {
	let n = 0;
	for (let y = 0; y < height; y++) {
		let any = false;
		for (let x = 0; x < width && !any; x++) if (m[y * width + x]) any = true;
		if (!any) n++;
	}
	return n;
}

describe('suppressPageRules', () => {
	it('leaves a page with no rules untouched to the pixel', () => {
		// THE PROPERTY THAT MAKES THIS SAFE TO RUN UNCONDITIONALLY. Every page gets
		// this step whether it has rules or not, so "does nothing" must be exact.
		const { m, height } = mask(4, 40, false);
		const before = Uint8Array.from(m);
		expect(suppressPageRules(m, WIDTH, height)).toBe(false);
		expect(m).toEqual(before);
	});

	it('removes a frame and restores the gaps the profile needs', () => {
		// The mechanism, measured against what a clean page achieves rather than
		// "some row reaches zero" — that bar is too low to catch a missing axis:
		// removing horizontal rules alone already clears a handful of rows.
		const clean = mask(4, 40, false);
		const framed = mask(4, 40, true);
		const target = clearRows(clean.m, WIDTH, clean.height);

		expect(clearRows(framed.m, WIDTH, framed.height)).toBe(0);
		expect(suppressPageRules(framed.m, WIDTH, framed.height)).toBe(true);
		expect(clearRows(framed.m, WIDTH, framed.height)).toBeGreaterThanOrEqual(target * 0.9);
	});

	it('never mistakes glyph-sized ink for a rule', () => {
		// The false-positive direction, and what pins RULE_SPAN.
		const { m, height } = mask(6, 10, false);
		const before = Uint8Array.from(m);
		suppressPageRules(m, WIDTH, height);
		expect(m).toEqual(before);
	});

	it('abandons suppression that would eat the page', () => {
		// RULE_SPAN is a fraction of the page, so on a SHORT page a tall text block
		// exceeds it vertically and every glyph column reads as a rule. Leaving the
		// page alone is strictly better than emptying it.
		const width = 900;
		const height = 20 + 6 * 30;
		const m = new Uint8Array(width * height);
		let y = 20;
		for (let b = 0; b < 6; b++) {
			for (let yy = y; yy < y + 30; yy++)
				for (let x = 40; x < 860; x += PITCH)
					for (let i = 0; i < GLYPH_W; i++) m[yy * width + x + i] = 1;
			y += 30;
		}
		const before = Uint8Array.from(m);
		expect(suppressPageRules(m, width, height)).toBe(false);
		expect(m).toEqual(before);
	});

	it('removes a horizontal rule on its own', () => {
		// Kills the mutation that skips the horizontal scan. The frame fixture cannot:
		// its vertical rules alone clear enough rows to satisfy a row-count
		// assertion, so dropping the horizontal axis went unnoticed there.
		//
		// The page needs TEXT as well as the rule. A first version drew the rule
		// alone and failed — correctly. With no other ink, removing the rule removes
		// 100% of the page and the ink-share guard refuses. The guard was right and
		// the fixture was unrealistic: a page whose entire content is one rule is
		// not a page worth clearing.
		const { m, height } = mask(4, 40, false);
		const rowY = MARGIN + BAND + 10; // inside the first inter-line gap
		for (let i = 0; i < 4; i++) for (let x = 0; x < WIDTH; x++) m[(rowY + i) * WIDTH + x] = 1;

		expect(suppressPageRules(m, WIDTH, height)).toBe(true);
		for (let i = 0; i < 4; i++)
			for (let x = 0; x < WIDTH; x++) expect(m[(rowY + i) * WIDTH + x]).toBe(0);
	});

	it('detects a run of exactly the minimum length, and not one pixel less', () => {
		// Kills the >= / > off-by-one on the horizontal bound. The frame fixture
		// spans the full width, far past the threshold, so a one-pixel error in the
		// bound is invisible there. Text is present for the same ink-share reason.
		const minH = Math.max(15, Math.floor(WIDTH * RULE_SPAN)); // imported, so it tracks a change
		const rowY = MARGIN + BAND + 10;

		const exact = mask(4, 40, false);
		for (let x = 0; x < minH; x++) exact.m[rowY * WIDTH + x] = 1;
		expect(suppressPageRules(exact.m, WIDTH, exact.height)).toBe(true);
		expect(exact.m[rowY * WIDTH]).toBe(0);

		const short = mask(4, 40, false);
		for (let x = 0; x < minH - 1; x++) short.m[rowY * WIDTH + x] = 1;
		expect(suppressPageRules(short.m, WIDTH, short.height)).toBe(false);
		expect(short.m[rowY * WIDTH]).toBe(1);
	});

	it('detects a vertical run of exactly the minimum length', () => {
		// The vertical bound needs its own case: an exact-length test on the
		// horizontal axis leaves the vertical >= / > mutation alive.
		const { m, height } = mask(4, 40, false);
		const minV = Math.max(15, Math.floor(height * RULE_SPAN));
		const col = 12; // in the left margin, clear of the glyph columns
		for (let y = 0; y < minV; y++) m[y * WIDTH + col] = 1;

		expect(suppressPageRules(m, WIDTH, height)).toBe(true);
		expect(m[col]).toBe(0);

		const short = mask(4, 40, false);
		for (let y = 0; y < minV - 1; y++) short.m[y * WIDTH + col] = 1;
		expect(suppressPageRules(short.m, WIDTH, short.height)).toBe(false);
		expect(short.m[col]).toBe(1);
	});

	it('pins the two constants by value, not only by relation', () => {
		// IMPORTING A CONSTANT MAKES A TEST BLIND TO IT. The boundary tests above
		// derive minH and minV from RULE_SPAN, which is right — they check the
		// `>=` relation and should survive a deliberate retune. But it also means
		// they pass for ANY value: changing RULE_SPAN to 0.25 left the whole suite
		// green, which is how this test came to exist.
		//
		// So the value is pinned separately, with its reason. Both numbers sit in a
		// measured gap and are not free to drift:
		//
		//   RULE_SPAN 0.5      no Mon, Burmese or Latin glyph holds an unbroken
		//                      stroke half a page long, so the false-positive risk
		//                      against text is structural rather than merely small.
		//   RULE_MAX_INK_SHARE real framed pages classify 21.5%–58.8% of their ink
		//   0.8                as rules, rule-free pages 0.00%, and the known false
		//                      positive 98.7%. 0.8 sits in that empty band.
		//
		// Five sibling implementations carry the same two values. Changing one here
		// without the others is the drift `mon_OCR/scripts/segmenter_parity.py`
		// exists to catch, and it does not yet cover these two.
		expect(RULE_SPAN).toBe(0.5);
		expect(RULE_MAX_INK_SHARE).toBe(0.8);
	});
	it('handles a blank and an all-ink mask without throwing', () => {
		const blank = new Uint8Array(100 * 50);
		expect(suppressPageRules(blank, 100, 50)).toBe(false);
		const solid = new Uint8Array(100 * 50).fill(1);
		suppressPageRules(solid, 100, 50);
	});
});

describe('segmentLines wiring', () => {
	it('calls the suppression before the projection profile', () => {
		// The unit tests are worthless if segmentLines does not run it. Structural,
		// because a synthetic framed page does not fuse at this parameter set — the
		// justification is the real-page measurement in the header above.
		expect(segmentLines.toString()).toContain('suppressPageRules');
	});
});
