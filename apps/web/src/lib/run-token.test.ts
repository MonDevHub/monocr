import { describe, expect, it } from 'vitest';
import { combinePages, readPdfPages, type PageReading } from './reading';
import { createRunToken } from './run-token';

describe('run token', () => {
	it('is current only for the latest run', () => {
		const token = createRunToken();
		const first = token.begin();
		expect(token.isCurrent(first)).toBe(true);
		const second = token.begin();
		expect(token.isCurrent(first)).toBe(false);
		expect(token.isCurrent(second)).toBe(true);
	});

	it('cancel() makes the running read stale without starting another', () => {
		const token = createRunToken();
		const run = token.begin();
		token.cancel();
		expect(token.isCurrent(run)).toBe(false);
	});

	it('commit() skips the write for a stale run', () => {
		const token = createRunToken();
		const stale = token.begin();
		token.begin();
		let wrote = false;
		expect(token.commit(stale, () => (wrote = true))).toBe(false);
		expect(wrote).toBe(false);
	});

	/**
	 * The bug this exists for, reproduced with the real PDF loop: clear a PDF mid
	 * read, pick an image, and the PDF's text must not land under the image.
	 */
	it('keeps a cleared PDF from writing its text over the next file', async () => {
		const token = createRunToken();
		const shown: { text: string | null; savedAs: string[] } = { text: null, savedAs: [] };
		const reading = (text: string): PageReading => ({ text, lineCount: 1, failedLineCount: 0 });

		let releasePdf!: () => void;
		const pdfGate = new Promise<void>((r) => (releasePdf = r));

		async function readPdf(name: string) {
			const run = token.begin();
			const pages = await readPdfPages(
				2,
				async (n) => n,
				async () => {
					await pdfGate;
					return reading('pdf text');
				},
				() => {},
				{ isCancelled: () => !token.isCurrent(run) }
			);
			token.commit(run, () => {
				shown.text = combinePages(pages).text;
				shown.savedAs.push(name);
			});
		}

		async function readImage(name: string) {
			const run = token.begin();
			const r = reading('image text');
			token.commit(run, () => {
				shown.text = r.text;
				shown.savedAs.push(name);
			});
		}

		const pdf = readPdf('sample.pdf');
		token.cancel(); // Clear
		await readImage('sample.jpg');
		releasePdf();
		await pdf;

		expect(shown.text).toBe('image text');
		expect(shown.savedAs).toEqual(['sample.jpg']);
	});
});
