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
export function assemblePage(lines: LineReading[]): PageReading {
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
	return { text: texts.join('\n'), lineCount: lines.length, failedLineCount: failed };
}

/** Warnings for one image's reading. None for a complete one. */
export function pageWarnings(reading: PageReading): string[] {
	if (reading.failedLineCount === 0) return [];
	return [
		`${reading.failedLineCount} of ${reading.lineCount} line(s) could not be read. ` +
			`The text is incomplete; check it against the image.`
	];
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
	return { text, warnings };
}
