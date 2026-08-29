import { describe, expect, it } from 'vitest';
import fixture from '../../../../shared/segmentation-fixtures/rule-cases.json';
import { RULE_MAX_INK_SHARE, RULE_SPAN, suppressPageRules } from './segmentation';

/**
 * Checks `suppressPageRules` against `shared/segmentation-fixtures/rule-cases.json`,
 * the expectations generated from the printed-rule specification in
 * `mon_OCR/src/monocr/segmenter.py` and shared with the Android and iOS ports.
 *
 * The point of a shared fixture is that three ports cannot drift apart quietly. A
 * disagreement here is either a bug in this port or a regenerated fixture, and both
 * need a human — do not adjust the expectations to match the code.
 *
 * The generator's docstring records two edge cases where the reference's cv2
 * morphology deviates from the sentence it implements, and why these expectations
 * follow the sentence. `--cross-check` re-derives that classification and fails on
 * any divergence it cannot attribute, so a new one cannot hide behind the known two.
 */

interface RuleCase {
	name: string;
	width: number;
	height: number;
	density: number;
	rule_rows: number[];
	rule_cols: number[];
	run_length: number;
	run_start: number;
	col_length: number;
	col_start: number;
	expected_changed: boolean;
	expected_ink: number;
	expected_checksum: number;
}

/**
 * The 32-bit xorshift the generator describes, which every port reproduces.
 *
 * A PRNG rather than a literal mask because the cases run to 300x200. It has to be a
 * generator exactly representable in all four languages, which is why it is not an
 * LCG — JS numbers lose precision above 2^53 and could not reproduce one. Every step
 * is forced back to unsigned with `>>> 0`.
 */
function buildMask(c: RuleCase): Uint8Array {
	let x = 2463534242 >>> 0;
	const m = new Uint8Array(c.width * c.height);
	for (let i = 0; i < c.width * c.height; i++) {
		x ^= x << 13;
		x >>>= 0;
		x ^= x >>> 17;
		x >>>= 0;
		x ^= x << 5;
		x >>>= 0;
		m[i] = x % 100 < c.density ? 1 : 0;
	}
	for (const ry of c.rule_rows) {
		const length = c.run_length < 0 ? c.width : c.run_length;
		const start = c.run_length < 0 ? 0 : c.run_start;
		for (let xx = start; xx < Math.min(c.width, start + length); xx++) m[ry * c.width + xx] = 1;
	}
	for (const cx of c.rule_cols) {
		const length = c.col_length < 0 ? c.height : c.col_length;
		const start = c.col_length < 0 ? 0 : c.col_start;
		for (let yy = start; yy < Math.min(c.height, start + length); yy++) m[yy * c.width + cx] = 1;
	}
	return m;
}

/**
 * Ink count and a position-weighted checksum. A bare count would not notice
 * suppression that removed the right NUMBER of pixels in the wrong places, which is
 * exactly what an off-by-one in a run-length scan produces.
 */
function signature(m: Uint8Array, modulus: number): [number, number] {
	let ink = 0;
	let sum = 0;
	for (let i = 0; i < m.length; i++)
		if (m[i]) {
			ink++;
			sum = (sum + (i + 1)) % modulus;
		}
	return [ink, sum];
}

describe('printed-rule suppression against the shared fixture', () => {
	it('was generated with this port’s constants', () => {
		expect(fixture.rule_span).toBe(RULE_SPAN);
		expect(fixture.rule_max_ink_share).toBe(RULE_MAX_INK_SHARE);
	});

	it('carries cases', () => {
		expect(fixture.cases.length).toBeGreaterThan(0);
	});

	for (const c of fixture.cases as RuleCase[]) {
		it(`matches: ${c.name}`, () => {
			const m = buildMask(c);
			const changed = suppressPageRules(m, c.width, c.height);
			const [ink, checksum] = signature(m, fixture.checksum_modulus);

			expect(changed).toBe(c.expected_changed);
			expect(ink).toBe(c.expected_ink);
			expect(checksum).toBe(c.expected_checksum);
		});
	}
});
