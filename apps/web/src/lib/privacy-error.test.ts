import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');
const privacy = read('../routes/privacy/+page.svelte');
const error = read('../routes/+error.svelte');

describe('privacy page', () => {
	it('uses the quiet page frame: one h1 via PageLayout, no hand-rolled main or legacy type', () => {
		expect(privacy).toContain('<PageLayout');
		expect(privacy).not.toMatch(/<main|<h1/);
		expect(privacy).not.toMatch(/font-bold|tracking-|uppercase|text-fg-muted|<strong>/);
	});

	it('has sentence-case, unnumbered section headings and a short version', () => {
		const titles = [...privacy.matchAll(/<Section title="([^"]+)"/g)].map((x) => x[1]);
		expect(titles.length).toBe(8);
		expect(titles[0]).toBe('The short version');
		for (const t of titles) {
			expect(t).not.toMatch(/^\d/);
			expect(t.slice(1)).toBe(
				t
					.slice(1)
					.toLowerCase()
					.replace(/^(gdpr|ccpa)/, (s) => s)
			);
		}
	});

	it('keeps the effective date and a build-injected version', () => {
		expect(privacy).toContain('Effective date: March 22, 2026');
		expect(privacy).toContain('__APP_VERSION__');
		expect(privacy).toContain('<MetaLine');
	});

	it('lists the two permissions as a description list', () => {
		expect(privacy).toContain('<DescriptionList items={permissions}');
		expect(privacy).toContain("term: 'Camera access'");
		expect(privacy).toContain("term: 'Photo library and file access'");
	});

	it('keeps the voluntary-contribution consent wording', () => {
		expect(privacy).toContain('This is strictly opt-in');
		expect(privacy).toContain('Revoking consent stops all future synchronizations');
	});
});

describe('error page', () => {
	it('owns the one main landmark and uses EmptyState', () => {
		expect(error).toContain('<main id="main-content"');
		expect(error).toContain('<EmptyState');
		expect(error).toContain('<h1 class="type-h1">{status}</h1>');
	});

	it('separates 404 from other errors and links Home and Docs', () => {
		expect(error).toContain('status === 404');
		expect(error).toContain('error_page_not_found_text');
		expect(error).toContain('error_page_generic_text');
		expect(error).toContain('<Button variant="primary" href="/">');
		expect(error).toContain('href="/docs"');
	});

	it('hard-codes no English chrome', () => {
		expect(error).not.toMatch(/>\s*[A-Z][a-z]+ [a-z]+/);
	});
});
