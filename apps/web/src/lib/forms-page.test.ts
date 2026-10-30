/**
 * The two forms (Feedback and Contribute) as source: the consent gate, the error pattern and the
 * controls that make them operable. Source reads, like ui-contract.test.ts, because `pnpm test`
 * starts no browser.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { acceptsFile } from './accept-file';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf-8');
const report = read('../routes/report/+page.svelte');
const contribute = read('../routes/contribute/+page.svelte');
const actionBox = read('./components/ui/ActionBox.svelte');

describe('Feedback form', () => {
	it('keeps consent unchecked by default and blocks the save without it', () => {
		expect(report).toMatch(/let consent = \$state\(false\)/);
		// The guard sits before any saveRecord call.
		const guard = report.indexOf('if (found.length > 0) return;');
		expect(guard).toBeGreaterThan(-1);
		expect(guard).toBeLessThan(report.indexOf('await saveRecord('));
		expect(report).toMatch(/if \(!consent\) found\.push\(/);
		expect(report).toMatch(/if \(!correctedText\) found\.push\(/);
	});

	it('uses the vendored Checkbox for consent, with the privacy sentence as its hint', () => {
		expect(report).toMatch(/<Checkbox[^>]*hint=\{m\.report_consent_desc\(\)\}/s);
		expect(report).toMatch(/bind:checked=\{consent\}/);
		expect(report).not.toMatch(/checked=\{true\}|\$state\(true\)/);
	});

	it('reports what is missing instead of disabling Submit', () => {
		expect(report).toMatch(/disabled=\{loading\}/);
		expect(report).not.toMatch(/disabled=\{[^}]*(consent|correctedText)/);
		expect(report).toContain('<ErrorSummary');
	});

	it('keeps the Mon script font on the corrected-text field', () => {
		expect(report).toMatch(/<Textarea[^>]*class="font-mon"/s);
	});

	it('announces the result in a live region that is always mounted', () => {
		// The result or a save failure: the region says whichever there is, and is never conditional.
		expect(report).toMatch(/<Status message=\{resultMessage \|\| saveError\}/);
		expect(report).not.toMatch(/\{#if[^}]*\}\s*<Status/);
	});
});

describe('Save failures', () => {
	it('are said in words on both forms instead of only logged', () => {
		for (const [name, source] of [
			['Feedback', report],
			['Contribute', contribute]
		] as const) {
			expect(source, name).toMatch(/catch \(e\) \{[^}]*saveError = m\.error_save_failed\(\)/s);
			expect(source, name).toMatch(
				/\{#if saveError\}\s*<Note label=\{m\.error_label\(\)\}>\{saveError\}<\/Note>/
			);
		}
		const en = JSON.parse(
			readFileSync(new URL('../../messages/en.json', import.meta.url), 'utf-8')
		);
		expect(en.error_save_failed).toMatch(/Try again/);
	});
});

describe('Contribute form', () => {
	it('refuses an empty submission before saving and says what to do', () => {
		expect(contribute.indexOf('m.contribute_error_empty()')).toBeGreaterThan(-1);
		expect(contribute.indexOf('if (!transcription && !sourceFile) {')).toBeLessThan(
			contribute.indexOf('await saveRecord(')
		);
	});

	it('still saves as a contribution with consent implied by the page', () => {
		expect(contribute).toMatch(/'contribution',\s*true\s*\)/);
	});

	it('keeps the Mon script font on the textarea', () => {
		expect(contribute).toMatch(/<Textarea[^>]*class="font-mon"/s);
	});
});

describe('Neither form uses legacy names', () => {
	for (const [name, src] of [
		['report', report],
		['contribute', contribute],
		['ActionBox', actionBox]
	] as const) {
		it(`${name} has no legacy class`, () => {
			expect(src).not.toMatch(
				/zen-label|btn-primary|btn-secondary|focus-ring|section-label|uppercase|tracking-/
			);
		});
	}
});

describe('ActionBox file control', () => {
	it('is keyboard operable through a button and tints on dragover', () => {
		expect(actionBox).toContain('<Button');
		expect(actionBox).toContain('dropzone-active');
		expect(actionBox).toContain('m.form_remove_file()');
	});

	it('accepts a dropped file only when the picker would', () => {
		const accept = 'image/*,application/pdf';
		expect(acceptsFile({ name: 'a.png', type: 'image/png' }, accept)).toBe(true);
		expect(acceptsFile({ name: 'a.pdf', type: 'application/pdf' }, accept)).toBe(true);
		expect(acceptsFile({ name: 'a.exe', type: 'application/x-msdownload' }, accept)).toBe(false);
		expect(acceptsFile({ name: 'N.TXT', type: '' }, '.pdf,.txt')).toBe(true);
		expect(acceptsFile({ name: 'a.bin', type: '' }, '')).toBe(true);
	});

	it('says so, in words and politely, when a dropped file is not accepted', () => {
		// The drop handler records the rejection instead of ignoring the file ...
		expect(actionBox).toMatch(
			/if \(acceptsFile\(dropped, accept\)\) set\(dropped\);\s*else rejected = true;/
		);
		// ... shows it as the field's error with the existing, translated message ...
		expect(actionBox).toMatch(/error=\{shownError\}/);
		expect(actionBox).toMatch(
			/shownError = \$derived\(error \?\? \(rejected \? m\.error_file_type\(\)/
		);
		// ... clears it once a file is taken ...
		expect(actionBox).toMatch(/function set\(next: File \| null\) \{\s*rejected = false;/);
		// ... and announces it through a status region that exists before it has text.
		expect(actionBox).toMatch(
			/<p role="status" class="sr-only">\{rejected \? m\.error_file_type\(\) : ''\}<\/p>/
		);
	});
});
