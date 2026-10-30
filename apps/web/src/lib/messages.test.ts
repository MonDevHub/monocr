import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf-8');
const json = (locale: string): Record<string, string> =>
	JSON.parse(read(`../../messages/${locale}.json`));

const en = json('en');
const my = json('my');
const mnw = json('mnw');
const todo = read('../../TODO-translations.md');
const home = read('../routes/+page.svelte');

describe('message coverage', () => {
	it('lists every key that has no Burmese or Mon text in TODO-translations.md', () => {
		const unlisted = Object.keys(en).filter(
			(key) => (!(key in my) || !(key in mnw)) && !todo.includes(`\`${key}\``)
		);
		expect(unlisted).toEqual([]);
	});

	it('does not list a key that has since been translated everywhere', () => {
		const listed = [...todo.matchAll(/^\| `([\w]+)` \|/gm)].map((m) => m[1]);
		const stale = listed.filter((key) => key in my && key in mnw);
		expect(stale).toEqual([]);
	});
});

describe('home page wiring', () => {
	it('uses the translated messages that were sitting unused', () => {
		for (const key of [
			'main_title',
			'main_hero',
			'main_choose_file',
			'main_report_problem',
			'error_label',
			'note_label',
			'main_engine_ready',
			'main_extracted_text',
			'main_no_text',
			'main_copy_clipboard',
			'main_copied',
			'main_processing_page',
			'error_engine_load',
			'error_file_large',
			'error_file_type',
			'error_pdf_failed',
			'error_ocr_failed',
			'history_retry',
			'main_model_info'
		]) {
			expect(home, key).toContain(`m.${key}(`);
		}
	});

	it('has no English chrome left in the markup', () => {
		for (const literal of [
			'Drop an image or PDF here',
			'System Ready',
			'Extracted Text',
			'Recent Scans',
			'Digitize Mon texts',
			'>Retry<',
			'Copied'
		]) {
			expect(home, literal).not.toContain(literal);
		}
		// HistorySection must fall back to the translated default title.
		expect(home).not.toMatch(/<HistorySection[^>]*title=/);
	});

	it('every message the page calls exists', () => {
		const used = [...home.matchAll(/\bm\.(\w+)\(/g)].map((m) => m[1]);
		expect(used.filter((key) => !(key in en))).toEqual([]);
	});
});
