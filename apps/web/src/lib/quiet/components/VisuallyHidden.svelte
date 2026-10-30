<script lang="ts">
	/*
	 * Text for assistive technology only: a heading kept for the outline, or a suffix that
	 * makes a repeated link label unique ("Project details, MonOCR").
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	interface Props extends HTMLAttributes<HTMLSpanElement> {
		/** The hidden text. */
		children: Snippet;
	}

	const { children, class: className, ...rest }: Props = $props();
</script>

<span class={['sr-only', className]} {...rest}>{@render children()}</span>
