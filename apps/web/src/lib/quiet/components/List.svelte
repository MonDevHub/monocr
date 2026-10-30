<script lang="ts">
	/*
	 * A flat list of ListRow or ArticleRow items: no bullets, no dividers, no gap (each row
	 * is already 44px tall). role="list" keeps VoiceOver announcing it as a list after
	 * Tailwind's preflight removes the bullets.
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	interface Props extends HTMLAttributes<HTMLUListElement> {
		/** ListRow or ArticleRow items. */
		children: Snippet;
	}

	const { children, ...rest }: Props = $props();
</script>

<ul role="list" {...rest}>{@render children()}</ul>
