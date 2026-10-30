/**
 * Contrast of the colour tokens in src/lib/quiet/tokens.css, read from the file itself.
 *
 * The app takes its colours from quiet-ds. An earlier defect here was a token that one theme
 * forgot to redefine, so a focus ring and the docs links vanished into the dark canvas
 * (1.04:1). No review of either theme on its own saw it, so these tests read both theme
 * blocks and hold the pairs that matter to WCAG: 4.5:1 for text, 3:1 for a focus indicator
 * and for the edge of a control.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const tokens = readFileSync(new URL('./quiet/tokens.css', import.meta.url), 'utf-8');
const quiet = readFileSync(new URL('./quiet/quiet.css', import.meta.url), 'utf-8');

/** The custom properties of the first top-level block opened by `selector`. */
function block(selector: string): Record<string, string> {
	const start = tokens.indexOf(`\n${selector} {`);
	if (start < 0) throw new Error(`no ${selector} block in tokens.css`);
	const end = tokens.indexOf('\n}', start);
	const vars: Record<string, string> = {};
	for (const match of tokens.slice(start, end).matchAll(/(--[\w-]+):\s*([^;]+);/g))
		vars[match[1]] = match[2].trim();
	return vars;
}

const light = block(':root');
const dark = { ...light, ...block('.dark') };
const themes = { light, dark };

type Rgb = [number, number, number];

function hex(value: string): Rgb {
	const m = /^#([0-9a-f]{6})$/i.exec(value.split(' ')[0]);
	if (!m) throw new Error(`not a 6-digit hex colour: ${value}`);
	const n = parseInt(m[1], 16);
	return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function luminance([r, g, b]: Rgb): number {
	const lin = (c: number) => {
		const s = c / 255;
		return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
	};
	return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function contrast(a: Rgb, b: Rgb): number {
	const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
	return (hi + 0.05) / (lo + 0.05);
}

describe('theme tokens', () => {
	it('redefines every text and focus colour in dark mode instead of inheriting the light value', () => {
		const darkOnly = block('.dark');
		for (const name of [
			'--bg-canvas',
			'--fg-primary',
			'--fg-secondary',
			'--fg-accent',
			'--fg-on-primary'
		])
			expect(darkOnly[name], name).toBeDefined();
	});

	for (const [name, t] of Object.entries(themes)) {
		const canvas = () => hex(t['--bg-canvas']);

		it(`${name}: primary and secondary text read on the canvas (4.5:1)`, () => {
			expect(contrast(hex(t['--fg-primary']), canvas())).toBeGreaterThanOrEqual(4.5);
			expect(contrast(hex(t['--fg-secondary']), canvas())).toBeGreaterThanOrEqual(4.5);
		});

		it(`${name}: text on the primary fill reads (4.5:1)`, () => {
			expect(contrast(hex(t['--fg-on-primary']), hex(t['--fg-primary']))).toBeGreaterThanOrEqual(
				4.5
			);
			expect(contrast(hex(t['--fg-on-primary']), hex(t['--fg-accent']))).toBeGreaterThanOrEqual(
				4.5
			);
		});

		it(`${name}: the focus outline and a control's edge clear 3:1 on the canvas`, () => {
			expect(contrast(hex(t['--fg-accent']), canvas())).toBeGreaterThanOrEqual(3);
			expect(contrast(hex(t['--link-underline']), canvas())).toBeGreaterThanOrEqual(3);
		});
	}

	it('selection uses the accent with the text token paired with it, not a fixed colour', () => {
		const rule = /::selection\s*\{([^}]*)\}/.exec(quiet)?.[1] ?? '';
		expect(rule).toMatch(/background-color:\s*var\(--fg-accent\)/);
		expect(rule).toMatch(/color:\s*var\(--fg-on-primary\)/);
	});
});
