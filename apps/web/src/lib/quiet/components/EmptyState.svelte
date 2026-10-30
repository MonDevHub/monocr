<script lang="ts">
	/*
	 * What a list, search or table shows when it has nothing: a heading that says why, one
	 * line of text, and the next step. Left-aligned, no icon, no illustration, no box. 48px of
	 * space above and below. It is present at load, so it has no live region role.
	 */
	import type { Snippet } from 'svelte';

	interface Props {
		/** Says what is empty ("No articles match these filters"). It names the region. */
		title: string;
		/** Heading level of the title. Change it to follow the outline. */
		level?: 2 | 3 | 4;
		/** One or two sentences: why it is empty and what to do. Use children for markup. */
		text?: string;
		/** Markup for the text, used instead of text. */
		children?: Snippet;
		/** The next step, after the text: a Button or a TextButton. */
		action?: Snippet;
	}

	const { title, level = 2, text, children, action }: Props = $props();

	const uid = $props.id();
	const headingId = $derived(`${uid}-title`);
</script>

<section aria-labelledby={headingId} class="py-12">
	<svelte:element this={`h${level}`} id={headingId} class="type-title">{title}</svelte:element>
	{#if children}
		<div class="mt-4 max-w-measure type-body">{@render children()}</div>
	{:else if text}
		<p class="mt-4 max-w-measure type-body">{text}</p>
	{/if}
	{#if action}
		<div class="mt-6">{@render action()}</div>
	{/if}
</section>
