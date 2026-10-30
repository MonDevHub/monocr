<script lang="ts">
	/*
	 * The way back from an inner page (the header has no home link). Arrow plus label in
	 * the link style; the arrow nudges 4px left on hover. A ::before extends the hit area to
	 * 44px without moving anything, so the focus outline still hugs the text.
	 */
	import type { HTMLAnchorAttributes } from 'svelte/elements';
	import Icon from './Icon.svelte';

	interface Props extends HTMLAnchorAttributes {
		/** The parent page. */
		href?: string;
		/** Visible text and accessible name. Change it whenever the parent is not Home. */
		label?: string;
	}

	const { href = '/', label = 'Back to Home', class: className, ...rest }: Props = $props();
</script>

<a
	{href}
	class={[
		'group relative inline-flex items-center gap-2 type-label link before:absolute before:inset-x-0 before:-inset-y-3 focus-visible:rounded-sm',
		className
	]}
	{...rest}
>
	<Icon
		name="arrow-left"
		size={16}
		strokeWidth={2}
		class="h-4 w-4 transition-transform duration-300 group-hover:-translate-x-1"
	/>
	{label}
</a>
