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
	const MIN_LINE_HEIGHT = 10;

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
		if (seg.width < 10 || seg.height < 10) return false;

		// Reject logos/images:
		// If it's squarish (not a distinct wide line) AND much taller than normal text
		if (medianH > 0 && ratio < 2.5 && seg.height > medianH * 2.5) {
			return false;
		}

		return true;
	});

	return finalSegments;
}
