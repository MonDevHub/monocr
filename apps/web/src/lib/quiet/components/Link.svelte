<script lang="ts">
	/*
	 * The one text-link style: fg-primary text, a thin underline at rest that darkens on
	 * hover and focus. Inline inside prose, or `standalone` as its own 44px row (a "details"
	 * or "archive" link). An absolute http(s) URL opens in a new tab with rel=noopener.
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAnchorAttributes } from 'svelte/elements';
	import { isExternal } from '../external';

	interface Props extends HTMLAnchorAttributes {
		/** Destination. An absolute http(s) URL opens in a new tab. */
		href: string;
		/** A 44px row in type-label, for a link on its own line. */
		standalone?: boolean;
		/** `true` forces a new tab, `false` prevents it. Omit it to decide from `href`. */
		external?: boolean;
		/** The link text. It is the accessible name, so it says where the link goes. */
		children: Snippet;
	}

	const {
		href,
		standalone = false,
		external,
		children,
		class: className,
		...rest
	}: Props = $props();

	const opensNewTab = $derived(external ?? isExternal(href));
</script>

<a
	{href}
	target={opensNewTab ? '_blank' : undefined}
	rel={opensNewTab ? 'noopener noreferrer' : undefined}
	class={[
		standalone && 'inline-flex min-h-target items-center type-label',
		'link focus-visible:rounded-sm',
		className
	]}
	{...rest}>{@render children()}</a
>
