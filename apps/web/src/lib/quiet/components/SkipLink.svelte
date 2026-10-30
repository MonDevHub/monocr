<script lang="ts">
	/*
	 * The first focusable element on the page. Hidden until focused, then fixed top-left
	 * above everything. Inert while the mobile drawer is open, so Tab cannot leave the drawer
	 * through it. Shell renders it; render it yourself only without Shell.
	 */
	import type { HTMLAnchorAttributes } from 'svelte/elements';
	import { drawerState } from '../state.svelte';

	interface Props extends HTMLAnchorAttributes {
		/** The id to jump to: the id on <main>. Change it only when <main> has another id. */
		href?: string;
		/** The text shown on focus, and the accessible name. */
		label?: string;
	}

	const {
		href = '#main-content',
		label = 'Skip to main content',
		class: className,
		...rest
	}: Props = $props();
</script>

<a
	{href}
	inert={drawerState.open}
	class={[
		'sr-only transition-all duration-200 focus:not-sr-only focus:fixed focus:top-4 focus:left-4 focus:z-(--layer-skip) focus:rounded-sm focus:bg-fg-primary focus:px-4 focus:py-2 focus:text-on-primary',
		className
	]}
	{...rest}>{label}</a
>
