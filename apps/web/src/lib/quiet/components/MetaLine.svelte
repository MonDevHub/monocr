<script lang="ts">
	/*
	 * One line of metadata under a title: a date, a reading time, tags. Everything is
	 * type-meta; a tag is a plain word or a text link, never a chip, a pill or a badge. The
	 * middle dots are decorative (aria-hidden). The spaces around each dot sit outside the
	 * hidden span, so a screen reader still hears a gap between items once the dot is
	 * dropped ("12 Oct 2026 4 min read", not "12 Oct 20264 min read"); on screen it reads
	 * "a · b".
	 * A link here is inline in a line of text (the 44px exception), so keep at least one
	 * plain item beside it. An absolute http(s) URL opens in a new tab, as Link does.
	 */
	import { isExternal } from '../external';

	interface Props {
		/** The items, in order. A string shows as plain text; { label, href } as a text link. An absolute http(s) href opens in a new tab. */
		items: (string | { label: string; href: string })[];
	}

	const { items }: Props = $props();
</script>

{#if items.length > 0}
	<p class="type-meta">
		{#each items as item, i (i)}
			<!-- eslint-disable-next-line svelte/no-useless-mustaches -- Svelte trims a plain or &#32; space at a block's edge; these two must render -->
			{#if i > 0}{' '}<span aria-hidden="true">·</span>{' '}{/if}
			{#if typeof item === 'string'}
				{item}
			{:else}
				{@const external = isExternal(item.href)}
				<a
					href={item.href}
					target={external ? '_blank' : undefined}
					rel={external ? 'noopener noreferrer' : undefined}
					class="link focus-visible:rounded-sm">{item.label}</a
				>
			{/if}
		{/each}
	</p>
{/if}
