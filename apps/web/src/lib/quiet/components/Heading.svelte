<script lang="ts">
	/*
	 * A heading in one of the four heading roles. The level is the document outline; the
	 * variant is the look. Defaults pair them: 1 h1, 2 h2, 3 title, 4 label. One h1 per page.
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	interface Props extends HTMLAttributes<HTMLHeadingElement> {
		/** The element, h1 to h4. Follow the outline. */
		level: 1 | 2 | 3 | 4;
		/** Type role. Defaults from the level; set it only when the look must differ. */
		variant?: 'h1' | 'h2' | 'title' | 'label';
		/** Hide it from view (sr-only) but keep it in the outline for screen readers. */
		visuallyHidden?: boolean;
		/** The heading text, sentence case. */
		children: Snippet;
	}

	const {
		level,
		variant,
		visuallyHidden = false,
		children,
		class: className,
		...rest
	}: Props = $props();

	const BY_LEVEL = { 1: 'h1', 2: 'h2', 3: 'title', 4: 'label' } as const;
	const ROLE = {
		h1: 'type-h1',
		h2: 'type-h2',
		title: 'type-title',
		label: 'type-label'
	} as const;
	const role = $derived(ROLE[variant ?? BY_LEVEL[level]]);
</script>

<svelte:element this={`h${level}`} class={[visuallyHidden ? 'sr-only' : role, className]} {...rest}>
	{@render children()}
</svelte:element>
