/**
 * Contrast of the colour tokens in src/app.css, read from the file itself.
 *
 * `--primary` was never redefined under `.dark`, so the focus ring, the docs
 * active link and selection were drawn in #1a1a1a on a #151517 canvas: 1.04:1.
 * No review of either theme on its own saw it, because the defect was a token
 * that one theme forgot. These tests read both theme blocks and hold the pairs
 * that matter to WCAG: 3:1 for a focus indicator, 4.5:1 for text.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('../app.css', import.meta.url), 'utf-8');

function block(selector: string): Record<string, string> {
	const start = css.indexOf(`\t${selector} {`);
	if (start < 0) throw new Error(`no ${selector} block in app.css`);
	const end = css.indexOf('\n\t}', start);
	const body = css.slice(start, end);
	const vars: Record<string, string> = {};
	for (const match of body.matchAll(/(--[\w-]+):\s*([^;]+);/g)) vars[match[1]] = match[2].trim();
	return vars;
}

const light = block(':root');
const dark = { ...light, ...block('.dark') };

type Rgb = [number, number, number];

function hex(value: string): Rgb {
	const m = /^#([0-9a-f]{6})$/i.exec(value);
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

/** `color-mix(in srgb, fg pct%, transparent)` painted over `bg`. */
function over(fg: Rgb, alpha: number, bg: Rgb): Rgb {
	return fg.map((c, i) => Math.round(c * alpha + bg[i] * (1 - alpha))) as Rgb;
}

const themes = { light, dark };

describe('theme tokens', () => {
	it('defines --primary in dark mode instead of inheriting the light value', () => {
		expect(block('.dark')['--primary']).toBeDefined();
	});

	for (const [name, t] of Object.entries(themes)) {
		it(`${name}: text drawn in --primary reads on the canvas (4.5:1)`, () => {
			expect(contrast(hex(t['--primary']), hex(t['--bg-canvas']))).toBeGreaterThanOrEqual(4.5);
		});

		it(`${name}: selected text reads on the selection background (4.5:1)`, () => {
			expect(contrast(hex(t['--fg-on-primary']), hex(t['--primary']))).toBeGreaterThanOrEqual(4.5);
		});
	}

	it('selection uses the text token paired with --primary, not a fixed colour', () => {
		const rule = /::selection\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
		expect(rule).toMatch(/background-color:\s*var\(--primary\)/);
		expect(rule).toMatch(/color:\s*var\(--fg-on-primary\)/);
	});

	it('the focus rings clear 3:1 against the canvas in both themes', () => {
		const mixed = /color-mix\(in srgb, var\(--primary\) (\d+)%, transparent\);/.exec(css);
		const ring = /@apply ring-primary\/(\d+) /.exec(css);
		expect(mixed, 'focus-ring box-shadow').not.toBeNull();
		expect(ring, 'global focus ring').not.toBeNull();
		for (const pct of [Number(mixed![1]), Number(ring![1])]) {
			for (const t of Object.values(themes)) {
				const canvas = hex(t['--bg-canvas']);
				const drawn = over(hex(t['--primary']), pct / 100, canvas);
				expect(contrast(drawn, canvas)).toBeGreaterThanOrEqual(3);
			}
		}
	});
});
