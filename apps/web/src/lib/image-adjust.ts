/**
 * Rotate and crop an image before it is read again.
 *
 * The geometry is kept free of the DOM so it can be tested; `adjustImage` is the
 * one function that touches a canvas.
 *
 * Why it exists: there was no way to turn a sideways phone photo, or to leave out
 * a margin, a ruled line or the next column. The sample's dotted rule was read as
 * a run of "မမမမ…". It is offered after a read ("Adjust"), not as a step before
 * it, so the one-tap path is unchanged.
 */

export interface Rect {
	x: number;
	y: number;
	width: number;
	height: number;
}

/** A crop smaller than this (display pixels) is a stray click, not a selection. */
export const MIN_CROP = 16;

/** Size after `turns` clockwise quarter turns. */
export function rotatedSize(
	width: number,
	height: number,
	turns: number
): { width: number; height: number } {
	return Math.abs(turns) % 2 === 1 ? { width: height, height: width } : { width, height };
}

/**
 * The canvas transform `[a, b, c, d, e, f]` that draws a `width` x `height`
 * image rotated by `turns` clockwise quarter turns into a canvas of
 * `rotatedSize(...)`. Pass it to `ctx.setTransform` and draw at 0,0.
 */
export function rotationMatrix(
	turns: number,
	width: number,
	height: number
): [number, number, number, number, number, number] {
	switch (((turns % 4) + 4) % 4) {
		case 1:
			return [0, 1, -1, 0, height, 0];
		case 2:
			return [-1, 0, 0, -1, width, height];
		case 3:
			return [0, -1, 1, 0, 0, width];
		default:
			return [1, 0, 0, 1, 0, 0];
	}
}

/** Largest size that fits the box, never enlarging. `scale` maps source to display. */
export function fitSize(width: number, height: number, maxWidth: number, maxHeight: number) {
	const scale = Math.min(1, maxWidth / width, maxHeight / height);
	return {
		width: Math.max(1, Math.round(width * scale)),
		height: Math.max(1, Math.round(height * scale)),
		scale
	};
}

/**
 * The rectangle dragged from `a` to `b`, in either direction, clamped to the
 * bounds. Null when it is too small to be a deliberate selection.
 */
export function dragRect(
	a: { x: number; y: number },
	b: { x: number; y: number },
	bounds: { width: number; height: number }
): Rect | null {
	const clamp = (v: number, max: number) => Math.min(Math.max(v, 0), max);
	const x1 = clamp(Math.min(a.x, b.x), bounds.width);
	const y1 = clamp(Math.min(a.y, b.y), bounds.height);
	const x2 = clamp(Math.max(a.x, b.x), bounds.width);
	const y2 = clamp(Math.max(a.y, b.y), bounds.height);
	if (x2 - x1 < MIN_CROP || y2 - y1 < MIN_CROP) return null;
	return { x: x1, y: y1, width: x2 - x1, height: y2 - y1 };
}

/** A rectangle in display pixels mapped to source pixels, kept inside the source. */
export function toSourceRect(
	rect: Rect,
	scale: number,
	source: { width: number; height: number }
): Rect {
	const x = Math.max(0, Math.round(rect.x / scale));
	const y = Math.max(0, Math.round(rect.y / scale));
	const width = Math.max(1, Math.min(source.width - x, Math.round(rect.width / scale)));
	const height = Math.max(1, Math.min(source.height - y, Math.round(rect.height / scale)));
	return { x, y, width, height };
}

/**
 * Render the rotated, cropped image to a new File. `crop` is in the rotated
 * image's pixels; null keeps all of it.
 */
export async function adjustImage(file: File, turns: number, crop: Rect | null): Promise<File> {
	const bitmap = await createImageBitmap(file);
	try {
		const rotated = rotatedSize(bitmap.width, bitmap.height, turns);
		const full = document.createElement('canvas');
		full.width = rotated.width;
		full.height = rotated.height;
		const fullCtx = full.getContext('2d')!;
		// White under transparency, as the engine does when it reads the page.
		fullCtx.fillStyle = 'white';
		fullCtx.fillRect(0, 0, full.width, full.height);
		fullCtx.setTransform(...rotationMatrix(turns, bitmap.width, bitmap.height));
		fullCtx.drawImage(bitmap, 0, 0);

		let out = full;
		if (crop) {
			out = document.createElement('canvas');
			out.width = crop.width;
			out.height = crop.height;
			out
				.getContext('2d')!
				.drawImage(full, crop.x, crop.y, crop.width, crop.height, 0, 0, crop.width, crop.height);
		}

		const blob = await new Promise<Blob | null>((resolve) => out.toBlob(resolve, 'image/png'));
		if (!blob) throw new Error('The adjusted image could not be encoded.');
		const base = file.name.replace(/\.[^.]+$/, '');
		return new File([blob], `${base}-adjusted.png`, { type: 'image/png' });
	} finally {
		bitmap.close();
	}
}
