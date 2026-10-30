/**
 * Markup and stylesheet rules that the page depends on, read from the source.
 *
 * The component tests need a browser, which `pnpm test` here does not start (the
 * `client` project matches only `*.svelte.test.ts`, and there are none). These
 * rules are class names and selectors, so they are checked where they are
 * written. A weaker guarantee than rendering them, and a cheap one: each of
 * these was a defect found in the audit, and each fails if the rule goes.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf-8');
const css = read('../app.css');
// quiet-ds owns the base layer, including reduced motion; the app adds only the Mon-script rules.
const quietCss = read('./quiet/quiet.css');
const page = read('../routes/+page.svelte');
const layout = read('../routes/+layout.svelte');

describe('Mon-script interface font', () => {
	it('puts PyidaungSu in the UI font stack when <html lang> is mnw or my', () => {
		const rule = /html:lang\(mnw\),\s*html:lang\(my\)\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
		expect(rule).toMatch(/--font-display:[^;]*'PyidaungSu'/);
		// Inter first: Latin text must not change.
		expect(rule.indexOf("'Inter'")).toBeLessThan(rule.indexOf("'PyidaungSu'"));
	});

	it('drops letter-spacing on Myanmar-script text', () => {
		expect(css).toMatch(/html:lang\(mnw\) \*,\s*html:lang\(my\) \*\s*\{\s*letter-spacing: normal/);
	});
});

describe('motion', () => {
	it('honours prefers-reduced-motion', () => {
		expect(quietCss).toMatch(/@media \(prefers-reduced-motion: reduce\)/);
	});
});

describe('landmarks', () => {
	it('has one #main-content: the page <main>, not also the layout wrapper', () => {
		expect(layout).not.toMatch(/id="main-content"/);
		expect(page.match(/id="main-content"/g)).toHaveLength(1);
	});
});

describe('result toolbar on a phone', () => {
	it('lays the actions out as a wrapping grid, with touch-sized buttons', () => {
		expect(page).toMatch(/grid grid-cols-2 gap-2 sm:flex sm:flex-wrap/);
		for (const handler of [
			'onclick={downloadText}',
			'onclick={copyText}',
			'onclick={reportError}'
		]) {
			const at = page.indexOf(handler);
			expect(at, handler).toBeGreaterThan(0);
			const button = page.slice(page.lastIndexOf('<button', at), at);
			expect(button, handler).toMatch(/min-h-11/);
		}
	});

	it('makes Clear a 44px target', () => {
		const at = page.indexOf('onclick={reset}');
		expect(page.slice(at, at + 300)).toMatch(/h-11 w-11/);
	});

	it('focuses Copy without scrolling the card under the fixed header', () => {
		expect(page).toMatch(/focus\(\{ preventScroll: true \}\)/);
		expect(page).toMatch(/scroll-mt-20/);
	});
});

describe('the result area is reachable and announced', () => {
	it('makes the scrolling text region focusable and labelled', () => {
		expect(page).toMatch(/tabindex="0"\s+role="region"/);
	});
	it('announces progress and results in a polite status line, and errors as alerts', () => {
		expect(page).toMatch(/role="status" aria-live="polite"/);
		expect(page).toMatch(/role="alert"/);
	});
	it('highlights the drop zone while a file is dragged over it', () => {
		expect(page).toContain('dropzone-active');
		expect(css).toMatch(/\.dropzone-active\s*\{/);
	});
});

describe('rotate and crop', () => {
	it('is offered after a read, for images only, and the one-tap path is unchanged', () => {
		expect(page).toMatch(/const canAdjust = \$derived\(file !== null && !isPdf && !reading/);
		// Choosing a file still starts the read at once.
		expect(page).toMatch(/startRead\(newFile\);\s*\}\s*function startRead/);
		// Applying an adjustment reads the adjusted file as a new run.
		expect(page).toMatch(
			/onapply=\{\(adjusted\) => \{\s*adjusting = false;\s*startRead\(adjusted\);/
		);
	});
});
