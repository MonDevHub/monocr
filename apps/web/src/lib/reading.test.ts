import { describe, expect, it } from 'vitest';

import {
	assemblePage,
	combinePages,
	pageWarnings,
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
