import { describe, expect, it, vi } from 'vitest';

import { OcrError } from './monocr';
import {
	assemblePage,
	combinePages,
	MAX_PDF_TIMEOUTS,
	pageWarnings,
	readPdfPages,
	type LineReading,
	type PageOutcome,
	type PageReading
} from './reading';

const read = (text: string): LineReading => ({ state: 'read', text });
const failed = (error = 'boom'): LineReading => ({ state: 'failed', error });

const page = (pageNumber: number, reading: PageReading): PageOutcome => ({
	pageNumber,
	status: 'read',
	reading
});
const ok = (text: string): PageReading => ({ text, lineCount: 1, failedLineCount: 0 });

describe('assembling the lines of one page', () => {
	it('joins lines with text exactly as before, one per row, skipping blank lines', () => {
		expect(assemblePage([read('a'), read('  '), read(''), read('b')])).toEqual({
			text: 'a\nb',
			lineCount: 4,
			failedLineCount: 0
		});
	});

	it('keeps the readable lines when one line fails, and counts the failure', () => {
		expect(assemblePage([read('a'), failed(), read('b')])).toEqual({
			text: 'a\nb',
			lineCount: 3,
			failedLineCount: 1
		});
	});

	it('throws when every line failed, naming the first error', () => {
		expect(() => assemblePage([failed('first'), failed('second')])).toThrow(
			'No line could be read: all 2 line(s) failed. First error: first'
		);
	});

	it('throws for a single line that failed, which is what a whole-page fallback is', () => {
		expect(() => assemblePage([failed()])).toThrow(/No line could be read/);
	});

	// A page that was read and holds no text is a reading, not a failure. It may
	// be blank or a bad capture; the capture check speaks to that, not this.
	it('returns empty text, not an error, when every line was read and none had text', () => {
		expect(assemblePage([read(''), read(' ')])).toEqual({
			text: '',
			lineCount: 2,
			failedLineCount: 0
		});
	});

	it('does not fail a page when some lines failed and the rest read no text', () => {
		expect(assemblePage([failed(), read('')])).toEqual({
			text: '',
			lineCount: 2,
			failedLineCount: 1
		});
	});

	it('returns empty text for no lines at all', () => {
		expect(assemblePage([])).toEqual({ text: '', lineCount: 0, failedLineCount: 0 });
	});
});

describe('warning about one image', () => {
	it('says nothing about a complete reading', () => {
		expect(pageWarnings({ text: 'a', lineCount: 2, failedLineCount: 0 })).toEqual([]);
	});

	it('says nothing about a blank page that was read', () => {
		expect(pageWarnings({ text: '', lineCount: 1, failedLineCount: 0 })).toEqual([]);
	});

	it('says how many lines are missing', () => {
		const [warning] = pageWarnings({ text: 'a', lineCount: 3, failedLineCount: 2 });

		expect(warning).toMatch(/^2 of 3 line\(s\) could not be read\./);
	});
});

describe('combining the pages of a PDF', () => {
	it('labels and joins pages with text exactly as before, skipping blank pages', () => {
		expect(combinePages([page(1, ok('one')), page(2, ok('')), page(3, ok('three'))])).toEqual({
			text: '--- Page 1 ---\none\n\n--- Page 3 ---\nthree',
			warnings: []
		});
	});

	it('keeps the pages that were read when another failed, and names the failed one', () => {
		const result = combinePages([
			page(1, ok('one')),
			{ pageNumber: 2, status: 'render_failed', error: 'canvas' },
			{ pageNumber: 3, status: 'read_failed', error: 'no line' },
			page(4, ok('four'))
		]);

		expect(result.text).toBe('--- Page 1 ---\none\n\n--- Page 4 ---\nfour');
		expect(result.warnings).toEqual([
			'Page(s) 2, 3 of 4 could not be read. Their text is missing.'
		]);
	});

	it('warns about lines that failed on pages that were otherwise read', () => {
		const result = combinePages([
			page(1, { text: 'one', lineCount: 4, failedLineCount: 1 }),
			page(2, ok('two')),
			page(3, { text: 'three', lineCount: 4, failedLineCount: 2 })
		]);

		expect(result.warnings).toEqual([
			'3 line(s) on page(s) 1, 3 could not be read. The text is incomplete; check it against the source.'
		]);
	});

	it('throws when no page could be read, naming the first error', () => {
		expect(() =>
			combinePages([
				{ pageNumber: 1, status: 'render_failed', error: 'canvas' },
				{ pageNumber: 2, status: 'read_failed', error: 'no line' }
			])
		).toThrow('No page of this PDF could be read. First error: canvas');
	});

	it('throws for a PDF with no pages to read', () => {
		expect(() => combinePages([])).toThrow('No page of this PDF could be read.');
	});

	// Every page was read and none held text. That is a blank document, not a
	// failed one, and it stays an empty result as it always has.
	it('returns empty text, not an error, when every page was read and none had text', () => {
		expect(combinePages([page(1, ok('')), page(2, ok(' '))])).toEqual({
			text: '',
			warnings: []
		});
	});

	it('does not fail a PDF in which one page was read blank and the rest failed', () => {
		const result = combinePages([
			page(1, ok('')),
			{ pageNumber: 2, status: 'read_failed', error: 'no line' }
		]);

		expect(result.text).toBe('');
		expect(result.warnings).toHaveLength(1);
	});
});

describe('reading the pages of a PDF', () => {
	// The client's own error, so a renamed code would fail here rather than
	// silently stop the worker being reset.
	const timedOut = () => new OcrError('Request timed out', 'TIMEOUT');
	const quiet = () => vi.spyOn(console, 'error').mockImplementation(() => {});

	/** A PDF of `count` pages; page n renders as n and reads as `recognize(n)`. */
	function pdf(count: number, recognize: (n: number) => Promise<PageReading>) {
		const calls: string[] = [];
		const render = vi.fn(async (n: number) => {
			calls.push(`render ${n}`);
			return n;
		});
		const read = vi.fn(async (n: number) => {
			calls.push(`read ${n}`);
			return recognize(n);
		});
		const onTimeout = vi.fn(() => {
			calls.push('reset');
		});
		return {
			calls,
			render,
			read,
			onTimeout,
			run: () => readPdfPages(count, render, read, onTimeout)
		};
	}

	it('produces the same pages and the same text as before when every page reads', async () => {
		const doc = pdf(3, async (n) => ok(n === 2 ? '' : `page ${n}`));

		const pages = await doc.run();

		expect(pages).toEqual([page(1, ok('page 1')), page(2, ok('')), page(3, ok('page 3'))]);
		expect(combinePages(pages)).toEqual({
			text: '--- Page 1 ---\npage 1\n\n--- Page 3 ---\npage 3',
			warnings: []
		});
		expect(doc.onTimeout).not.toHaveBeenCalled();
	});

	it('resets the worker after a timeout, before the next page is read', async () => {
		quiet();
		const doc = pdf(3, async (n) => {
			if (n === 2) throw timedOut();
			return ok(`page ${n}`);
		});

		const pages = await doc.run();

		expect(doc.onTimeout).toHaveBeenCalledTimes(1);
		expect(doc.calls).toEqual([
			'render 1',
			'read 1',
			'render 2',
			'read 2',
			'reset',
			'render 3',
			'read 3'
		]);
		expect(pages).toEqual([
			page(1, ok('page 1')),
			{ pageNumber: 2, status: 'read_failed', error: 'Request timed out' },
			page(3, ok('page 3'))
		]);
		expect(combinePages(pages).text).toBe('--- Page 1 ---\npage 1\n\n--- Page 3 ---\npage 3');
	});

	it('stops after the timeout limit, so a PDF that always times out costs a bounded wait', async () => {
		quiet();
		const doc = pdf(5, async () => {
			throw timedOut();
		});

		const pages = await doc.run();

		expect(doc.read).toHaveBeenCalledTimes(MAX_PDF_TIMEOUTS);
		expect(doc.render).toHaveBeenCalledTimes(MAX_PDF_TIMEOUTS);
		expect(doc.onTimeout).toHaveBeenCalledTimes(MAX_PDF_TIMEOUTS);
		expect(pages.map((p) => p.status)).toEqual(Array(5).fill('read_failed'));
		expect(pages[4]).toEqual({
			pageNumber: 5,
			status: 'read_failed',
			error: `Not attempted: reading stopped after ${MAX_PDF_TIMEOUTS} pages timed out.`
		});
		expect(() => combinePages(pages)).toThrow(
			'No page of this PDF could be read. First error: Request timed out'
		);
	});

	it('keeps the pages read before the limit was reached', async () => {
		quiet();
		const doc = pdf(4, async (n) => {
			if (n > 1) throw timedOut();
			return ok('page 1');
		});

		const pages = await doc.run();

		expect(pages.map((p) => p.status)).toEqual([
			'read',
			'read_failed',
			'read_failed',
			'read_failed'
		]);
		expect(combinePages(pages).text).toBe('--- Page 1 ---\npage 1');
	});

	it('does not reset the worker for a failure that is not a timeout', async () => {
		quiet();
		const doc = pdf(3, async (n) => {
			if (n !== 3) throw new Error('No line could be read');
			return ok('page 3');
		});

		const pages = await doc.run();

		expect(doc.onTimeout).not.toHaveBeenCalled();
		expect(doc.read).toHaveBeenCalledTimes(3);
		expect(pages[0]).toEqual({
			pageNumber: 1,
			status: 'read_failed',
			error: 'No line could be read'
		});
	});

	it('does not read a page that could not be rendered', async () => {
		quiet();
		const read = vi.fn(async () => ok('read'));
		const render = vi.fn(async (n: number) => {
			if (n === 1) throw new Error('canvas');
			return n;
		});

		const pages = await readPdfPages(2, render, read, vi.fn());

		expect(read).toHaveBeenCalledTimes(1);
		expect(pages).toEqual([
			{ pageNumber: 1, status: 'render_failed', error: 'canvas' },
			page(2, ok('read'))
		]);
	});
});
