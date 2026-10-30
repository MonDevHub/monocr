<script lang="ts">
	/*
	 * Text in one of the six non-heading roles: body, label, subtitle, small, small-wrapped,
	 * meta. Each sets size, line height, weight and colour; add only layout classes.
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	interface Props extends HTMLAttributes<HTMLElement> {
		/** Type role: what the text is. */
		variant?: 'body' | 'label' | 'subtitle' | 'small' | 'small-wrapped' | 'meta';
		/** The element. Use `span` or `div` for text that is not a paragraph. */
		as?: 'p' | 'span' | 'div';
		/** The text. */
		children: Snippet;
	}

	const { variant = 'body', as = 'p', children, class: className, ...rest }: Props = $props();

	const ROLE = {
		body: 'type-body',
		label: 'type-label',
		subtitle: 'type-subtitle',
		small: 'type-small',
		'small-wrapped': 'type-small-wrapped',
		meta: 'type-meta'
	} as const;
</script>

<svelte:element this={as} class={[ROLE[variant], className]} {...rest}>
	{@render children()}
</svelte:element>
