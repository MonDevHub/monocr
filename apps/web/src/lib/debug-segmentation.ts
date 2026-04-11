/**
 * Debug utilities for visualizing line segmentation
 */
import type { LineSegment } from './segmentation';

export function visualizeSegments(imageData: ImageData, segments: LineSegment[]): ImageData {
	// Clone the image data
	const debugData = new ImageData(
		new Uint8ClampedArray(imageData.data),
		imageData.width,
		imageData.height
	);

	// Draw rectangles at segment boundaries
	for (const seg of segments) {
		const { x, y, width, height } = seg;
		const x1 = Math.floor(x);
		const y1 = Math.floor(y);
		const x2 = Math.floor(x + width);
		const y2 = Math.floor(y + height);

		// Helper to set pixel color
		const setPixel = (px: number, py: number, r: number, g: number, b: number) => {
			if (px >= 0 && px < debugData.width && py >= 0 && py < debugData.height) {
				const offset = (py * debugData.width + px) * 4;
				debugData.data[offset] = r;
				debugData.data[offset + 1] = g;
				debugData.data[offset + 2] = b;
				debugData.data[offset + 3] = 255;
			}
		};

		// Draw top and bottom borders (green for top, red for bottom)
		for (let cx = x1; cx < x2; cx++) {
			setPixel(cx, y1, 0, 255, 0); // Green
			setPixel(cx, y2 - 1, 255, 0, 0); // Red
		}
		// Draw left and right borders
		for (let cy = y1; cy < y2; cy++) {
			setPixel(x1, cy, 0, 255, 0); // Green
			setPixel(x2 - 1, cy, 255, 0, 0); // Red
		}
	}

	return debugData;
}

export function logSegmentationDetails(imageData: ImageData, segments: LineSegment[]): void {
	console.group('🔍 Segmentation Debug');
	console.log('Image dimensions:', imageData.width, 'x', imageData.height);
	console.log('Segments found:', segments.length);

	segments.forEach((seg, i) => {
		const coverageY = ((seg.height / imageData.height) * 100).toFixed(1);
		const coverageX = ((seg.width / imageData.width) * 100).toFixed(1);
		console.log(
			`Line ${i + 1}: x=${seg.x.toFixed(0)}, y=${seg.y.toFixed(0)}, w=${seg.width.toFixed(0)}, h=${seg.height.toFixed(0)} (H:${coverageY}%, W:${coverageX}% of image)`
		);
	});

	console.groupEnd();
}
