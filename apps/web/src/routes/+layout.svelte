<script lang="ts">
	import '../app.css';
	import '@fontsource/inter/latin-400.css';
	import '@fontsource/inter/latin-500.css';
	import '@fontsource/inter/latin-600.css';
	import '@fontsource/inter/latin-700.css';
	import '@fontsource/jetbrains-mono/latin-400.css';
	import '@fontsource/jetbrains-mono/latin-500.css';
	// @ts-expect-error virtual module
	import { pwaInfo } from 'virtual:pwa-info';

	// Import font files for preloading
	import inter400 from '@fontsource/inter/files/inter-latin-400-normal.woff2?url';
	import inter500 from '@fontsource/inter/files/inter-latin-500-normal.woff2?url';

	import { onMount } from 'svelte';
	import { logger } from '$lib/logger';
	import { SEO, Header, Footer } from '$lib/components';
	import { initLanguage, currentLang } from '$lib/i18n';
	import * as m from '$lib/paraglide/messages';
	import { syncService } from '$lib/services/sync-service';
	import SkipLink from '$lib/quiet/components/SkipLink.svelte';
	import { drawerState } from '$lib/quiet/state.svelte';
	import { THEME_INIT_SCRIPT } from '$lib/quiet/theme';

	const { children } = $props();

	// Built from a constant, never user input.
	const themeScript = '<script>' + THEME_INIT_SCRIPT + '</' + 'script>';

	onMount(async () => {
		try {
			initLanguage();
			syncService.start();
		} catch (error) {
			logger.error('lang init failed:', error);
		}

		if (pwaInfo) {
			// @ts-expect-error virtual module
			const { registerSW } = await import('virtual:pwa-register');

			// Use requestIdleCallback if available, otherwise fallback to setTimeout
			// This ensures SW registration doesn't block main thread during hydration/interactivity
			const idleCallback = window.requestIdleCallback || ((cb) => setTimeout(cb, 1));

			idleCallback(() => {
				registerSW({
					immediate: true,
					onRegisterError(error: unknown) {
						// Quietly fail in production, log only in dev if needed
						logger.error('SW registration error', error);
					}
				});
			});
		}
	});
</script>

<svelte:head>
	<!-- applies the saved or OS theme before first paint: no flash. Same key and class as ever. -->
	<!-- eslint-disable-next-line svelte/no-at-html-tags -- a build-time constant from theme.ts -->
	{@html themeScript}

	<!-- favicon -->
	<link rel="icon" type="image/x-icon" href="/favicon.ico?v=2" />

	<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png?v=2" />

	<!-- preload critical fonts -->
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

	<!-- load manifest async -->
</svelte:head>

<SkipLink label={m.a11y_skip_to_main()} />

<Header />

<div
	class="bg-canvas text-fg-primary min-h-screen transition-colors duration-500 ease-in-out"
	lang={$currentLang}
	aria-hidden={drawerState.open || undefined}
	inert={drawerState.open}
>
	<div
		class="max-w-column px-gutter py-shell sm:px-gutter-wide md:py-shell-md lg:py-shell-lg mx-auto"
	>
		<SEO />
		{@render children?.()}
		<Footer />
	</div>
</div>
