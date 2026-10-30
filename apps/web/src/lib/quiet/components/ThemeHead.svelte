<script lang="ts">
	/*
	 * Everything quiet-ds needs in <head>, in order: the two theme-color metas (canvas
	 * colours), the pre-paint theme script (no flash of the wrong theme), and preloads for
	 * the two Inter weights. It also imports fonts.css. Render it once, in the root layout.
	 */
	import '../fonts.css';
	import inter400 from '../fonts/inter-latin-400-normal.woff2?url';
	import inter500 from '../fonts/inter-latin-500-normal.woff2?url';
	import { THEME_COLOR, THEME_INIT_SCRIPT } from '../theme';

	// Built from the constant, never user input.
	const script = '<script>' + THEME_INIT_SCRIPT + '</' + 'script>';
</script>

<svelte:head>
	<meta name="theme-color" content={THEME_COLOR.light} media="(prefers-color-scheme: light)" />
	<meta name="theme-color" content={THEME_COLOR.dark} media="(prefers-color-scheme: dark)" />
	<!-- eslint-disable-next-line svelte/no-at-html-tags -- a build-time constant from theme.ts -->
	{@html script}
	<link
		rel="preload"
		href={inter400}
		as="font"
		type="font/woff2"
		crossorigin="anonymous"
		fetchpriority="high"
	/>
	<link
		rel="preload"
		href={inter500}
		as="font"
		type="font/woff2"
		crossorigin="anonymous"
		fetchpriority="high"
	/>
</svelte:head>
