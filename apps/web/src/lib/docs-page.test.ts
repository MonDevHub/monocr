import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const page = readFileSync('src/routes/docs/+page.svelte', 'utf8');
const layout = readFileSync('src/routes/docs/+layout.svelte', 'utf8');

/**
 * The docs page is one quiet-ds column: a table of contents above the sections, plain
 * code blocks, real tabs. These guard the markup that carries that, read as text.
 */
describe('docs page', () => {
	const sectionIds = [
		'installation',
		'quick-start',
		'image-quality',
		'sdks',
		'cli-reference',
		'privacy',
		'license'
	];

	it('links every contents entry to a section with that id, in order', () => {
		const hrefs = [...page.matchAll(/href: '#([\w-]+)'/g)].map((x) => x[1]);
		const ids = [...page.matchAll(/<Section id="([\w-]+)"/g)].map((x) => x[1]);
		expect(hrefs).toEqual(sectionIds);
		expect(ids).toEqual(sectionIds);
	});

	it('owns one h1 and one main, and the layout adds neither', () => {
		expect(page.match(/<h1\b/g)).toHaveLength(1);
		expect(page.match(/<main id="main-content"/g)).toHaveLength(1);
		expect(layout).not.toMatch(/<main|<h1/);
	});

	it('uses the vendored CodeBlock and Tabs, with no hand-built code chrome', () => {
		expect(page).toContain('<CodeBlock');
		expect(page).toContain('<Tabs');
		expect(page).not.toMatch(/rounded-full|bg-red-|bg-yellow-|bg-green-|zen-code|font-mono|<pre/);
		expect(page).not.toMatch(/IntersectionObserver|sticky|<aside|scroll-mt/);
	});

	it('lists the four SDKs as tabs', () => {
		for (const name of ['JavaScript', 'Python', 'Go', 'Rust']) expect(page).toContain(`'${name}'`);
	});

	it('uses no legacy class names', () => {
		expect(page).not.toMatch(
			/glass|focus-ring|text-fg-muted|bg-primary|section-label|no-scrollbar/
		);
	});
});
