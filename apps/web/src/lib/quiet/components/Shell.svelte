<script lang="ts">
	/*
	 * The site frame: skip link, fixed header, then one centred 768px column holding the
	 * page and the footer. Gutters 24px, 32px from 640px; 32/48/64px above and below.
	 * While the mobile drawer is open the column is inert and hidden from assistive
	 * technology. Render it once, in the root layout.
	 */
	import type { Snippet } from 'svelte';
	import type { NavItem } from '../types';
	import { drawerState } from '../state.svelte';
	import SkipLink from './SkipLink.svelte';
	import Header from './Header.svelte';

	interface Props {
		/** The header links, in order. Passed to Header. */
		nav: NavItem[];
		/** The current path (page.url.pathname). Marks the matching nav entry. */
		current: string;
		/** Controls after the nav links, in the bar and in the drawer. Defaults to ThemeToggle. */
		controls?: Snippet;
		/** The page: a PageLayout. */
		children: Snippet;
		/** Rendered inside the column after the page. Pass a Footer. */
		footer?: Snippet;
		/** The skip link's text. Change it when the site is not in English. */
		skipLabel?: string;
	}

	const { nav, current, controls, children, footer, skipLabel }: Props = $props();
</script>

<SkipLink label={skipLabel} />

<Header items={nav} {current} {controls} />

<div
	class="min-h-screen bg-canvas text-fg-primary transition-colors duration-500 ease-in-out"
	aria-hidden={drawerState.open || undefined}
	inert={drawerState.open}
>
	<div
		class="mx-auto max-w-column px-gutter py-shell sm:px-gutter-wide md:py-shell-md lg:py-shell-lg"
	>
		{@render children()}
		{@render footer?.()}
	</div>
</div>
