/**
 * What a read produced, and whether it counts as a result at all.
 *
 * Kept free of onnxruntime and the DOM so the decisions can be tested on their
 * own, and so the page can import them without pulling the engine into the main
 * bundle. The engine (in the worker) assembles lines into a page; the page
 * combines the pages of a PDF.
 *
 * Two outcomes are kept apart on purpose, as the Android and iOS apps do:
 *
 * - A page that was read and holds no text. That is a reading — it may be a
 *   blank page, or a bad capture — and it stays a (possibly empty) result.
 * - A page on which recognition itself failed for every line, or a PDF in which
 *   no page could be read. That is not a reading. Shown as an empty result it is
 *   indistinguishable from a blank page, so it is an error.
 *
 * Anything in between keeps what was read and says what is missing.
 */

/** One detected line: its text, or why it could not be read. */
export type LineReading = { state: 'read'; text: string } | { state: 'failed'; error: string };

/** What the engine returns for one image. */
export interface PageReading {
	text: string;
	/** Lines the page was divided into, including any that failed. */
	lineCount: number;
	failedLineCount: number;
	/**
	 * Capture-quality problems the engine measured before reading (soft image,
	 * small text, no lines found). Absent on a reading from an older worker.
	 */
	captureWarnings?: string[];
	/** Bands that are not line-shaped, so may be several lines fused into one. */
	fusedBandCount?: number;
}

/** One page of a PDF. */
export type PageOutcome =
	| { pageNumber: number; status: 'read'; reading: PageReading }
	| { pageNumber: number; status: 'render_failed' | 'read_failed'; error: string };

type ReadPage = Extract<PageOutcome, { status: 'read' }>;
type FailedPage = Exclude<PageOutcome, ReadPage>;

export interface DocumentReading {
	text: string;
	warnings: string[];
}

/**
 * Join the lines of one page, or throw if none of them could be read.
 *
 * The text is joined exactly as before: lines with text, one per row, and a line
 * that read nothing adds nothing. A failed line also adds nothing, but is
 * counted, so the page can say its text is incomplete.
 */
export function assemblePage(
	lines: LineReading[],
	capture: { captureWarnings?: string[]; fusedBandCount?: number } = {}
): PageReading {
	const texts: string[] = [];
	let failed = 0;
	let firstError: string | null = null;
	for (const line of lines) {
		if (line.state === 'failed') {
			failed++;
			firstError ??= line.error;
		} else if (line.text.trim()) {
			texts.push(line.text);
		}
	}
	if (lines.length > 0 && failed === lines.length) {
		throw new Error(
			`No line could be read: all ${failed} line(s) failed. First error: ${firstError}`
		);
	}
	const reading: PageReading = {
		text: texts.join('\n'),
		lineCount: lines.length,
		failedLineCount: failed
	};
	if (capture.captureWarnings?.length) reading.captureWarnings = capture.captureWarnings;
	if (capture.fusedBandCount) reading.fusedBandCount = capture.fusedBandCount;
	return reading;
}

/**
 * What the engine measured about the capture itself, as warnings. These are
 * reported even when every line was read: a soft or tiny capture produces text
 * that is wrong without any line failing.
 */
function captureNotes(reading: PageReading): string[] {
	const notes = [...(reading.captureWarnings ?? [])];
	if (reading.fusedBandCount) {
		notes.push(
			`${reading.fusedBandCount} block(s) on this page may be several lines read as one, ` +
				`so their text can be wrong. Check it against the image.`
		);
	}
	return notes;
}

/** Warnings for one image's reading. None for a clean one. */
export function pageWarnings(reading: PageReading): string[] {
	const warnings: string[] = [];
	if (reading.failedLineCount > 0) {
		warnings.push(
			`${reading.failedLineCount} of ${reading.lineCount} line(s) could not be read. ` +
				`The text is incomplete; check it against the image.`
		);
	}
	return [...warnings, ...captureNotes(reading)];
}

/**
 * Combine the pages of a PDF, or throw if no page could be read.
 *
 * A page that failed no longer fails the document: the pages that were read are
 * kept, labelled as before, and the missing ones are named in a warning.
 */
export function combinePages(pages: PageOutcome[]): DocumentReading {
	const read = pages.filter((p): p is ReadPage => p.status === 'read');
	const failedPages = pages.filter((p): p is FailedPage => p.status !== 'read');
	if (read.length === 0) {
		const detail = failedPages.length > 0 ? ` First error: ${failedPages[0].error}` : '';
		throw new Error(`No page of this PDF could be read.${detail}`);
	}

	const text = read
		.filter((p) => p.reading.text.trim())
		.map((p) => `--- Page ${p.pageNumber} ---\n${p.reading.text}`)
		.join('\n\n');

	const warnings: string[] = [];
	if (failedPages.length > 0) {
		warnings.push(
			`Page(s) ${failedPages.map((p) => p.pageNumber).join(', ')} of ${pages.length} could not be read. ` +
				`Their text is missing.`
		);
	}
	const partial = read.filter((p) => p.reading.failedLineCount > 0);
	if (partial.length > 0) {
		const lines = partial.reduce((n, p) => n + p.reading.failedLineCount, 0);
		warnings.push(
			`${lines} line(s) on page(s) ${partial.map((p) => p.pageNumber).join(', ')} ` +
				`could not be read. The text is incomplete; check it against the source.`
		);
	}
	// Capture problems are per page; say them once, naming the pages they apply to.
	const noted = new Map<string, number[]>();
	for (const p of read) {
		for (const note of captureNotes(p.reading)) {
			noted.set(note, [...(noted.get(note) ?? []), p.pageNumber]);
		}
	}
	for (const [note, pageNumbers] of noted) {
		warnings.push(`Page(s) ${pageNumbers.join(', ')}: ${note}`);
	}
	return { text, warnings };
}

/**
 * How many recognition timeouts one PDF may spend before the rest of it is
 * given up on. Each costs a full recognition budget plus a worker restart.
 */
export const MAX_PDF_TIMEOUTS = 2;

/**
 * Whether an error is the client's recognition timeout. Matched on the code
 * rather than on the OcrError class so this module stays free of the worker.
 */
export function isTimeout(err: unknown): boolean {
	return typeof err === 'object' && err !== null && (err as { code?: unknown }).code === 'TIMEOUT';
}

function message(err: unknown): string {
	return err instanceof Error ? err.message : String(err);
}

export interface PdfReadOptions {
	/** Called as each page starts, with its 1-based number and the page count. */
	onPage?: (pageNumber: number, pageCount: number) => void;
	/**
	 * True once the person has cleared the file or chosen another. The loop stops
	 * and returns the pages read so far; the caller must discard them.
	 */
	isCancelled?: () => boolean;
}

/**
 * Read every page of a PDF, one at a time, giving each an outcome.
 *
 * A timeout rejects the request but does not stop the worker: it is still
 * running that page, and the worker has no queue, so the next page would run
 * beside it on a busy engine and time out too. `onTimeout` must therefore stop
 * the worker (the client's cleanup), so the next page starts on a fresh one.
 *
 * Pages already read are always kept. After MAX_PDF_TIMEOUTS timeouts the
 * remaining pages are not attempted and are marked failed, so a PDF that times
 * out on every page costs at most that many recognition budgets, not one per
 * page.
 */
export async function readPdfPages<T>(
	pageCount: number,
	render: (pageNumber: number) => Promise<T>,
	recognize: (page: T) => Promise<PageReading>,
	onTimeout: () => void,
	options: PdfReadOptions = {}
): Promise<PageOutcome[]> {
	const pages: PageOutcome[] = [];
	let timeouts = 0;
	for (let i = 1; i <= pageCount; i++) {
		// Checked before each page and again after the render, so a cancelled read
		// stops within one page rather than running a 14-page PDF to the end.
		if (options.isCancelled?.()) break;
		options.onPage?.(i, pageCount);
		if (timeouts >= MAX_PDF_TIMEOUTS) {
			pages.push({
				pageNumber: i,
				status: 'read_failed',
				error: `Not attempted: reading stopped after ${timeouts} pages timed out.`
			});
			continue;
		}

		let page: T;
		try {
			page = await render(i);
		} catch (err: unknown) {
			console.error(`PDF page ${i} could not be rendered:`, err);
			pages.push({ pageNumber: i, status: 'render_failed', error: message(err) });
			continue;
		}
		if (options.isCancelled?.()) break;

		try {
			pages.push({ pageNumber: i, status: 'read', reading: await recognize(page) });
		} catch (err: unknown) {
			console.error(`PDF page ${i} could not be read:`, err);
			pages.push({ pageNumber: i, status: 'read_failed', error: message(err) });
			if (isTimeout(err)) {
				timeouts++;
				onTimeout();
			}
		}
	}
	return pages;
}
