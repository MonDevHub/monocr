<script lang="ts">
	/*
	 * An article in an index: linked title with the year on the right, a one-sentence
	 * summary under it. Only the title is the link, so its accessible name is the title; a
	 * ::after overlay still makes the whole row clickable. Hover tints the row like ListRow.
	 */
	import { isExternal } from '../external';

	interface Props {
		/** Article title: the link text. */
		title: string;
		/** Where the article lives. */
		href: string;
		/** A year, right-aligned (type-meta). */
		meta?: string;
		/** One sentence (type-small-wrapped). */
		summary?: string;
		/** Heading level of the title. Change it to follow the outline. */
		level?: 2 | 3 | 4;
	}

	const { title, href, meta, summary, level = 3 }: Props = $props();

	const external = $derived(isExternal(href));
</script>

<li class="group relative -mx-4 rounded-lg px-4 py-3 hover:bg-canvas-subtle/50">
	<div class="flex items-baseline justify-between gap-4">
		<svelte:element this={`h${level}`} class="type-label">
			<a
				{href}
				target={external ? '_blank' : undefined}
				rel={external ? 'noopener noreferrer' : undefined}
				class="link after:absolute after:inset-0 after:rounded-lg focus-visible:rounded-sm"
				>{title}</a
			>
		</svelte:element>
		{#if meta}
			<span class="shrink-0 type-meta">{meta}</span>
		{/if}
	</div>
	{#if summary}
		<p class="mt-1 type-small-wrapped">{summary}</p>
	{/if}
</li>
