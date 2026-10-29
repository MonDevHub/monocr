import { describe, expect, it } from 'vitest';
import {
	MIN_CROP,
	dragRect,
	fitSize,
	rotatedSize,
	rotationMatrix,
	toSourceRect
} from './image-adjust';

type Matrix = ReturnType<typeof rotationMatrix>;
const apply = ([a, b, c, d, e, f]: Matrix, x: number, y: number) => ({
	x: a * x + c * y + e,
	y: b * x + d * y + f
});

describe('rotation', () => {
	it('swaps the dimensions on an odd number of quarter turns', () => {
		expect(rotatedSize(300, 200, 0)).toEqual({ width: 300, height: 200 });
		expect(rotatedSize(300, 200, 1)).toEqual({ width: 200, height: 300 });
		expect(rotatedSize(300, 200, 2)).toEqual({ width: 300, height: 200 });
		expect(rotatedSize(300, 200, 3)).toEqual({ width: 200, height: 300 });
		expect(rotatedSize(300, 200, -1)).toEqual({ width: 200, height: 300 });
	});

	it('maps every corner of the image inside the rotated canvas, for every turn', () => {
		const w = 300;
		const h = 200;
		for (const turns of [0, 1, 2, 3, -1, 5]) {
			const size = rotatedSize(w, h, turns);
			const m = rotationMatrix(turns, w, h);
			for (const [x, y] of [
				[0, 0],
				[w, 0],
				[0, h],
				[w, h]
			]) {
				const p = apply(m, x, y);
				expect(p.x, `turns ${turns}`).toBeGreaterThanOrEqual(0);
				expect(p.x, `turns ${turns}`).toBeLessThanOrEqual(size.width);
				expect(p.y, `turns ${turns}`).toBeGreaterThanOrEqual(0);
				expect(p.y, `turns ${turns}`).toBeLessThanOrEqual(size.height);
			}
		}
	});

	it('turns clockwise: the top-left corner goes to the top-right', () => {
		expect(apply(rotationMatrix(1, 300, 200), 0, 0)).toEqual({ x: 200, y: 0 });
		// and the top-right to the bottom-right
		expect(apply(rotationMatrix(1, 300, 200), 300, 0)).toEqual({ x: 200, y: 300 });
	});
});

describe('crop selection', () => {
	const bounds = { width: 400, height: 300 };

	it('is the same rectangle whichever way it is dragged', () => {
		const down = dragRect({ x: 50, y: 40 }, { x: 250, y: 140 }, bounds);
		const up = dragRect({ x: 250, y: 140 }, { x: 50, y: 40 }, bounds);
		expect(down).toEqual({ x: 50, y: 40, width: 200, height: 100 });
		expect(up).toEqual(down);
	});

	it('is clamped to the image', () => {
		expect(dragRect({ x: -30, y: -30 }, { x: 900, y: 900 }, bounds)).toEqual({
			x: 0,
			y: 0,
			width: 400,
			height: 300
		});
	});

	it('is nothing at all for a click or a sliver', () => {
		expect(dragRect({ x: 10, y: 10 }, { x: 10, y: 10 }, bounds)).toBeNull();
		expect(dragRect({ x: 10, y: 10 }, { x: 10 + MIN_CROP - 1, y: 200 }, bounds)).toBeNull();
	});

	it('maps a display rectangle back to source pixels, inside the source', () => {
		const { scale } = fitSize(2000, 1000, 500, 500);
		expect(scale).toBe(0.25);
		expect(
			toSourceRect({ x: 100, y: 50, width: 200, height: 100 }, scale, { width: 2000, height: 1000 })
		).toEqual({ x: 400, y: 200, width: 800, height: 400 });
		expect(
			toSourceRect({ x: 400, y: 200, width: 400, height: 400 }, scale, {
				width: 2000,
				height: 1000
			})
		).toEqual({ x: 1600, y: 800, width: 400, height: 200 });
	});

	it('never enlarges an image to fit', () => {
		expect(fitSize(100, 50, 500, 500)).toEqual({ width: 100, height: 50, scale: 1 });
	});
});
