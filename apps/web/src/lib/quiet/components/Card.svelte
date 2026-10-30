<script lang="ts">
	/*
	 * A flat, hairline container for one self-contained item: a 1px border-border edge,
	 * rounded-lg, 24px padding, no fill, no shadow. With href the whole card is one link:
	 * the title is the <a> and its ::after covers the card (ArticleRow's pattern), so the
	 * card has one tab stop and one accessible name. The hover tint is the only state.
	 */
	import type { Snippet } from 'svelte';

	interface Props {
		/** The element. Use "li" inside a <ul role="list"> and "article" or "section" for a titled unit; "section" and "article" take the title as their name. */
		as?: 'div' | 'article' | 'section' | 'li';
		/** The card's heading (type-label). Needed when href is set. */
		title?: string;
		/** Heading level of the title. Change it to follow the outline. */
		level?: 2 | 3 | 4;
		/** Makes the whole card one link to this address. The title is the link text. */
		href?: string;
		/** The card's content. */
		children: Snippet;
		/** A line of type-meta under a hairline, for a date or a count. */
		footer?: Snippet;
	}

	const { as = 'div', title, level = 3, href, children, footer }: Props = $props();

	const uid = $props.id();
	const headingId = $derived(`${uid}-title`);
	const named = $derived(title !== undefined && (as === 'section' || as === 'article'));
	const linked = $derived(href !== undefined && title !== undefined);
</script>

<svelte:element
	this={as}
	aria-labelledby={named ? headingId : undefined}
	class={[
		'relative flex flex-col rounded-lg hairline-box border-border p-6',
		linked && 'hover:bg-canvas-subtle/50'
	]}
>
	{#if title !== undefined}
		<svelte:element this={`h${level}`} id={headingId} class="mb-4 type-label">
			{#if linked}
				<a
					{href}
					class="link after:absolute after:inset-0 after:rounded-lg focus-visible:rounded-sm"
					>{title}</a
				>
			{:else}
				{title}
			{/if}
		</svelte:element>
	{/if}
	<div class="grow type-small-wrapped">{@render children()}</div>
	{#if footer}
		<div class="mt-4 hairline-t border-border/50 pt-4 type-meta">{@render footer()}</div>
	{/if}
</svelte:element>
