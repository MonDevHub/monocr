import { describe, expect, it } from 'vitest';

import { tileLine } from './segmentation';

/**
 * Tiling parity with the Python binding.
 *
 * `tileLine` is a port of `tile_line`/`cut_column` in monocr-onnx
 * `python/monocr_onnx/segmenter.py`. The whole reason it is worth having is a
 * measurement taken with that implementation — on the pinned v3.5 graph, a wide
 * line squeezed into the model window scored CER 0.1434 against 0.0795 tiled —
 * so a port that cuts somewhere else is not the thing that was measured.
 *
 * The expected widths below were produced by running the Python function over
 * these exact images:
 *
 *   uv run --with pillow --with numpy python - <<'PY'
 *   from PIL import Image; from monocr_onnx.segmenter import tile_line
 *   img = Image.new('L', (w, h), 255)   # then black columns per `rule`
 *   print([t.size[0] for t in tile_line(img, 160, 1024)])
 *   PY
 *
 * Each case is a pure function of x so both sides build identical pixels.
 */
const TARGET_H = 160;
const TARGET_W = 1024;

/** White canvas with full-height black bars wherever `inked(x)` holds. */
function page(w: number, h: number, inked: (x: number) => boolean): ImageData {
	const data = new Uint8ClampedArray(w * h * 4).fill(255);
	for (let x = 0; x < w; x++) {
		if (!inked(x)) continue;
		for (let y = 0; y < h; y++) {
			const o = (y * w + x) * 4;
			data[o] = data[o + 1] = data[o + 2] = 0;
		}
	}
	return { width: w, height: h, data, colorSpace: 'srgb' } as ImageData;
}

const CASES: {
	name: string;
	w: number;
	h: number;
	inked: (x: number) => boolean;
	widths: number[];
}[] = [
	// Fits the window once scaled to 160px tall, so it must come back untouched.
	{ name: 'fits', w: 200, h: 100, inked: (x) => x % 7 === 0, widths: [200] },
	// No blank column inside the search window: falls back to the lightest, and
	// numpy's argmin takes the first on a tie.
	{
		name: 'no gap in the search window',
		w: 3000,
		h: 100,
		inked: (x) => x % 137 !== 0,
		widths: [564, 564, 564, 637, 564, 107]
	},
	// Solid ink, every column identical: same argmin-first fallback.
	{ name: 'solid ink', w: 2500, h: 120, inked: () => true, widths: [676, 676, 676, 472] },
	// Blanks land inside the window, so the rightmost one wins and tiles stay as
	// wide as the window allows. 621 is 23 x 27.
	{
		name: 'a gap inside the search window',
		w: 3000,
		h: 100,
		inked: (x) => x % 23 !== 0,
		widths: [621, 621, 621, 621, 516]
	},
	{ name: 'sparse ink', w: 2200, h: 90, inked: (x) => x % 11 === 0, widths: [575, 575, 575, 475] },
	// Exactly the window width after scaling: still one tile, not two.
	{ name: 'exactly at the boundary', w: 640, h: 100, inked: (x) => x % 3 === 0, widths: [640] }
];

describe('tileLine matches the Python binding it was ported from', () => {
	for (const c of CASES) {
		it(c.name, () => {
			const img = page(c.w, c.h, c.inked);
			const tiles = tileLine(img, { x: 0, y: 0, width: c.w, height: c.h }, TARGET_H, TARGET_W);

			expect(tiles.map((t) => t.width)).toEqual(c.widths);
		});
	}

	it('covers the line exactly, with no gap and no overlap', () => {
		const c = CASES[3];
		const tiles = tileLine(
			page(c.w, c.h, c.inked),
			{ x: 0, y: 0, width: c.w, height: c.h },
			TARGET_H,
			TARGET_W
		);

		// Dropping or double-reading a strip is how tiling silently loses text.
		let x = 0;
		for (const t of tiles) {
			expect(t.x).toBe(x);
			x += t.width;
		}
		expect(x).toBe(c.w);
	});

	it('offsets tiles by the segment origin, not the crop', () => {
		// processLine is handed page coordinates, so a line part-way down a page
		// must not have its tiles reported from 0.
		const c = CASES[3];
		const tiles = tileLine(
			page(c.w, c.h, c.inked),
			{ x: 40, y: 25, width: c.w - 40, height: c.h - 25 },
			TARGET_H,
			TARGET_W
		);

		expect(tiles[0].x).toBe(40);
		expect(tiles.every((t) => t.y === 25)).toBe(true);
	});

	it('always advances, so a pathological line cannot spin forever', () => {
		// cut_column can only return a value in (x0, ideal], but the guard behind
		// that is structural. A one-pixel-wide tall line is the degenerate input.
		const tiles = tileLine(
			page(4, 4000, () => true),
			{ x: 0, y: 0, width: 4, height: 4000 },
			TARGET_H,
			TARGET_W
		);

		expect(tiles.length).toBeGreaterThan(0);
		expect(tiles.every((t) => t.width >= 1)).toBe(true);
	});
});
