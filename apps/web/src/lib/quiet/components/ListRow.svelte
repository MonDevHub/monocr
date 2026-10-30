<script lang="ts">
	/*
	 * A flat row: a type-label title with a short secondary line. Linked rows bleed 16px
	 * left and right (-mx-4 px-4) so the text stays on the column edge while the hover tint
	 * (canvas-subtle at 50%, rounded 8px) gets room; the whole row is the link and at least
	 * 44px tall. `meta` sits beside the title from 640px; `description` always sits under it.
	 */
	import type { Snippet } from 'svelte';
	import { isExternal } from '../external';

	interface Props {
		/** Row title. The link's accessible name starts with it. */
		title: string;
		/** Makes the whole row a link. */
		href?: string;
		/** A year or short fact, beside the title from 640px (type-meta). */
		meta?: string;
		/** A sentence under the title (type-small-wrapped). Makes `stacked` default to true. */
		description?: string;
		/** Put the secondary content under the title at every width, instead of beside it from 640px. */
		stacked?: boolean;
		/** Custom secondary content. Replaces `meta` and `description`. */
		secondary?: Snippet;
		/** Heading level of the title. Change it to follow the outline. */
		level?: 2 | 3 | 4;
	}

	const {
		title,
		href,
		meta,
		description,
		stacked = description !== undefined,
		secondary,
		level = 3
	}: Props = $props();

	const side = $derived(stacked ? '' : 'sm:flex-row sm:items-baseline sm:justify-between sm:gap-4');
	const external = $derived(isExternal(href));
</script>

{#snippet content()}
	{#if secondary}
		{@render secondary()}
	{:else if description}
		<span class="max-w-measure type-small-wrapped">{description}</span>
	{:else if meta}
		<span class="type-meta">{meta}</span>
	{/if}
{/snippet}

<li class="block">
	{#if href}
		<a
			{href}
			target={external ? '_blank' : undefined}
			rel={external ? 'noopener noreferrer' : undefined}
			class={[
				'group -mx-4 flex min-h-target flex-col justify-center gap-1 rounded-lg px-4 py-2 transition-colors hover:bg-canvas-subtle/50',
				side
			]}
		>
			<svelte:element this={`h${level}`} class="type-label link">{title}</svelte:element>
			{@render content()}
		</a>
	{:else}
		<div class={['flex flex-col justify-center gap-1 py-2', side]}>
			<svelte:element this={`h${level}`} class="type-label">{title}</svelte:element>
			{@render content()}
		</div>
	{/if}
</li>
