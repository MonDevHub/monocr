import * as pdfjs from 'pdfjs-dist';

// Configure the worker
// Using a CDN link for the worker as it's often easier than managing local worker scripts in SvelteKit
if (typeof window !== 'undefined' && 'pdfjsLib' in window) {
	(
		window as unknown as { pdfjsLib: { GlobalWorkerOptions: { workerSrc: string } } }
	).pdfjsLib.GlobalWorkerOptions.workerSrc = '/pdf.worker.min.mjs';
} else {
	// Standard import way if the above doesn't work in the specific build env
	pdfjs.GlobalWorkerOptions.workerSrc = '/pdf.worker.min.mjs';
}

export interface PdfPageResult {
	pageNumber: number;
	imageBytes: Uint8Array;
}

/**
 * Renders a PDF page to a Uint8Array (as a JPG image)
 */
export async function renderPdfPage(
	pdfInput: ArrayBuffer | Uint8Array | pdfjs.PDFDocumentProxy,
	pageNumber: number = 1,
	scale: number = 4.16 // Senior Tip: 300 DPI (4.16) for optimal OCR accuracy
): Promise<PdfPageResult> {
	let pdf: pdfjs.PDFDocumentProxy;

	if ('numPages' in pdfInput) {
		pdf = pdfInput;
	} else {
		const loadingTask = pdfjs.getDocument({ data: pdfInput });
		pdf = await loadingTask.promise;
	}

	if (pageNumber < 1 || pageNumber > pdf.numPages) {
		throw new Error(`Invalid page number: ${pageNumber}. PDF has ${pdf.numPages} pages.`);
	}

	const page = await pdf.getPage(pageNumber);
	const viewport = page.getViewport({ scale });

	// Create a hidden canvas to render the page
	const canvas = document.createElement('canvas');
	const context = canvas.getContext('2d');

	if (!context) {
		throw new Error('Could not get 2D context from canvas');
	}

	canvas.height = viewport.height;
	canvas.width = viewport.width;

	// Senior Tip: Always clear to white first to prevent transparent PDFs
	// from inheriting accidental dark backgrounds during JPG conversion
	context.fillStyle = 'white';
	context.fillRect(0, 0, canvas.width, canvas.height);

	const renderContext = {
		canvasContext: context,
		viewport: viewport
	};

	await page.render(renderContext).promise;

	// Convert canvas to Blob then to ArrayBuffer
	return new Promise((resolve, reject) => {
		canvas.toBlob(
			(blob) => {
				if (!blob) {
					reject(new Error('Failed to convert canvas to blob'));
					return;
				}
				const reader = new FileReader();
				reader.onload = () => {
					if (reader.result instanceof ArrayBuffer) {
						resolve({
							pageNumber,
							imageBytes: new Uint8Array(reader.result)
						});
					} else {
						reject(new Error('Failed to read blob as ArrayBuffer'));
					}
				};
				reader.onerror = () => reject(new Error('FileReader error'));
				reader.readAsArrayBuffer(blob);
			},
			'image/jpeg',
			0.9
		);
	});
}

/**
 * Loads a PDF document from data
 */
export async function loadPdf(pdfData: ArrayBuffer | Uint8Array): Promise<pdfjs.PDFDocumentProxy> {
	const loadingTask = pdfjs.getDocument({ data: pdfData });
	return loadingTask.promise;
}

/**
 * Gets the total number of pages in a PDF
 */
export async function getPdfPageCount(
	pdfInput: ArrayBuffer | Uint8Array | pdfjs.PDFDocumentProxy
): Promise<number> {
	if ('numPages' in pdfInput) {
		return pdfInput.numPages;
	}
	const pdf = await loadPdf(pdfInput);
	return pdf.numPages;
}
