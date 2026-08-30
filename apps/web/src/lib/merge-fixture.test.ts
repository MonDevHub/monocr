import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { MIN_GAP_MERGE, MIN_LINE_HEIGHT, mergeRuns } from './segmentation';

/**
 * Checks `mergeRuns` against `shared/segmentation-fixtures/merge-cases.json`, the
 * expectations generated from the SPECIFICATION of the merge and shared with the
 * Android, iOS and Rust ports.
 *
 * `mergeRuns` is the only thing standing between raw-profile boundary detection and
 * a 22x garbage regression, and it now exists ten times in five languages. Parity
 * between those ten was checked once, by hand. This file is the permanent version of
 * that check.
 *
 * The expectations are NOT taken from any port — the generator reimplements the four
 * decisions from their statement. A fixture whose oracle is one of the
 * implementations proves only that they agree with each other, and if two of them
 * are wrong in the same way it certifies the bug. The generator additionally fails
 * unless every one of its twenty single-decision mutations is killed by some case
 * and every case kills at least one, and unless the greedy fold agrees with an
 * independent brute-force enumeration of every way to cut the run list into groups.
 *
 * A disagreement here is either a bug in this port or a regenerated fixture, and
 * both need a human — do not adjust the expectations to match the code.
 */

interface MergeCase {
	name: string;
	note: string;
	profile_length: number;
	profile_fills: [number, number, number][];
	runs: [number, number][];
	max_gap: number;
	min_line: number;
	typical: number;
	expected: [number, number][];
	discriminates: string[];
}

interface MergeFixture {
	min_gap_merge: number;
	min_line_height: number;
	mutations: Record<string, string>;
	cases: MergeCase[];
}

/**
 * Read from the checkout rather than `import`ing the JSON, unlike
 * `rule-fixture.test.ts`. The difference buys the `MONOCR_MERGE_FIXTURE` override,
 * which is what lets CI and the other three ports be pointed at one file from
 * wherever they are checked out, and it matches how the Rust consumer resolves the
 * same fixture.
 *
 * A missing fixture throws rather than skipping. A skip would report a green run for
 * a port that nothing checked, which is the failure mode this whole file exists to
 * remove.
 */
function loadFixture(): MergeFixture {
	const override = process.env.MONOCR_MERGE_FIXTURE;
	const path =
		override ??
		fileURLToPath(
			new URL('../../../../shared/segmentation-fixtures/merge-cases.json', import.meta.url)
		);
	let raw: string;
	try {
		raw = readFileSync(path, 'utf-8');
	} catch (cause) {
		throw new Error(
			`cannot read the shared line-merge fixture at ${path}: ${cause}\n` +
				'set MONOCR_MERGE_FIXTURE to point at ' +
				'monocr-monorepo/shared/segmentation-fixtures/merge-cases.json',
			{ cause }
		);
	}
	return JSON.parse(raw) as MergeFixture;
}

/**
 * The row profile a port must build from the same case description.
 *
 * Fills are applied IN ORDER and overwrite, which is how a one-row sub-threshold dip
 * is written over the band it sits inside. Applying them in any other order gives a
 * different profile and the fixture would not match.
 */
function buildProfile(c: MergeCase): Float32Array {
	const hist = new Float32Array(c.profile_length);
	for (const [a, b, value] of c.profile_fills) {
		for (let y = a; y < b; y++) hist[y] = value;
	}
	return hist;
}

const fixture = loadFixture();

describe('the line merge against the shared fixture', () => {
	it('was generated with this port’s constants', () => {
		expect(fixture.min_gap_merge).toBe(MIN_GAP_MERGE);
		expect(fixture.min_line_height).toBe(MIN_LINE_HEIGHT);
	});

	it('carries cases and a mutation battery', () => {
		// Floors, not emptiness. A fixture regenerated with three of its eighteen
		// cases is not empty, so this file went on passing while testing a sixth of
		// what it advertises. Not equality: a fixture GAINING a case is the wanted
		// direction and should not need an edit in four languages to land.
		expect(fixture.cases.length).toBeGreaterThanOrEqual(18);
		expect(Object.keys(fixture.mutations).length).toBeGreaterThanOrEqual(21);
	});

	for (const c of fixture.cases) {
		it(`matches: ${c.name}`, () => {
			const hist = buildProfile(c);
			const got = mergeRuns(c.runs, hist, c.max_gap, c.min_line);

			// Exact equality, not a property. Half these cases assert that a merge
			// does NOT happen — a speckle chain that must not fuse, two real lines
			// that must stay apart — and asserting only the positive is what let the
			// speckle-chain defect survive a mutation battery once.
			expect(got, c.note).toEqual(c.expected);

			// A regenerated fixture cannot quietly bring in padding: the generator
			// refuses to write a case no mutation kills, and this is the consumer-side
			// half of that guard, for a fixture edited by hand instead.
			expect(c.discriminates.length, `${c.name} discriminates nothing`).toBeGreaterThan(0);
		});
	}
});
