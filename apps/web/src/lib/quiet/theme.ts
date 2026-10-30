/*
 * Theme mechanism, framework-agnostic (Svelte, React, plain HTML).
 *
 * - The theme is a `dark` class on <html>. Light is the absence of it.
 * - First visit follows the OS (`prefers-color-scheme`). A choice made with the toggle is
 *   saved in localStorage under `theme` as 'dark' or 'light' and wins from then on.
 * - THEME_INIT_SCRIPT runs inline in <head>, before first paint, so there is no flash. It
 *   also follows the OS while no choice is saved: switch the OS to dark and the page follows.
 * - The `theme-color` metas follow the canvas actually showing, not the OS scheme.
 *
 * THEME_COLOR repeats --bg-canvas because a <meta> cannot read CSS. It is the only colour
 * literal outside tokens.css, and tests/tokens.test.ts fails if it drifts from the token.
 */

export const THEME_STORAGE_KEY = 'theme';

// quiet-ds-allow: a <meta> cannot read CSS; tests/tokens.test.ts pins these to --bg-canvas.
export const THEME_COLOR = { light: '#fafafa', dark: '#18181b' } as const; // quiet-ds-allow

export type Theme = 'light' | 'dark';

export const THEME_INIT_SCRIPT = `(function () {
	function saved() {
		try {
			var v = localStorage.getItem('${THEME_STORAGE_KEY}');
			return v === 'dark' || v === 'light' ? v : null;
		} catch (e) {
			return null; /* Storage unavailable: the OS scheme decides. */
		}
	}
	var choice = saved();
	var query = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
	var dark = choice ? choice === 'dark' : !!query && query.matches;
	if (dark) document.documentElement.classList.add('dark');
	if (choice) {
		document.querySelectorAll('meta[name="theme-color"]').forEach(function (meta) {
			meta.setAttribute('content', dark ? '${THEME_COLOR.dark}' : '${THEME_COLOR.light}');
		});
	}
	if (!query) return;
	function follow(e) {
		if (saved()) return;
		document.documentElement.classList.toggle('dark', e.matches);
	}
	/* Safari before 14 has addListener only. */
	if (query.addEventListener) query.addEventListener('change', follow);
	else if (query.addListener) query.addListener(follow);
})();`;

/** The theme showing now, read from the class the init script set. */
export function currentTheme(): Theme {
	return document.documentElement.classList.contains('dark') ? 'dark' : 'light';
}

/** Show a theme, keep the browser toolbar on its canvas colour, and remember the choice. */
export function applyTheme(theme: Theme): void {
	const dark = theme === 'dark';
	document.documentElement.classList.toggle('dark', dark);
	document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => {
		meta.setAttribute('content', THEME_COLOR[theme]);
	});
	try {
		localStorage.setItem(THEME_STORAGE_KEY, theme);
	} catch {
		// Storage unavailable: the choice lasts for this page only.
	}
}
